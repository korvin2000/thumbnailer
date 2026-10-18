import { Subject } from 'rxjs';
import { Album, GatewayEvent, Op, OpIssue, Preflight, PreflightItem } from './model';
import { delay, gate, Resolver, targets } from './mock-gateway';

/** What operations need from the album side of the mock; `renameRoot` = the settings follow a renamed root (the server's `rebasing` phase). */
export interface OpsHost { scanning(): boolean; roots(): string[]; sync(ids?: string[]): void; find: Resolver; renameRoot(from: string, to: string): void }

const base = (p: string) => p.slice(p.lastIndexOf('/') + 1);
const parent = (p: string) => p.slice(0, p.lastIndexOf('/'));
const below = (p: string, dir: string) => p === dir || p.startsWith(dir.replace(/\/+$/, '') + '/');
const NEEDS_TARGET = ['MOVE', 'COPY', 'COPY_PHOTOS', 'FOLDER_RENAME', 'FOLDER_MOVE', 'FOLDER_COPY'];

/** File operations emulation: a preflight, then one operation at a time with `opProgress` every 150 ms per item and one `opResult`; effects land on the album map. */
export class MockOps {
  private current: { id: string; cancelled: boolean } | null = null;
  private seq = 0;

  constructor(private readonly albums: Map<string, Album>, private readonly events: Subject<GatewayEvent>, private readonly host: OpsHost) {}

  get busy() { return !!this.current; }

  async preflight(op: Op): Promise<Preflight> {
    await delay(120);
    const plan = this.plan(op), items = plan.map(p => p.item);
    const bytes = plan.reduce((s, p) => s + (p.item.skip || !p.album ? 0 : op.kind === 'COPY_PHOTOS' ? p.album.bytes / p.album.imageCount : p.album.bytes), 0);
    const leaving = (op.kind === 'MOVE' || op.kind === 'FOLDER_MOVE') && op.target && !this.root(op.target);
    const consequences = [
      ...(op.kind === 'DELETE' || op.kind === 'FOLDER_DELETE' ? [`${items.filter(i => !i.skip).length} albums are deleted from disk`] : []),
      ...(leaving ? ['The target lies outside every indexed folder: the albums leave the library'] : []),
    ];
    return { items, bytes: Math.round(bytes), free: 50e9, warnings: [], consequences };
  }

  /** Refused while the scan runs or another operation runs (the server's gate), and when the selection is invalid or empty. */
  async run(op: Op): Promise<string> {
    await delay(40);
    gate(this.host.scanning(), this.busy);
    if (NEEDS_TARGET.includes(op.kind) && !op.target) throw new Error('No target folder');
    const o = { ...op, target: op.target?.replace(/\/+$/, '') }, plan = this.plan(o);
    if (!plan.length) throw new Error('Nothing to do');
    const job = { id: `op-${++this.seq}`, cancelled: false };
    this.current = job;
    void this.work(o, plan, job);
    return job.id;
  }

  async cancel() { if (this.current) this.current.cancelled = true; }

  private async work(op: Op, plan: { album?: Album; item: PreflightItem }[], job: { id: string; cancelled: boolean }) {
    const started = new Date().toISOString(), skipped: OpIssue[] = [], failed: OpIssue[] = [];
    let done = 0;
    if (op.kind === 'FOLDER_RENAME') { const from = op.paths![0].replace(/\/+$/, ''); if (this.host.roots().some(r => r.replace(/\/+$/, '') === from)) this.host.renameRoot(from, `${parent(from)}/${op.target}`); }
    const progress = (running: boolean, current: string | null, message: string | null = null) => this.events.next({
      type: 'opProgress', id: job.id, kind: op.kind, running, total: plan.length, done, current, skipped: skipped.length, failed: failed.length, message,
      startedAt: started, finishedAt: running ? null : new Date().toISOString(),
    });
    progress(true, null);
    for (const { album, item } of plan) {
      if (job.cancelled) break;
      await delay(150);
      if (item.skip) skipped.push({ item: item.path, reason: item.reason ?? '' });
      else if (!album || !this.albums.has(album.id)) failed.push({ item: item.path, reason: 'Album not found' });
      else { this.apply(op, this.albums.get(album.id)!); this.host.sync([]); done++; }
      progress(true, item.path);
    }
    this.current = null;
    progress(false, null, job.cancelled ? 'Cancelled' : null);
    this.events.next({ type: 'opResult', id: job.id, done, skipped, failed });
  }

  /** What the operation walks: albums below the folders (FOLDER_*), the photos of one album (COPY_PHOTOS), else the target albums with skip reasons. */
  private plan(op: Op): { album?: Album; item: PreflightItem }[] {
    if (op.kind.startsWith('FOLDER_')) return [...this.albums.values()].filter(a => (op.paths ?? []).some(p => below(a.path, p))).map(album => ({ album, item: { path: album.path, skip: false } }));
    if (op.kind === 'COPY_PHOTOS') { const album = this.albums.get(op.albumIds?.[0] ?? ''); return album ? (op.names ?? []).map(n => ({ album, item: { path: `${album.path}/${n}`, skip: false } })) : []; }
    return targets(this.albums, op, this.host.find).map(album => {
      const reason = op.kind === 'PACK' && album.kind !== 'DIRECTORY' ? 'already packed' : op.kind === 'UNPACK' && album.kind === 'DIRECTORY' ? 'already unpacked' : null;
      return { album, item: reason ? { path: album.path, skip: true, reason } : { path: album.path, skip: false } };
    });
  }

  private root(path: string) { return this.host.roots().find(r => below(path, r)); }

  /** One album's effect, broadcast as the server would. */
  private apply(op: Op, a: Album): void {
    const put = (x: Album) => { this.albums.set(x.id, x); this.events.next({ type: 'albumChanged', album: x }); };
    const relocate = (path: string) => { const root = this.root(path); if (root) put({ ...a, path, root, revision: a.revision + 1 }); else { this.albums.delete(a.id); this.events.next({ type: 'albumRemoved', albumId: a.id }); } };
    const copy = (path: string) => { const root = this.root(path); if (root) put({ ...a, id: `${a.id}-c${++this.seq}`, path, root, revision: 1, hidden: false }); };
    const folder = () => { const from = op.paths!.find(p => below(a.path, p))!.replace(/\/+$/, ''); return (op.kind === 'FOLDER_RENAME' ? `${parent(from)}/${op.target}` : `${op.target}/${base(from)}`) + a.path.slice(from.length); };
    switch (op.kind) {
      case 'PACK': { const z7 = op.format === 'SEVEN_ZIP'; return put({ ...a, kind: z7 ? 'ARCHIVE' : 'ZIP', path: a.path + (z7 ? '.7z' : '.zip'), revision: a.revision + 1 }); }
      case 'UNPACK': return put({ ...a, kind: 'DIRECTORY', path: a.path.replace(/\.(tar\.\w+|\w+)$/, ''), revision: a.revision + 1 });
      case 'REPACK': case 'CRYPT': return put({ ...a, revision: a.revision + 1 });
      case 'MOVE': return relocate(`${op.target}/${base(a.path)}`);
      case 'COPY': return copy(`${op.target}/${base(a.path)}`);
      case 'DELETE': case 'FOLDER_DELETE': this.albums.delete(a.id); return void this.events.next({ type: 'albumRemoved', albumId: a.id });
      case 'COPY_PHOTOS': return;
      case 'FOLDER_RENAME': case 'FOLDER_MOVE': return relocate(folder());
      case 'FOLDER_COPY': return copy(folder());
    }
  }
}
