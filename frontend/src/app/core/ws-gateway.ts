import { Subject } from 'rxjs';
import { Gateway } from './gateway';
import {
  Album, AlbumDetails, AlbumPatch, AlbumQuery, AlbumRef, AlbumTarget, Comparison, EntityCard, EntityKind, EntityList, EntityQuery, EntitySave, Facets, Filters,
  Folder, GatewayEvent, LibraryInfo, Listing, MetaRow, Op, PhotoList, PhotoMarks, Preflight, SearchResult, Settings, SettingsInfo,
} from './model';

interface Pending { resolve: (m: any) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }

const REQUEST_TIMEOUT_MS = 30_000;
/** Comparing copies hashes both albums in full and a preflight sizes every item: minutes on a slow disk, while the UI shows a spinner. */
const LONG_TIMEOUT_MS = 600_000;
const EVENTS = new Set(['albumChanged', 'albumRemoved', 'scanProgress', 'catalogChanged', 'opProgress', 'opResult']);
/** Broadcasts whose `id` is the operation's, never a request's. */
const OP_EVENTS = new Set(['opProgress', 'opResult']);
/** A target's query goes out as an `AlbumQuery` under the server's 500-album cap. */
const target = (t: AlbumTarget) => ({ albumIds: t.albumIds, query: t.query && { ...t.query, sort: 'NAME', offset: 0, limit: 500 } });

/** JSON-over-WebSocket client with id correlation and exponential reconnect; images come over plain HTTP. */
export class WsGateway extends Gateway {
  readonly events = new Subject<GatewayEvent>();
  readonly mock = false;

  private ready!: Promise<WebSocket>;
  private seq = 0;
  private backoff = 500;
  /** Last render spec epoch seen in a settings reply; a changed spec changes image URLs, bypassing the browser cache. */
  private epoch = '';
  private readonly pending = new Map<string, Pending>();

  constructor(private readonly url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`) {
    super();
    this.connect();
  }

  search = (query: AlbumQuery) => this.request<SearchResult>('search', { query });
  album = (albumId: string) => this.request<AlbumDetails>('album', { albumId });
  update = (albumId: string, patch: AlbumPatch) => this.request<{ album: Album }>('update', { albumId, patch }).then(r => r.album);
  scan = (paths?: string[]) => this.request<void>('scan', { paths: paths ?? null });
  cancelScan = () => this.request<void>('cancelScan', {});
  getSettings = () => this.request<SettingsInfo>('getSettings', {}).then(s => this.stamp(s));
  setSettings = (settings: Settings) => this.request<SettingsInfo>('setSettings', { settings }).then(s => this.stamp(s));
  facets = (filters?: Filters) => this.request<Facets>('facets', { query: filters ? { ...filters, sort: 'NAME', offset: 0, limit: 1 } : null });
  reveal = (albumId: string | null, path?: string) => this.request<void>('reveal', { albumId, ...(path ? { path } : {}) });
  folders = (path: string) => this.request<{ folders: Folder[] }>('folders', { path }).then(r => r.folders);
  browse = (path: string | null) => this.request<Listing>('browse', { path });
  library = () => this.request<LibraryInfo>('library', {});
  switchLibrary = (dir: string) => this.request<void>('switchLibrary', { dir });
  forgetLibrary = (dir: string) => this.request<LibraryInfo>('forgetLibrary', { dir });
  optimize = () => this.request<void>('optimize', {});

  entities = (kind: EntityKind, o: EntityQuery = {}) => this.request<EntityList>('entities', { kind, text: o.text, under: o.under, ...(o.unused ? { unused: true } : {}), sort: o.byCount ? 'COUNT' : 'NAME', offset: o.offset ?? 0, limit: o.limit ?? 100 });
  entity = (entityId: number) => this.request<EntityCard>('entity', { entityId });
  saveEntity = (entity: EntitySave) => this.request<EntityCard>('saveEntity', { entity });
  deleteEntities = (ids: number[]) => this.request<void>('deleteEntities', { ids });
  mergeEntities = (from: number[], into: number) => this.request<EntityCard>('mergeEntities', { from, into });
  confirmEntities = (ids: number[]) => this.request<void>('confirmEntities', { ids });
  creditAs = (modelId: number, studioId: number, alias: string, roles?: string) => this.request<EntityCard>('creditAs', { modelId, studioId, alias, roles });
  bulkUpdate = (t: AlbumTarget, patch: AlbumPatch) => this.request<{ count: number }>('bulkUpdate', { ...target(t), patch }).then(r => r.count);
  link = (t: AlbumTarget, entityId: number) => this.request<{ count: number }>('link', { ...target(t), entityId }).then(r => r.count);
  unlink = (t: AlbumTarget, entityId: number) => this.request<{ count: number }>('unlink', { ...target(t), entityId }).then(r => r.count);
  copies = (albumId: string) => this.request<{ albums: Album[] }>('copies', { albumId }).then(r => r.albums);
  compareCopies = (a: string, b: string) => this.request<Comparison>('compareCopies', { a, b }, LONG_TIMEOUT_MS);
  markPhoto = (albumId: string, name: string, mark: { rating?: number; lists?: number[] }) => this.request<{ marks: PhotoMarks }>('markPhoto', { albumId, name, ...mark }).then(r => r.marks);
  photos = (listId: number, offset: number, limit: number) => this.request<PhotoList>('photos', { listId, offset, limit });
  photoMeta = (albumId: string, index: number) => this.request<{ rows: MetaRow[] }>('photoMeta', { albumId, index }).then(r => r.rows);
  albumText = (albumId: string, name: string) => this.request<{ text: string }>('albumText', { albumId, name }).then(r => r.text);
  preflight = (op: Op) => this.request<Preflight>('preflight', { op: { ...op, ...target(op) } }, LONG_TIMEOUT_MS);
  runOp = (op: Op) => this.request<{ opId: string }>('op', { op: { ...op, ...target(op) } }).then(r => r.opId);
  cancelOp = () => this.request<void>('cancelOp', {});

  coverUrl = (a: Album) => `/img/${a.id}/cover?v=${a.revision}${this.r()}`;
  thumbUrl = (a: AlbumRef, i: number) => `/img/${a.id}/${i}/thumb?v=${a.revision}${this.r()}`;
  originalUrl = (a: AlbumRef, i: number) => `/img/${a.id}/${i}?v=${a.revision}`;

  private r() { return this.epoch ? `&r=${this.epoch}` : ''; }
  private stamp(s: SettingsInfo) { if (s.renderEpoch) this.epoch = s.renderEpoch; return s; }

  private connect() {
    const ws = new WebSocket(this.url);
    let failed!: (e: Error) => void;
    this.ready = new Promise<WebSocket>((resolve, reject) => {
      failed = reject;
      ws.onopen = () => {
        this.backoff = 500;
        resolve(ws);
        this.events.next({ type: 'connection', connected: true });
      };
    });
    this.ready.catch(() => { /* surfaced through the awaiting requests */ });
    ws.onmessage = e => this.receive(JSON.parse(e.data));
    ws.onclose = () => {
      failed(new Error('Connection lost'));
      this.events.next({ type: 'connection', connected: false });
      for (const p of this.pending.values()) p.reject(new Error('Connection lost'));
      this.pending.clear();
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 2, 10_000);
    };
  }

  /** Waits for the socket while it is (re)connecting, so requests issued at startup are not lost. */
  private async request<T>(type: string, payload: object, timeout = REQUEST_TIMEOUT_MS): Promise<T> {
    const ws = await this.ready;
    const id = String(++this.seq);
    return new Promise<T>((resolve, reject) => {
      if (ws.readyState !== WebSocket.OPEN) return reject(new Error('Not connected'));
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${type}: timeout`)); }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      ws.send(JSON.stringify({ type, id, ...payload }));
    });
  }

  private receive(m: { type: string; id?: string; message?: string } & Record<string, unknown>) {
    const p = m.id && !OP_EVENTS.has(m.type) ? this.pending.get(m.id) : undefined;
    if (p) {
      this.pending.delete(m.id!);
      clearTimeout(p.timer);
      m.type === 'error' ? p.reject(new Error(m.message ?? 'Request failed')) : p.resolve(m);
    }
    if (EVENTS.has(m.type)) this.events.next(m as unknown as GatewayEvent);
  }
}
