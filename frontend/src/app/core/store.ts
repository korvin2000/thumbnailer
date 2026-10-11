import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Gateway } from './gateway';
import {
  activeFilterCount, Album, AlbumPatch, AlbumQuery, Facets, Filters, GatewayEvent, ScanProgress, Settings, SettingsInfo, Sort,
} from './model';
import { parseSearch } from './search-syntax';
import { Toasts } from './toasts';

const PAGE = 120;
const MAX_LIMIT = 1000;
const REFRESH_MAX_WAIT = 1500;

/** Single source of truth for the library screen: query → results, selection, facets, scan and settings state. */
@Injectable({ providedIn: 'root' })
export class AlbumStore {
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);

  readonly text = signal('');
  readonly filters = signal<Filters>({});
  readonly sort = signal<Sort>('NEWEST');

  readonly albums = signal<Album[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly selectedId = signal<string | null>(null);
  readonly hoveredId = signal<string | null>(null);
  readonly facets = signal<Facets | null>(null);
  readonly scan = signal<ScanProgress | null>(null);
  readonly settings = signal<SettingsInfo | null>(null);
  readonly connected = signal(true);
  readonly mock = this.gw.mock;

  readonly parsed = computed(() => parseSearch(this.text()));
  readonly query = computed<AlbumQuery>(() => {
    const p = this.parsed(), f = this.filters();
    const tags = [...new Set([...p.tags, ...(f.tags ?? [])])];
    return {
      ...f, text: p.text, tags: tags.length ? tags : undefined,
      model: p.model ?? f.model, studio: p.studio ?? f.studio, photographer: p.photographer ?? f.photographer, kind: p.kind ?? f.kind,
      sort: this.sort(), offset: 0, limit: PAGE,
    };
  });
  readonly activeFilters = computed(() => { const { sort, offset, limit, ...rest } = this.query(); return activeFilterCount(rest); });
  readonly byId = computed(() => new Map(this.albums().map(a => [a.id, a])));
  readonly selected = computed(() => this.byId().get(this.selectedId() ?? '') ?? null);
  readonly hovered = computed(() => this.byId().get(this.hoveredId() ?? '') ?? null);
  readonly roots = computed(() => this.settings()?.roots ?? []);

  private generation = 0;
  /** The query the current `albums` answer; pagination only continues while it is still the active query. */
  private loadedFor: AlbumQuery | null = null;
  private searchTimer?: ReturnType<typeof setTimeout>;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshSince = 0;
  private facetsTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    effect(() => { const q = this.query(); untracked(() => this.runSearch(q, 120)); });
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
      this.albums.update(l => l.map(x => x.id === a.id ? a : x));
      this.loadFacets();
      return a;
    } catch (e) { this.toasts.error(e, 'Save failed: '); return null; }
  }

  async reveal(id: string) {
    try { await this.gw.reveal(id); if (this.gw.mock) this.toasts.show('Would open the folder in your file manager'); }
    catch (e) { this.toasts.error(e, 'Cannot open folder: '); }
  }

  async startScan(roots?: string[]) {
    try { await this.gw.scan(roots); this.toasts.show('Scan started'); }
    catch (e) { this.toasts.error(e, 'Scan failed: '); }
  }

  async cancelScan() {
    try { await this.gw.cancelScan(); } catch (e) { this.toasts.error(e); }
  }

  async saveSettings(s: Settings) {
    try { this.settings.set(await this.gw.setSettings(s)); this.toasts.show('Settings saved', 'success'); return true; }
    catch (e) { this.toasts.error(e, 'Saving settings failed: '); return false; }
  }

  async loadSettings() {
    try { this.settings.set(await this.gw.getSettings()); } catch { /* offline: keep the last known settings */ }
  }

  loadFacets(delay = 800) {
    clearTimeout(this.facetsTimer);
    this.facetsTimer = setTimeout(async () => {
      try { this.facets.set(await this.gw.facets()); } catch { /* facets are decoration; search still works */ }
    }, delay);
  }

  // ---- internals ----

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
        if (this.byId().has(e.album.id)) this.albums.update(l => l.map(a => a.id === e.album.id ? e.album : a));
        this.refresh(600);
        this.loadFacets(2000);
        break;
      case 'albumRemoved':
        if (this.byId().has(e.albumId)) { this.albums.update(l => l.filter(a => a.id !== e.albumId)); this.total.update(t => Math.max(0, t - 1)); }
        if (this.selectedId() === e.albumId) this.selectedId.set(null);
        this.refresh(600);
        break;
      case 'scanProgress': {
        const wasRunning = this.scan()?.running;
        this.scan.set(e as ScanProgress);
        this.refresh(600);
        if (wasRunning && !e.running) { this.loadSettings(); this.loadFacets(0); this.toasts.show(`Scan finished: ${e.indexed} indexed, ${e.changed} changed, ${e.removed} removed`, 'success'); }
        break;
      }
      case 'connection':
        this.connected.set(e.connected);
        if (e.connected) { this.loadSettings(); this.loadFacets(0); this.refresh(0); }
        break;
    }
  }
}
