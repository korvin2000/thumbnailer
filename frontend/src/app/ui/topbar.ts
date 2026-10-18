import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, output, signal, untracked, viewChild } from '@angular/core';
import { CatalogStore } from '../core/catalog-store';
import { EntityKind } from '../core/model';
import { formatBytes, formatMegapixels } from '../core/format';
import { Prefs } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { Actions } from './actions';
import { KIND_ICON, KIND_LABEL, NOUN as KIND_NOUN } from './kinds';
import { ContextMenu, MenuEntry } from './shared/context-menu';
import { EntityPicker } from './shared/entity-picker';
import { Icon, IconName } from './shared/icon';
import { SortMenu } from './sort-menu';

interface Chip { key: string; label: string; icon?: IconName; remove: () => void }
/** Scope chip prefix and icon per entity kind (`entities` filter); tags read `#name`. */
const SCOPE: Record<EntityKind, [string, IconName]> = { MODEL: ['model:', 'user'], STUDIO: ['studio:', 'building'], PHOTOGRAPHER: ['by:', 'camera'], GENRE: ['genre:', 'sparkles'], TAG: ['#', 'hash'], LIST: ['list:', 'heart'] };
const NOUN = KIND_NOUN;
/** The Catalog menu: the kinds with profiles to edit, then the favorites lists. */
const CATALOG: EntityKind[] = ['MODEL', 'STUDIO', 'PHOTOGRAPHER', 'GENRE', 'LIST'];
/** A tag as a regex literal: tags may hold `+` or `.` (the server only strips `#` and whitespace), and a token ends where `search-syntax.ts`'s `[\w-]+` ends. */
const tagPattern = (t: string) => `#${t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w-])`;

@Component({
  selector: 'app-topbar',
  imports: [Icon, SortMenu],
  template: `
    <button class="brand" title="Thumbnailer — settings" (click)="settings.emit()"><span class="logo"><app-icon name="images" [size]="18" /></span><span class="name">Thumbnailer</span></button>

    <button class="btn icon-btn" [class.active]="prefs.sidebar() !== 'none'" [title]="(prefs.sidebar() === 'none' ? 'Show' : 'Hide') + ' the sidebar: Browse (E) · Refine (F)'" (click)="prefs.toggleSidebar()">
      <app-icon name="panelLeft" />
      @if (store.refining()) { <span class="count">{{ store.refining() }}</span> }
    </button>
    <button class="btn icon-btn" [class.active]="catalogOpen()" title="Catalog: edit or create models, studios, photographers, genres" (click)="openCatalogMenu($event)"><app-icon name="contact" /></button>

    <div class="search" [class.focused]="focused()">
      <app-icon name="search" [size]="16" />
      <input #q type="search" [value]="store.text()" (input)="store.text.set(q.value)" (focus)="focused.set(true)" (blur)="focused.set(false)"
             (keydown.escape)="onEscape(q, $event)" placeholder="Search albums · #tag · model: · studio: · by: · kind:zip" autocomplete="off" spellcheck="false" />
      <div class="chips">
        @for (c of chips(); track c.key) {
          <button class="chip active" (click)="c.remove()" [title]="'Remove ' + c.label">@if (c.icon) { <app-icon [name]="c.icon" [size]="12" /> }<span>{{ c.label }}</span><app-icon name="x" [size]="11" /></button>
        }
      </div>
      @if (store.loading()) { <span class="spinner"></span> }
      @if (store.text() || store.activeFilters()) {
        <button class="clear" title="Clear search and filters" (click)="store.clearFilters()"><app-icon name="x" [size]="14" /></button>
      }
    </div>

    <app-sort-menu />

    <div class="stepper" title="Albums per row ( [ and ] )">
      <button class="btn icon-btn sm ghost" (click)="prefs.setColumns(prefs.columns() - 1)" [disabled]="prefs.columns() <= 1"><app-icon name="minus" [size]="13" /></button>
      <span class="val">{{ prefs.columns() }}</span>
      <button class="btn icon-btn sm ghost" (click)="prefs.setColumns(prefs.columns() + 1)" [disabled]="prefs.columns() >= 12"><app-icon name="plus" [size]="13" /></button>
    </div>

    <button class="btn icon-btn" [title]="prefs.layoutMode() === 'bricks' ? 'Bricks layout: portrait covers in their own rows — switch to the grid' : 'Grid layout: strict order, one tile per cell — switch to bricks'" (click)="prefs.toggleLayout()"><app-icon [name]="prefs.layoutMode() === 'bricks' ? 'bricks' : 'grid'" /></button>

    <button class="btn icon-btn" [title]="(prefs.theme() === 'dark' ? 'Light' : 'Dark') + ' theme (T)'" (click)="prefs.toggleTheme()"><app-icon [name]="prefs.theme() === 'dark' ? 'sun' : 'moon'" /></button>
    <button class="btn icon-btn" [class.active]="prefs.detailsOpen()" title="Details panel (I)" (click)="prefs.detailsOpen.set(!prefs.detailsOpen())"><app-icon name="panelRight" /></button>
  `,
  styles: `
    :host { display: flex; align-items: center; gap: 8px; height: var(--topbar-h); padding: 0 12px; background: var(--bg-elev); border-bottom: 1px solid var(--border); }
    .brand { display: flex; align-items: center; gap: 8px; margin-right: 4px; padding: 2px 8px 2px 2px; border-radius: 9px; font-weight: 700; letter-spacing: -0.01em; transition: background 0.15s; }
    .brand:hover { background: var(--surface); }
    .brand:hover .logo { background: linear-gradient(135deg, var(--accent-strong), var(--accent)); }
    .logo { display: inline-flex; width: 30px; height: 30px; align-items: center; justify-content: center; border-radius: 8px; background: linear-gradient(135deg, var(--accent), var(--accent-strong)); color: var(--on-accent); }
    .search {
      flex: 1; min-width: 160px; display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 10px 0 12px; border-radius: 18px;
      background: var(--bg); border: 1px solid var(--border); color: var(--text-3); transition: border-color 0.15s, box-shadow 0.15s;
      &.focused { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); color: var(--text-2); }
    }
    .search input { flex: 1; min-width: 80px; height: 100%; border: 0; background: transparent; color: var(--text); font: inherit; outline: none; }
    .search input::placeholder { color: var(--text-3); }
    .search input::-webkit-search-cancel-button { display: none; }
    .chips { display: flex; gap: 4px; max-width: 45%; overflow: hidden; }
    .chips .chip { height: 24px; }
    .clear { display: inline-flex; width: 24px; height: 24px; align-items: center; justify-content: center; border-radius: 50%; color: var(--text-2); &:hover { background: var(--surface-2); color: var(--text); } }
    .stepper { display: flex; align-items: center; height: 32px; padding: 0 1px; border-radius: var(--radius-s); border: 1px solid var(--border); background: var(--surface); }
    .stepper .btn { width: 24px; }
    .val { display: inline-flex; min-width: 18px; justify-content: center; font-size: 12.5px; font-weight: 600; font-variant-numeric: tabular-nums; }
    @media (max-width: 1100px) { .brand .name, .chips { display: none; } }
    @media (max-width: 760px) { :host { flex-wrap: wrap; height: auto; padding: 8px 10px; } .search { order: 10; flex-basis: 100%; } .stepper { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Topbar {
  readonly settings = output<void>();
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  private readonly catalog = inject(CatalogStore);
  private readonly actions = inject(Actions);
  private readonly menu = inject(ContextMenu);
  private readonly picker = inject(EntityPicker);
  protected readonly focused = signal(false);
  /** The Catalog menu is the one the service shows (its entries are this instance's). */
  private catalogItems: MenuEntry[] | null = null;
  protected readonly catalogOpen = computed(() => { const s = this.menu.state(); return !!s && s.items === this.catalogItems; });
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('q');

  /** Active structured criteria as removable chips; tags typed as `#tag` are removed from the text itself. */
  protected readonly chips = computed<Chip[]>(() => {
    const q = this.store.query(), f = this.store.filters(), s = this.store;
    const out: Chip[] = [];
    const strip = (re: RegExp) => s.text.update(t => t.replace(re, ' ').replace(/\s+/g, ' ').trim());
    for (const t of q.tags ?? []) out.push({ key: 't:' + t, label: '#' + t, remove: () => { if (f.tags?.includes(t)) s.toggleTag(t); strip(new RegExp(tagPattern(t), 'ig')); } });
    const facet = (key: 'model' | 'studio' | 'photographer', prefix: string, keys: string) => {
      if (q[key]) out.push({ key, label: `${prefix} ${q[key]}`, remove: () => { s.setFilter(key, null); strip(new RegExp(`\\b(${keys}):("[^"]*"|\\S+)`, 'ig')); } });
    };
    facet('model', 'model:', 'model|m'); facet('studio', 'studio:', 'studio|s'); facet('photographer', 'by:', 'by|photographer|p');
    if (q.kind) out.push({ key: 'kind', label: q.kind === 'ZIP' ? 'zips only' : q.kind === 'ARCHIVE' ? 'archives' : 'folders', remove: () => { s.setFilter('kind', null); strip(/\bkind:\S+/ig); } });
    if (q.roots?.length) out.push({ key: 'roots', label: 'in: ' + q.roots[0].split('/').filter(Boolean).slice(-2).join('/'), icon: 'folder', remove: () => s.goTo(null) });
    if (q.minBytes != null || q.maxBytes != null) out.push({ key: 'bytes', label: `size ${q.minBytes != null ? formatBytes(q.minBytes, 0) : '0'} – ${q.maxBytes != null ? formatBytes(q.maxBytes, 0) : '∞'}`, remove: () => { s.setFilter('minBytes', null); s.setFilter('maxBytes', null); } });
    if (q.minCount != null || q.maxCount != null) out.push({ key: 'count', label: `${q.minCount ?? 0} – ${q.maxCount ?? '∞'} photos`, remove: () => { s.setFilter('minCount', null); s.setFilter('maxCount', null); } });
    if (q.minMegapixels != null || q.maxMegapixels != null) out.push({ key: 'mp', label: `${q.minMegapixels != null ? formatMegapixels(q.minMegapixels * 1e6) : '0'} – ${q.maxMegapixels != null ? formatMegapixels(q.maxMegapixels * 1e6) : '∞'}`, remove: () => { s.setFilter('minMegapixels', null); s.setFilter('maxMegapixels', null); } });
    if (q.dateFrom || q.dateTo) out.push({ key: 'date', label: `${q.dateFrom ?? '…'} → ${q.dateTo ?? '…'}`, remove: () => { s.setFilter('dateFrom', null); s.setFilter('dateTo', null); } });
    if (q.minRating != null || q.maxRating != null) out.push({ key: 'rating', label: `★ ${q.minRating ?? 0}–${q.maxRating ?? 10}`, remove: () => { s.setFilter('minRating', null); s.setFilter('maxRating', null); } });
    // catalog scopes (the tree, the cards, the Views strip): ids named through `CatalogStore.known`
    for (const id of q.entities ?? []) {
      const e = this.catalog.entityOf(id), [prefix, icon] = SCOPE[e?.kind ?? 'MODEL'];
      out.push({ key: 'e:' + id, label: e ? (e.kind === 'TAG' ? '#' + e.name : `${prefix} ${e.name}`) : '…', icon, remove: () => s.setFilter('entities', (f.entities ?? []).filter(x => x !== id)) });
    }
    if (q.missing) out.push({ key: 'missing', label: `unassigned ${NOUN[q.missing]}`, icon: 'alert', remove: () => s.setFilter('missing', null) });
    if (q.hidden) out.push({ key: 'hidden', label: 'hidden', icon: 'eyeOff', remove: () => s.setFilter('hidden', null) });
    if (q.duplicates) out.push({ key: 'duplicates', label: 'duplicates', icon: 'layers', remove: () => s.setFilter('duplicates', null) });
    for (const g of q.genres ?? []) out.push({ key: 'g:' + g, label: `genre: ${g}`, icon: 'sparkles', remove: () => s.setFilter('genres', (f.genres ?? []).filter(x => x !== g)) });
    return out;
  });

  constructor() {
    // a scope set by id alone (after a reload of the tree in another mode) still gets its name, refreshed on every catalog change (a rename elsewhere); a deleted or merged-away id leaves the scope
    effect(() => {
      const ids = this.store.filters().entities ?? [], v = this.catalog.version();
      untracked(() => void this.catalog.ensureKnown(ids, v > 0).then(gone => { if (gone.length) this.store.setFilter('entities', (this.store.filters().entities ?? []).filter(id => !gone.includes(id))); }));
    });
  }

  focusSearch() { this.input().nativeElement.focus(); this.input().nativeElement.select(); }

  protected onEscape(input: HTMLInputElement, e: Event) {
    if (input.value) { this.store.text.set(''); e.stopPropagation(); } else input.blur();
  }

  /** Catalog ▾: pick a kind, then an existing entity (searchable, recents first) or "Create …" in the picker; the card opens in the details panel. */
  protected openCatalogMenu(e: MouseEvent) {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    this.catalogItems = CATALOG.map(kind => ({ label: `${KIND_LABEL[kind]}…`, icon: KIND_ICON[kind], run: () => void this.editEntity(kind) }));
    this.menu.open(r.left, r.bottom + 6, this.catalogItems);
  }

  private async editEntity(kind: EntityKind) {
    const e = await this.picker.pick({ kind, title: `Open or create a ${NOUN[kind]}`, allowCreate: true });
    if (e) this.actions.openCard(e.id);
  }
}
