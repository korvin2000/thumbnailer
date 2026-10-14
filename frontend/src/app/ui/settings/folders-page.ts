import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, model, signal } from '@angular/core';
import { FolderPicker } from '../../core/folder-picker';
import { commonSuffixMapping, dir, normPath } from '../../core/paths';
import { Mapping, RootStatus, Settings } from '../../core/model';
import { AlbumStore } from '../../core/store';
import { Toasts } from '../../core/toasts';
import { Icon } from '../shared/icon';

const under = (path: string, root: string) => path.toLowerCase().startsWith(root.replace(/\/?$/, '/').toLowerCase());

/** Folders to index, exclusions, path mapping for a moved library, and scan control; edits go to the shared draft. */
@Component({
  selector: 'app-folders-page',
  imports: [Icon, DatePipe],
  template: `
    <h3>Folders</h3>
    <p class="hint">A folder holding photos, or a zip of photos, is one album; nested folders are albums of their own. Exclusions are glob patterns with <code>*</code>, matched against the full path.</p>

    <section class="card">
      <h4>Folders to index</h4>
      @for (r of draft().roots; track r.path) {
        @let st = status(r.path);
        <div class="root">
          <div class="head">
            <app-icon name="folder" [size]="15" />
            <span class="path mono" [title]="r.path">&lrm;{{ r.path }}&lrm;</span>
            @if (st) {
              <span class="status" [class.missing]="!st.exists" [title]="st.lastScanAt ? 'Last scan ' + (st.lastScanAt | date: 'medium') : 'Never scanned'">{{ st.exists ? st.albumCount + ' albums' : 'not reachable' }}</span>
              @if (!st.exists) { <button class="btn sm" (click)="locate(st)" title="Point to where this folder is on this machine"><app-icon name="folderOpen" [size]="13" /> Locate…</button> }
            } @else { <span class="status new">new</span> }
            <button class="btn icon-btn sm ghost" (click)="removeRoot(r.path)" title="Remove folder"><app-icon name="trash" [size]="14" /></button>
          </div>
          @if (st && st.location !== st.path) { <span class="hint muted found">found at <code>{{ st.location }}</code></span> }
          <input class="input sm mono" placeholder="Exclusions for this folder, e.g. */raw/*, *_tmp*" [value]="r.exclusions.join(', ')" (change)="setRoot(r.path, { exclusions: split($any($event.target).value) })" />
        </div>
      } @empty { <p class="hint muted">No folders yet.</p> }
      @if (replace(); as rp) {
        <div class="notice">
          <app-icon name="alert" [size]="15" />
          <div><b class="mono">{{ rp.path }}</b> contains {{ rp.roots.length }} indexed {{ rp.roots.length === 1 ? 'folder' : 'folders' }} (<span class="mono">{{ rp.roots.join(', ') }}</span>). Replace them with the parent? Albums and edits are kept.</div>
          <button class="btn sm primary" (click)="confirmReplace()">Replace</button>
          <button class="btn sm" (click)="replace.set(null)">Cancel</button>
        </div>
      }
      <form class="add" (submit)="addRoot(np.value); np.value = ''; $event.preventDefault()">
        <button class="btn sm" type="button" (click)="addViaPicker()"><app-icon name="folderOpen" [size]="14" /> Add folder…</button>
        <input #np class="input sm mono" placeholder="or type a path, e.g. D:\\Photos or /mnt/archive" />
        <button class="btn sm" type="submit"><app-icon name="plus" [size]="14" /> Add</button>
      </form>
    </section>

    <section class="card">
      <h4>Exclusions</h4>
      <label class="field"><span>Global exclusions</span><input class="input sm mono" [value]="draft().exclusions.join(', ')" (change)="patch({ exclusions: split($any($event.target).value) })" placeholder="*/.*, */Thumbs.db" /></label>
      <label class="check"><input type="checkbox" [checked]="draft().scanOnStart" (change)="patch({ scanOnStart: $any($event.target).checked })" /> Scan on start</label>
    </section>

    <section class="card">
      <h4>Path mapping (this machine)</h4>
      <p class="hint">A library moved from another machine finds its folders under a new prefix. Stored paths stay as they were created; each rule rewrites a prefix on this machine.</p>
      @for (m of draft().pathMap; track $index) {
        <div class="rule">
          <input class="input sm mono" placeholder="W:/" [value]="m.from" (change)="setRule($index, { from: dir(normPath($any($event.target).value)) })" />
          <button class="btn icon-btn sm ghost" title="Choose folder" (click)="pickRule($index, 'from')"><app-icon name="folderOpen" [size]="14" /></button>
          <app-icon name="arrowRight" [size]="14" class="arrow" />
          <input class="input sm mono" placeholder="X:/users/" [value]="m.to" (change)="setRule($index, { to: dir(normPath($any($event.target).value)) })" />
          <button class="btn icon-btn sm ghost" title="Choose folder" (click)="pickRule($index, 'to')"><app-icon name="folderOpen" [size]="14" /></button>
          <button class="btn icon-btn sm ghost" title="Remove rule" (click)="patch({ pathMap: draft().pathMap.filter((_, j) => j !== $index) })"><app-icon name="trash" [size]="14" /></button>
        </div>
      }
      <button class="btn sm" (click)="patch({ pathMap: [...draft().pathMap, { from: '', to: '' }] })"><app-icon name="plus" [size]="14" /> Add rule</button>
    </section>

    <section class="card">
      <h4>Scan</h4>
      <div class="row">
        @if (store.scan()?.running) {
          <button class="btn sm" (click)="store.cancelScan()">Cancel scan</button>
        } @else {
          <button class="btn sm primary" (click)="store.startScan()" [disabled]="dirty() || !draft().roots.length" [title]="dirty() ? 'Save the changes first' : 'Index new, changed, moved and removed albums'"><app-icon name="scan" [size]="14" /> Scan now</button>
        }
        @if (dirty()) { <span class="hint muted">Save your changes to scan with them.</span> }
      </div>
      @if (store.scan(); as s) {
        <div class="progress" [class.running]="s.running">
          <div class="track"><div class="fill" [style.width.%]="s.discovered ? Math.min(100, 100 * s.indexed / s.discovered) : 0"></div></div>
          <span>{{ s.running ? 'Scanning ' + s.root : 'Last scan' }} · {{ s.indexed }} / {{ s.discovered }} · {{ s.changed }} changed · {{ s.removed }} removed @if (s.message) { · {{ s.message }} }</span>
        </div>
      }
    </section>
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
    .root { display: flex; flex-direction: column; gap: 5px; padding: 8px 10px; margin-bottom: 6px; border-radius: var(--radius-s); background: var(--bg-elev); border: 1px solid var(--border); }
    .head { display: flex; align-items: center; gap: 8px; }
    .head app-icon { color: var(--text-3); }
    .path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
    .status { font-size: 11px; color: var(--success); white-space: nowrap; }
    .status.missing { color: var(--danger); }
    .status.new { color: var(--text-3); }
    .found { margin: -2px 0 0 23px; }
    .notice { display: flex; align-items: center; gap: 10px; padding: 8px 10px; margin: 6px 0; border-radius: var(--radius-s); border: 1px solid color-mix(in oklch, var(--accent) 50%, transparent); background: var(--accent-soft); font-size: 12px; }
    .notice app-icon { color: var(--accent); flex: none; }
    .notice div { flex: 1; }
    .add { display: flex; gap: 6px; margin-top: 8px; }
    .add input { flex: 1; }
    .rule { display: grid; grid-template-columns: 1fr auto auto 1fr auto auto; align-items: center; gap: 4px; margin-bottom: 6px; }
    .arrow { color: var(--text-3); margin: 0 2px; }
    .row { display: flex; align-items: center; gap: 10px; }
    .progress { margin-top: 10px; font-size: 11.5px; color: var(--text-2); }
    .track { height: 4px; border-radius: 2px; background: var(--border); margin-bottom: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--accent); transition: width 0.3s; }
    .running .fill { background-image: linear-gradient(90deg, transparent, oklch(100% 0 0 / 0.35), transparent); background-size: 200% 100%; animation: shimmer 1.2s linear infinite; }
    @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FoldersPage {
  protected readonly store = inject(AlbumStore);
  private readonly picker = inject(FolderPicker);
  private readonly toasts = inject(Toasts);
  protected readonly Math = Math;
  protected readonly normPath = normPath;
  protected readonly dir = dir;

  readonly draft = model.required<Settings>();
  /** Pending "replace nested roots with their parent" question. */
  protected readonly replace = signal<{ path: string; roots: string[] } | null>(null);

  protected dirty() { return JSON.stringify(this.draft()) !== JSON.stringify(this.store.settings()?.settings); }
  protected status(path: string): RootStatus | undefined { return this.store.settings()?.roots.find(r => r.path === path); }
  protected split(text: string): string[] { return text.split(/[,\n]/).map(s => s.trim()).filter(Boolean); }
  protected patch(p: Partial<Settings>) { this.draft.update(d => ({ ...d, ...p })); }
  protected setRoot(path: string, p: Partial<Settings['roots'][number]>) { this.patch({ roots: this.draft().roots.map(r => r.path === path ? { ...r, ...p } : r) }); }
  protected removeRoot(path: string) { this.patch({ roots: this.draft().roots.filter(r => r.path !== path) }); }
  protected setRule(i: number, p: Partial<Mapping>) { this.patch({ pathMap: this.draft().pathMap.map((m, j) => j === i ? { ...m, ...p } : m) }); }

  protected addRoot(raw: string) {
    const path = normPath(raw);
    if (!path) return;
    const roots = this.draft().roots.map(r => r.path);
    const covering = roots.find(r => r.toLowerCase() === path.toLowerCase() || under(path, r));
    if (covering) return this.toasts.show(`Already covered by ${covering}`);
    const nested = roots.filter(r => under(r, path));
    if (nested.length) return this.replace.set({ path, roots: nested });
    this.patch({ roots: [...this.draft().roots, { path, exclusions: [] }] });
  }

  protected confirmReplace() {
    const rp = this.replace();
    if (!rp) return;
    this.patch({ roots: [...this.draft().roots.filter(r => !rp.roots.includes(r.path)), { path: rp.path, exclusions: [] }] });
    this.replace.set(null);
  }

  protected async addViaPicker() {
    const path = await this.picker.pick({ title: 'Choose a folder to index', startAt: this.store.library()?.home ?? null });
    if (path) this.addRoot(path);
  }

  /** Picks where an unreachable root lives now and derives the prefix rule from the longest common path suffix. */
  protected async locate(st: RootStatus) {
    const picked = await this.picker.pick({ title: `Locate ${st.path}`, startAt: st.path.match(/^[A-Za-z]:\//)?.[0] ?? this.store.library()?.home ?? null });
    if (!picked) return;
    const rule = commonSuffixMapping(st.path, picked);
    const rest = this.draft().pathMap.filter(m => m.from !== rule.from);
    this.patch({ pathMap: [...rest, rule] });
    this.toasts.show(`Mapped ${rule.from} → ${rule.to}`);
  }

  protected async pickRule(i: number, side: 'from' | 'to') {
    const path = await this.picker.pick({ title: side === 'from' ? 'Prefix as stored in the library' : 'Prefix on this machine', startAt: this.draft().pathMap[i]?.[side] || null });
    if (path) this.setRule(i, { [side]: dir(path) });
  }
}
