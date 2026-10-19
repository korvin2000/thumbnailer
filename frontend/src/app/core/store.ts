import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { CatalogStore } from './catalog-store';
import { Gateway } from './gateway';
import {
  activeFilterCount, Album, AlbumPatch, AlbumQuery, ENTITY_KINDS, Facets, Filters, GatewayEvent, LibraryInfo, RootStatus, ScanProgress, Settings, SettingsInfo, Sort,
} from './model';
import { OpsStore } from './ops-store';
import { Prefs } from './prefs';
import { parseSearch } from './search-syntax';
import { Selection } from './selection';
import { Toasts } from './toasts';

const PAGE = 120;
const MAX_LIMIT = 1000;
const REFRESH_MAX_WAIT = 1500;

/** Single source of truth for the library screen: query → results, selection, facets, scan and settings state. */
@Injectable({ providedIn: 'root' })
export class AlbumStore {
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);
  private readonly catalog = inject(CatalogStore);
  private readonly ops = inject(OpsStore);
  private readonly prefs = inject(Prefs);

  readonly text = signal('');
  readonly filters = signal<Filters>({});
  readonly sort = signal<Sort>('NEWEST');

  readonly albums = signal<Album[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly selectedId = signal<string | null>(null);
  readonly hoveredId = signal<string | null>(null);
  /** The grid's multi-selection (bulk actions, operations); `selectedId` stays the one album the details panel shows. */
  readonly selection = new Selection();
  readonly facets = signal<Facets | null>(null);
  readonly scan = signal<ScanProgress | null>(null);
  readonly settings = signal<SettingsInfo | null>(null);
  readonly library = signal<LibraryInfo | null>(null);
  readonly connected = signal(true);
  readonly mock = this.gw.mock;

  readonly parsed = computed(() => parseSearch(this.text()));
  readonly query = computed<AlbumQuery>(() => {
    const p = this.parsed(), f = this.filters();
    const tags = [...new Set([...p.tags, ...(f.tags ?? [])])];
    return {
      ...f, text: p.text, tags: tags.length ? tags : undefined, duplicates: f.duplicates ?? (this.prefs.showDuplicates() ? undefined : false),
      model: p.model ?? f.model, studio: p.studio ?? f.studio, photographer: p.photographer ?? f.photographer, kind: p.kind ?? f.kind,
      sort: this.sort(), offset: 0, limit: PAGE,
    };
  });
  readonly activeFilters = computed(() => { const { sort, offset, limit, ...rest } = this.query(); return activeFilterCount(rest); });
  readonly byId = computed(() => new Map(this.albums().map(a => [a.id, a])));
  readonly selected = computed(() => this.byId().get(this.selectedId() ?? '') ?? null);
  readonly hovered = computed(() => this.byId().get(this.hoveredId() ?? '') ?? null);
  readonly roots = computed(() => this.settings()?.roots ?? []);
  /** The folder the grid is narrowed to (`filters.roots[0]`, a path-prefix filter); null = whole library. */
  readonly folder = computed(() => this.filters().roots?.[0] ?? null);
  /** Active criteria other than the folder, i.e. what the Refine tab owns. */
  readonly refining = computed(() => this.activeFilters() - (this.folder() ? 1 : 0));
  /** Bumped whenever stored folders may have changed (scan finished, reconnect, settings saved); the folder tree reloads on it. */
  readonly treeVersion = signal(0);

  private generation = 0;
  /** The query the current `albums` answer; pagination only continues while it is still the active query. */
  private loadedFor: AlbumQuery | null = null;
  private searchTimer?: ReturnType<typeof setTimeout>;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshSince = 0;
  private facetsTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    // another query shows other albums: a selection made over the old ones would act on albums no longer shown (a refresh keeps it: same query)
    effect(() => { const q = this.query(); untracked(() => { this.selection.clear(); this.runSearch(q, 120); }); });
    this.gw.events.subscribe(e => this.onEvent(e));
    this.loadSettings();
    this.loadFacets(0);
  }

  // ---- query ----

  setFilter<K extends keyof Filters>(key: K, value: Filters[K] | null) {
    this.filters.update(f => {
      const next = { ...f };
      if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) delete next[key]; else next[key] = value;
      return next;
    });
  }

  toggleTag(tag: string) {
    const tags = this.filters().tags ?? [];
    this.setFilter('tags', tags.includes(tag) ? tags.filter(t => t !== tag) : [...tags, tag]);
  }

  clearFilters() { this.filters.set({}); this.text.set(''); }

  /** Clears search text and refine criteria but keeps the current folder. */
  resetRefine() { const roots = this.filters().roots; this.filters.set(roots ? { roots } : {}); this.text.set(''); }

  // ---- folders ----

  goTo(path: string | null) { this.setFilter('roots', path ? [path] : null); }

  /** The configured root that `path` equals or lies below (case-insensitive, segment boundary), if any. */
  rootOf(path: string): RootStatus | null {
    const p = path.toLowerCase();
    return this.roots().find(r => { const q = r.path.toLowerCase().replace(/\/+$/, ''); return p === q || p.startsWith(q + '/'); }) ?? null;
  }

  /** Re-runs the current query keeping everything loaded so far; debounced, but never postponed beyond 1.5 s. */
  refresh(delay = 400) {
    const now = Date.now();
    if (!this.refreshTimer) this.refreshSince = now;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = undefined;
      this.runSearch({ ...this.query(), limit: Math.min(MAX_LIMIT, Math.max(PAGE, this.albums().length)) }, 0);
    }, Math.min(delay, Math.max(0, this.refreshSince + REFRESH_MAX_WAIT - now)));
  }

  async loadMore() {
    if (this.loading() || this.loadedFor !== this.query() || this.albums().length >= this.total()) return;
    const g = this.generation;
    this.loading.set(true);
    try {
      const r = await this.gw.search({ ...this.query(), offset: this.albums().length });
      if (g !== this.generation) return;
      const known = this.byId();
      this.albums.update(l => [...l, ...r.albums.filter(a => !known.has(a.id))]);
      this.total.set(r.total);
    } catch (e) { this.toasts.error(e, 'Loading failed: '); }
    finally { if (g === this.generation) this.loading.set(false); }
  }

  // ---- mutations ----

  async patch(id: string, patch: AlbumPatch): Promise<Album | null> {
    try {
      const a = await this.gw.update(id, patch);
      this.replace(a);
      this.loadFacets();
      return a;
    } catch (e) { this.toasts.error(e, 'Save failed: '); return null; }
  }

  /** The album's folder, or `path` itself (a folder of the tree; see `Gateway.reveal`). */
  async reveal(id: string | null, path?: string) {
    try { await this.gw.reveal(id, path); if (this.gw.mock) this.toasts.show('Would open the folder in your file manager'); }
    catch (e) { this.toasts.error(e, 'Cannot open folder: '); }
  }

  /** `paths`: roots or folders below them; none = every root. */
  async startScan(paths?: string[]) {
    try { await this.gw.scan(paths); this.toasts.show('Scan started'); }
    catch (e) { this.toasts.error(e, 'Scan failed: '); }
  }

  async cancelScan() {
    try { await this.gw.cancelScan(); } catch (e) { this.toasts.error(e); }
  }

  async saveSettings(s: Settings, done = 'Settings saved') {
    try { this.applySettings(await this.gw.setSettings(s)); this.treeVersion.update(v => v + 1); this.toasts.show(done, 'success'); return true; }
    catch (e) { this.toasts.error(e, 'Saving settings failed: '); return false; }
  }

  async loadSettings() {
    try { this.applySettings(await this.gw.getSettings()); } catch { /* offline: keep the last known settings */ }
  }

  private applySettings(info: SettingsInfo) {
    const was = this.settings()?.dataDir;
    this.settings.set(info);
    const f = this.folder(); // a folder of another library (after a switch) or of a removed root is no longer a valid scope
    if (f && !this.rootOf(f)) this.goTo(null);
    if (was !== undefined && was !== info.dataDir) { // another library: its entity ids are other entities here
      this.setFilter('entities', null); this.setFilter('missing', null); this.catalog.reset();
    }
  }

  // ---- library ----

  async loadLibrary() {
    try { this.library.set(await this.gw.library()); } catch (e) { this.toasts.error(e, 'Library info failed: '); }
  }

  /** The server restarts on the new library; the reconnect path reloads settings and results. */
  async switchLibrary(dir: string) {
    try { await this.gw.switchLibrary(dir); this.library.set(null); this.toasts.show('Switching library…'); }
    catch (e) { this.toasts.error(e, 'Switch failed: '); }
  }

  async forgetLibrary(dir: string) {
    try { this.library.set(await this.gw.forgetLibrary(dir)); } catch (e) { this.toasts.error(e, 'Forget failed: '); }
  }

  async optimize(): Promise<boolean> {
    try { await this.gw.optimize(); this.library.set(null); this.toasts.show('Optimizing… the library reopens in a moment'); return true; }
    catch (e) { this.toasts.error(e, 'Optimize failed: '); return false; }
  }

  /** Set once the server accepted `exit`: the app shows that it is closed. */
  readonly exited = signal(false);

  /** Asks the server to close for good (`Gateway.exit`); false when refused (an operation running), the toast says why. */
  async exit(): Promise<boolean> {
    try { await this.gw.exit(); this.exited.set(true); return true; }
    catch (e) { this.toasts.error(e, 'Exit failed: '); return false; }
  }

  loadFacets(delay = 800) {
    clearTimeout(this.facetsTimer);
    this.facetsTimer = setTimeout(async () => {
      try { this.facets.set(await this.gw.facets()); } catch { /* facets are decoration; search still works */ }
    }, delay);
  }

  // ---- internals ----

  /** Shows `a` in place of the album it updates, unless the one shown is already newer (a reply or event overtaken by a later one). */
  private replace(a: Album) { this.albums.update(l => l.map(x => x.id === a.id && a.revision >= x.revision ? a : x)); }

  private runSearch(q: AlbumQuery, delay: number) {
    clearTimeout(this.searchTimer);
    this.generation++;
    this.loadedFor = null;
    this.searchTimer = setTimeout(async () => {
      const g = this.generation, active = this.query();
      this.loading.set(true);
      try {
        const r = await this.gw.search(q);
        if (g !== this.generation) return;
        const known = this.byId();
        // a live event may have delivered a newer revision than the snapshot the search was built from
        this.albums.set(r.albums.map(a => { const cur = known.get(a.id); return cur && cur.revision > a.revision ? cur : a; }));
        this.total.set(r.total);
        this.loadedFor = active;
      } catch (e) { if (g === this.generation) this.toasts.error(e, 'Search failed: '); }
      finally { if (g === this.generation) this.loading.set(false); }
    }, delay);
  }

  private onEvent(e: GatewayEvent) {
    switch (e.type) {
      case 'albumChanged':
        if (this.byId().has(e.album.id)) this.replace(e.album);
        this.refresh(600);
        this.loadFacets(2000);
        break;
      case 'albumRemoved':
        if (this.byId().has(e.albumId)) { this.albums.update(l => l.filter(a => a.id !== e.albumId)); this.total.update(t => Math.max(0, t - 1)); }
        if (this.selectedId() === e.albumId) this.selectedId.set(null);
        if (this.selection.has(e.albumId)) this.selection.set([...this.selection.ids()].filter(id => id !== e.albumId));
        this.refresh(600);
        break;
      case 'scanProgress': {
        const wasRunning = this.scan()?.running;
        this.scan.set(e as ScanProgress);
        this.refresh(600);
        if (wasRunning && !e.running) { this.loadSettings(); this.loadFacets(0); this.treeVersion.update(v => v + 1); this.toasts.show(`Scan finished: ${e.indexed} indexed, ${e.changed} changed, ${e.removed} removed`, 'success'); }
        break;
      }
      case 'connection':
        this.connected.set(e.connected);
        if (e.connected) { this.loadSettings(); this.loadFacets(0); this.refresh(0); this.treeVersion.update(v => v + 1); this.catalog.onChanged(ENTITY_KINDS); }
        break;
      // catalog and operation state live in their own stores; this only routes
      case 'catalogChanged':
        this.catalog.onChanged(e.kinds);
        this.refresh(600);
        this.loadFacets(2000);
        break;
      case 'opProgress':
        this.ops.onProgress(e);
        break;
      case 'opResult': // paths, roots and folders may have moved, and deleted albums leave the entity counts (no `catalogChanged` says so)
        this.ops.onResult(e);
        this.loadSettings(); this.treeVersion.update(v => v + 1); this.refresh(600); this.catalog.onChanged(ENTITY_KINDS);
        break;
    }
  }
}
