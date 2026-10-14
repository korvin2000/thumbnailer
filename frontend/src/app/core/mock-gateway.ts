import { Subject } from 'rxjs';
import { Gateway } from './gateway';
import {
  Album, AlbumDetails, AlbumPatch, AlbumQuery, DEFAULT_SETTINGS, Entry, Facet, Facets, Filters, Folder, GatewayEvent, LibraryInfo,
  LibraryStats, Listing, SearchResult, Settings, SettingsInfo, Sort,
} from './model';

const STUDIOS = ['EL-Studio', 'CL-Biki', 'Lumen Lab', 'Northlight Photo', 'Atelier Noir', 'Prism Studio', null, null];
const MODELS = ['Ellena', 'Aurora', 'Roksana', 'Jeny', 'Felicia', 'Nelya', 'Mira', 'Dasha', 'Kira', 'Alina', 'Sofia', 'Vera', 'Lina', 'Nika', 'Polina', 'Eva'];
const PHOTOGRAPHERS = ['P. Ivanov', 'M. Weiss', 'A. Costa', 'J. Lindqvist', 'R. Tanaka', null];
const TAGS = ['wedding', 'outdoor', 'sport', 'advertising', 'studio', 'portrait', 'fashion', 'beauty', 'black-and-white', 'editorial', 'summer', 'winter', 'urban', 'nature', 'vintage'];
const BLURBS = ['Natural light session, soft tones.', 'Commercial shoot for a seasonal campaign.', 'Studio set with two strobes and a beauty dish.', 'Golden hour on the waterfront.', 'Editorial series, muted palette.', null, null];
const ROOTS = ['D:/Photos/Studio', 'E:/Archive/2019-2023'];
const SIDES = [1200, 1800, 2400, 3600, 6000];
const HOME = 'C:/Users/me';
const LIBRARIES = [`${HOME}/.thumbnailer`, 'D:/Libraries/archive-2023'];
/** The fake disk the picker browses: E:/Archive is "unplugged" (its root is unreachable) but a copy sits under D:/Backups. */
const DISK = ['C:/', 'D:/', 'E:/', 'C:/Users', HOME, `${HOME}/.thumbnailer`, `${HOME}/Pictures`, `${HOME}/Documents`, 'C:/Program Files', 'C:/Windows',
  'D:/Photos', 'D:/Photos/Studio', 'D:/Photos/Personal', 'D:/Photos/Clients', 'D:/Video', 'D:/Libraries', 'D:/Libraries/archive-2023',
  'D:/Backups', 'D:/Backups/Archive', 'D:/Backups/Archive/2019-2023', 'E:/Library', 'E:/Backup'];

/** Deterministic PRNG so the dummy library is the same on every reload. */
function rng(seed: number) {
  return () => {
    seed |= 0; seed = seed + 0x6d2b79f5 | 0;
    let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

/** In-browser dataset with canvas-painted covers; emulates search, editing and a scan without a backend. */
export class MockGateway extends Gateway {
  readonly events = new Subject<GatewayEvent>();
  readonly mock = true;

  private readonly albums = new Map<string, Album>();
  private readonly images = new Map<string, string>();
  private settings: Settings = { ...DEFAULT_SETTINGS, roots: ROOTS.map(path => ({ path, exclusions: ['*/raw/*'] })), exclusions: ['*/.*', '*/Thumbs.db'] };
  private scanning = false;
  private seq = 0;
  private libraryDir = LIBRARIES[0];
  private known = [...LIBRARIES];
  private slack = 1.45;

  constructor() {
    super();
    for (let i = 0; i < 180; i++) this.add(this.generate(i));
  }

  async search(q: AlbumQuery): Promise<SearchResult> {
    await delay(60);
    const all = [...this.albums.values()].filter(a => matches(a, q)).sort(comparator(q.sort));
    return { albums: all.slice(q.offset, q.offset + q.limit), total: all.length };
  }

  async album(id: string): Promise<AlbumDetails> {
    await delay(80);
    const album = this.albums.get(id);
    if (!album) throw new Error('Album not found');
    return { album, entries: this.entries(album) };
  }

  async update(id: string, patch: AlbumPatch): Promise<Album> {
    await delay(50);
    const a = this.albums.get(id);
    if (!a) throw new Error('Album not found');
    const next: Album = { ...a, revision: a.revision + 1 };
    for (const k of ['name', 'model', 'studio', 'photographer', 'description'] as const) {
      if (patch[k] !== undefined) (next as any)[k] = patch[k] === '' ? null : patch[k];
    }
    if (patch.name === '') next.name = a.name;
    if (patch.tags !== undefined) next.tags = [...new Set(patch.tags.map(t => t.toLowerCase().trim()).filter(Boolean))];
    if (patch.rating !== undefined) next.rating = Math.max(0, Math.min(10, patch.rating));
    if (patch.cover !== undefined) {
      next.coverPinned = patch.cover !== '';
      next.cover = patch.cover || '001.jpg';
      const e = this.entries(a).find(x => x.name === next.cover);
      if (e) next.coverPortrait = e.height > e.width;
    }
    this.albums.set(id, next);
    this.events.next({ type: 'albumChanged', album: next });
    return next;
  }

  /** `paths` are accepted (the fake scan always walks everything). */
  async scan(_paths?: string[]): Promise<void> {
    if (!this.scanning) void this.runScan();
  }

  private async runScan() {
    this.scanning = true;
    const started = new Date().toISOString();
    const progress = (p: Partial<Album> & { discovered: number; indexed: number; changed: number; root: string }) =>
      this.events.next({ type: 'scanProgress', running: this.scanning, root: p.root, discovered: p.discovered, indexed: p.indexed, changed: p.changed, removed: 0, message: null, startedAt: started, finishedAt: this.scanning ? null : new Date().toISOString() });
    const total = 24;
    for (let i = 0; i < total && this.scanning; i++) {
      await delay(140);
      if (i % 3 === 0) this.add(this.generate(1000 + this.seq++), true);
      progress({ root: ROOTS[i % 2], discovered: Math.min(total, i + 4), indexed: i + 1, changed: Math.floor(i / 3) });
    }
    this.scanning = false;
    progress({ root: ROOTS[1], discovered: total, indexed: total, changed: Math.floor(total / 3) });
  }

  async cancelScan() { this.scanning = false; }
  async getSettings(): Promise<SettingsInfo> { return this.info(); }
  /** Same rule as the server: folders and the path map cannot change under a running scan. */
  async setSettings(s: Settings): Promise<SettingsInfo> {
    const same = (k: keyof Settings) => JSON.stringify(s[k]) === JSON.stringify(this.settings[k]);
    if (this.scanning && !(same('roots') && same('pathMap'))) throw new Error('Stop the scan before changing folders');
    this.settings = s;
    return this.info();
  }
  async reveal() { await delay(30); }

  /** Child folders of `path` derived from album paths; '' lists the roots. */
  async folders(path: string): Promise<Folder[]> {
    await delay(40);
    const prefix = path ? path.replace(/\/+$/, '') + '/' : '';
    const kids = new Map<string, Folder>();
    for (const a of this.albums.values()) {
      if (!a.path.startsWith(prefix)) continue;
      const rest = prefix ? a.path.slice(prefix.length) : a.root;
      const name = prefix ? rest.split('/')[0] : rest, below = prefix ? rest.includes('/') : a.path !== a.root;
      if (!below && a.kind !== 'DIRECTORY') continue;
      const f = kids.get(name) ?? { name, path: prefix + name, albums: 0, nested: false };
      f.albums++; f.nested ||= below;
      kids.set(name, f);
    }
    return [...kids.values()].sort((x, y) => x.name.localeCompare(y.name));
  }

  async browse(raw: string | null): Promise<Listing> {
    await delay(120);
    const dirs = new Set([...DISK, ...[...this.albums.values()].filter(a => a.kind === 'DIRECTORY' && !a.path.startsWith('E:')).flatMap(a => { const p = a.path.split('/'); return p.map((_, i) => p.slice(0, i + 1).join('/')).map(d => d.endsWith(':') ? d + '/' : d); })]);
    const norm = (p: string) => { p = p.replace(/\\/g, '/').replace(/\/+$/, ''); return /^[A-Za-z]:$/.test(p) ? p + '/' : p; };
    let path = raw ? norm(raw) : HOME, error: string | null = null;
    if (!dirs.has(path)) {
      error = `${path} is not reachable`;
      while (!dirs.has(path) && path.includes('/')) path = parentOf(path) ?? 'C:/';
      if (!dirs.has(path)) path = 'C:/';
    }
    const folders = [...dirs].filter(d => parentOf(d) === path).map(d => ({ name: d.split('/').filter(Boolean).pop()!, path: d })).sort((x, y) => x.name.localeCompare(y.name));
    return { path, parent: parentOf(path), drives: ['C:/', 'D:/', 'E:/'], folders, error };
  }

  async library(): Promise<LibraryInfo> { await delay(90); return this.libraryInfo(); }

  /** The real server restarts on its new library; emulate the socket dropping and coming back. */
  async switchLibrary(dir: string) {
    await delay(60);
    this.libraryDir = dir;
    if (!this.known.includes(dir)) this.known.push(dir);
    this.events.next({ type: 'connection', connected: false });
    setTimeout(() => this.events.next({ type: 'connection', connected: true }), 1500);
  }

  async forgetLibrary(dir: string): Promise<LibraryInfo> {
    await delay(40);
    if (dir === this.libraryDir) throw new Error('Cannot forget the active library');
    this.known = this.known.filter(d => d !== dir);
    return this.libraryInfo();
  }

  async optimize() {
    if (this.scanning) throw new Error('A scan is running');
    await delay(300);
    this.slack = 1;
    this.events.next({ type: 'connection', connected: false }); // the real server restarts to compact the database
    setTimeout(() => this.events.next({ type: 'connection', connected: true }), 1200);
  }

  private libraryInfo(): LibraryInfo {
    const n = this.albums.size, covers = n * 11_900, meta = n * 2_600 + 512 * 1024;
    const stats: LibraryStats = {
      albums: n, images: [...this.albums.values()].reduce((s, a) => s + a.imageCount, 0), imageBytes: [...this.albums.values()].reduce((s, a) => s + a.bytes, 0),
      lastIndexedAt: new Date(Date.now() - 3_600_000).toISOString(),
      coversCount: n, coversBytes: covers, coversFile: Math.round(covers * this.slack) + 256 * 1024, metadataBytes: meta, metadataFile: Math.round(meta * this.slack) + 128 * 1024, settingsBytes: 410,
    };
    return { home: HOME, dir: this.libraryDir, known: [...this.known], pinned: false, stats, about: { version: 'dev', java: '26', os: 'Windows 10' } };
  }

  /** Longest-prefix rewrite, as `Containers.locate` on the server. */
  private locate(path: string): string {
    const m = this.settings.pathMap.filter(x => path.startsWith(x.from)).sort((a, b) => b.from.length - a.from.length)[0];
    return m ? m.to + path.slice(m.from.length) : path;
  }

  async facets(filters?: Filters): Promise<Facets> {
    const albums = [...this.albums.values()].filter(a => !filters || matches(a, filters));
    const count = (pick: (a: Album) => (string | null)[]): Facet[] => {
      const m = new Map<string, number>();
      for (const a of albums) for (const v of pick(a)) if (v) m.set(v, (m.get(v) ?? 0) + 1);
      return [...m].map(([value, count]) => ({ value, count })).sort((x, y) => y.count - x.count).slice(0, 50);
    };
    return { tags: count(a => a.tags), models: count(a => [a.model]), studios: count(a => [a.studio]), photographers: count(a => [a.photographer]) };
  }

  coverUrl(a: Album) { return this.paint(`${a.id}/cover/${a.cover}`, a.coverPortrait, 640, hash(a.id), 0); }
  thumbUrl(a: Album, i: number) { const e = this.entries(a)[i]; return this.paint(`${a.id}/${i}/t`, e.height > e.width, 480, hash(a.id), i + 1); }
  originalUrl(a: Album, i: number) { const e = this.entries(a)[i]; return this.paint(`${a.id}/${i}/o`, e.height > e.width, 1600, hash(a.id), i + 1); }

  private info(): SettingsInfo {
    return {
      settings: this.settings,
      dataDir: this.libraryDir,
      roots: this.settings.roots.map(r => { const location = this.locate(r.path); return { path: r.path, location, exists: !location.startsWith('E:'), albumCount: [...this.albums.values()].filter(a => a.root === r.path).length, lastScanAt: new Date(Date.now() - 3_600_000).toISOString(), storage: r.storage === 'SSD' || r.storage !== 'HDD' && location.startsWith('C:') ? 'SSD' : 'HDD' }; }),
      renderEpoch: hash(JSON.stringify([this.settings.covers, this.settings.thumbs])).toString(16),
    };
  }

  private add(a: Album, announce = false) {
    this.albums.set(a.id, a);
    if (announce) this.events.next({ type: 'albumChanged', album: a });
  }

  private generate(i: number): Album {
    const r = rng(i * 7919 + 17);
    const pick = <T>(xs: T[]) => xs[Math.floor(r() * xs.length)];
    const model = pick(MODELS), studio = pick(STUDIOS);
    const date = new Date(Date.UTC(2015 + Math.floor(r() * 11), Math.floor(r() * 12), 1 + Math.floor(r() * 28)));
    const shotDate = date.toISOString().slice(0, 10);
    const vol = 1 + Math.floor(r() * 30), zip = r() < 0.6, portrait = r() < 0.45;
    const side = pick(SIDES), short = Math.round(side * 2 / 3);
    const imageCount = 6 + Math.floor(r() ** 1.5 * 220);
    const tags = [...new Set(Array.from({ length: 1 + Math.floor(r() * 3) }, () => pick(TAGS)))];
    const name = `CLS-${shotDate} - ${studio ? studio + ' - ' : ''}${model} - (vol-${vol})`;
    const root = pick(ROOTS);
    return {
      id: `m${i.toString(36).padStart(4, '0')}`, root, path: `${root}/${shotDate.slice(0, 4)}/${name}${zip ? '.zip' : ''}`,
      kind: zip ? 'ZIP' : 'DIRECTORY', name, cover: pick(['001.jpg', '002.jpg', '003.jpg']), coverPinned: false, coverPortrait: portrait, revision: 1,
      imageCount, bytes: Math.round(imageCount * (300_000 + r() * 2_200_000) * side / 1800),
      avgPixels: side * short, maxWidth: portrait ? short : side, maxHeight: portrait ? side : short,
      shotDate, modifiedAt: new Date(date.getTime() + 86_400_000 * 3).toISOString(), indexedAt: new Date(Date.now() - r() * 86_400_000 * 30).toISOString(),
      model, studio, photographer: pick(PHOTOGRAPHERS), description: pick(BLURBS), tags, rating: r() < 0.5 ? 0 : 3 + Math.floor(r() * 8),
    };
  }

  private entries(a: Album): Entry[] {
    const r = rng(hash(a.id));
    return Array.from({ length: a.imageCount }, (_, index) => {
      const portrait = r() < (a.coverPortrait ? 0.75 : 0.25);
      const name = `${String(index + 1).padStart(3, '0')}.jpg`;
      return { index, name, bytes: Math.round(a.bytes / a.imageCount * (0.6 + r() * 0.8)), width: portrait ? a.maxHeight * 2 / 3 : a.maxWidth, height: portrait ? a.maxHeight : a.maxWidth * 2 / 3, shotAt: a.shotDate ? `${a.shotDate}T${String(9 + index % 9).padStart(2, '0')}:${String(index % 60).padStart(2, '0')}:00` : null };
    }).map(e => ({ ...e, width: Math.round(e.width), height: Math.round(e.height) }));
  }

  /** Abstract "studio" cover: gradient, soft blobs, a light band and a faint initial; cached as a data URL. */
  private paint(key: string, portrait: boolean, long: number, seed: number, variant: number): string {
    const cached = this.images.get(key);
    if (cached) return cached;
    const r = rng(seed * 31 + variant * 101);
    const w = portrait ? Math.round(long * 9 / 16) : long, h = portrait ? long : Math.round(long * 9 / 16);
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const g = c.getContext('2d')!;
    const hue = Math.floor(seed % 360), hue2 = (hue + 40 + Math.floor(r() * 80)) % 360;
    const bg = g.createLinearGradient(0, 0, w, h);
    bg.addColorStop(0, `oklch(${35 + r() * 20}% 0.09 ${hue})`);
    bg.addColorStop(1, `oklch(${20 + r() * 15}% 0.07 ${hue2})`);
    g.fillStyle = bg; g.fillRect(0, 0, w, h);
    for (let i = 0; i < 4; i++) {
      const x = r() * w, y = r() * h, rad = (0.25 + r() * 0.5) * Math.max(w, h);
      const rg = g.createRadialGradient(x, y, 0, x, y, rad);
      rg.addColorStop(0, `oklch(${60 + r() * 25}% 0.12 ${(hue + i * 25) % 360} / 0.5)`);
      rg.addColorStop(1, 'transparent');
      g.fillStyle = rg; g.fillRect(0, 0, w, h);
    }
    g.save(); g.translate(w / 2, h / 2); g.rotate(-0.5 + r());
    g.fillStyle = 'rgba(255,255,255,0.08)'; g.fillRect(-w, -h * 0.08, w * 2, h * 0.16); g.restore();
    const v = g.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.3, w / 2, h / 2, Math.max(w, h) * 0.75);
    v.addColorStop(0, 'transparent'); v.addColorStop(1, 'rgba(0,0,0,0.45)');
    g.fillStyle = v; g.fillRect(0, 0, w, h);
    g.fillStyle = 'rgba(255,255,255,0.18)';
    g.font = `600 ${Math.round(Math.min(w, h) * 0.55)}px Georgia, serif`;
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText(variant ? String(variant) : String.fromCharCode(65 + seed % 26), w / 2, h / 2);
    const url = c.toDataURL('image/jpeg', 0.8);
    this.images.set(key, url);
    return url;
  }
}

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

const delay = (ms: number) => new Promise(res => setTimeout(res, ms));

/** 'D:/a/b' → 'D:/a', 'D:/a' → 'D:/', 'D:/' → null. */
function parentOf(path: string): string | null {
  if (/^[A-Za-z]:\/$/.test(path)) return null;
  const i = path.lastIndexOf('/');
  return i < 0 ? null : i === 2 ? path.slice(0, 3) : path.slice(0, i);
}

const has = (hay: string | null, needle: string) => !!hay && hay.toLowerCase().includes(needle);
const eq = (a: string | null, b?: string) => !b || (a ?? '').toLowerCase() === b.toLowerCase();

/** Same semantics as the server's SQL filter. */
function matches(a: Album, q: Filters): boolean {
  const t = q.text?.trim().toLowerCase();
  const mp = a.avgPixels / 1e6;
  return (!t || has(a.name, t) || has(a.model, t) || has(a.studio, t) || has(a.photographer, t) || has(a.description, t) || a.tags.some(x => x.includes(t)))
    && (q.tags ?? []).every(x => a.tags.includes(x.toLowerCase()))
    && eq(a.model, q.model) && eq(a.studio, q.studio) && eq(a.photographer, q.photographer)
    && (!q.roots?.length || q.roots.some(r => a.path === r || a.path.startsWith(r.endsWith('/') ? r : r + '/'))) && (!q.kind || a.kind === q.kind)
    && (q.minBytes == null || a.bytes >= q.minBytes) && (q.maxBytes == null || a.bytes <= q.maxBytes)
    && (q.minCount == null || a.imageCount >= q.minCount) && (q.maxCount == null || a.imageCount <= q.maxCount)
    && (q.minMegapixels == null || mp >= q.minMegapixels) && (q.maxMegapixels == null || mp <= q.maxMegapixels)
    && (!q.dateFrom || (a.shotDate ?? '') >= q.dateFrom) && (!q.dateTo || (a.shotDate ?? '\uffff') <= q.dateTo)
    && (q.minRating == null || a.rating >= q.minRating) && (q.maxRating == null || a.rating <= q.maxRating);
}

function comparator(sort: Sort): (a: Album, b: Album) => number {
  const byName = (a: Album, b: Album) => a.name.localeCompare(b.name);
  const num = (f: (a: Album) => number, desc: boolean) => (a: Album, b: Album) => (desc ? f(b) - f(a) : f(a) - f(b)) || byName(a, b);
  const date = (desc: boolean) => (a: Album, b: Album) => {
    if (!a.shotDate || !b.shotDate) return (a.shotDate ? -1 : b.shotDate ? 1 : 0) || byName(a, b);
    return (desc ? b.shotDate.localeCompare(a.shotDate) : a.shotDate.localeCompare(b.shotDate)) || byName(a, b);
  };
  switch (sort) {
    case 'NEWEST': return date(true);
    case 'OLDEST': return date(false);
    case 'LARGEST': return num(a => a.bytes, true);
    case 'SMALLEST': return num(a => a.bytes, false);
    case 'MOST_PHOTOS': return num(a => a.imageCount, true);
    case 'FEWEST_PHOTOS': return num(a => a.imageCount, false);
    case 'BEST_QUALITY': return num(a => a.avgPixels, true);
    case 'TOP_RATED': return num(a => a.rating, true);
    case 'RECENTLY_INDEXED': return (a, b) => b.indexedAt.localeCompare(a.indexedAt) || byName(a, b);
    case 'NAME': return byName;
  }
}
