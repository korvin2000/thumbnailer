import { Subject } from 'rxjs';
import { Gateway } from './gateway';
import {
  Album, AlbumDetails, AlbumPatch, AlbumQuery, Facets, Filters, GatewayEvent, SearchResult, Settings, SettingsInfo,
} from './model';

interface Pending { resolve: (m: any) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }

const REQUEST_TIMEOUT_MS = 30_000;

/** JSON-over-WebSocket client with id correlation and exponential reconnect; images come over plain HTTP. */
export class WsGateway extends Gateway {
  readonly events = new Subject<GatewayEvent>();
  readonly mock = false;

  private ready!: Promise<WebSocket>;
  private seq = 0;
  private backoff = 500;
  private readonly pending = new Map<string, Pending>();

  constructor(private readonly url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`) {
    super();
    this.connect();
  }

  search = (query: AlbumQuery) => this.request<SearchResult>('search', { query });
  album = (albumId: string) => this.request<AlbumDetails>('album', { albumId });
  update = (albumId: string, patch: AlbumPatch) => this.request<{ album: Album }>('update', { albumId, patch }).then(r => r.album);
  scan = (roots?: string[]) => this.request<void>('scan', { roots: roots ?? null });
  cancelScan = () => this.request<void>('cancelScan', {});
  getSettings = () => this.request<SettingsInfo>('getSettings', {});
  setSettings = (settings: Settings) => this.request<SettingsInfo>('setSettings', { settings });
  facets = (filters?: Filters) => this.request<Facets>('facets', { query: filters ? { ...filters, sort: 'NAME', offset: 0, limit: 1 } : null });
  reveal = (albumId: string) => this.request<void>('reveal', { albumId });

  coverUrl = (a: Album) => `/img/${a.id}/cover?v=${a.revision}`;
  thumbUrl = (a: Album, i: number) => `/img/${a.id}/${i}/thumb?v=${a.revision}`;
  originalUrl = (a: Album, i: number) => `/img/${a.id}/${i}?v=${a.revision}`;

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
  private async request<T>(type: string, payload: object): Promise<T> {
    const ws = await this.ready;
    const id = String(++this.seq);
    return new Promise<T>((resolve, reject) => {
      if (ws.readyState !== WebSocket.OPEN) return reject(new Error('Not connected'));
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${type}: timeout`)); }, REQUEST_TIMEOUT_MS);
      this.pending.set(id, { resolve, reject, timer });
      ws.send(JSON.stringify({ type, id, ...payload }));
    });
  }

  private receive(m: { type: string; id?: string; message?: string } & Record<string, unknown>) {
    const p = m.id ? this.pending.get(m.id) : undefined;
    if (p) {
      this.pending.delete(m.id!);
      clearTimeout(p.timer);
      m.type === 'error' ? p.reject(new Error(m.message ?? 'Request failed')) : p.resolve(m);
    }
    if (m.type === 'albumChanged' || m.type === 'albumRemoved' || m.type === 'scanProgress') this.events.next(m as unknown as GatewayEvent);
  }
}
