import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, output, signal, untracked, viewChild } from '@angular/core';
import { CatalogStore } from '../core/catalog-store';
import { EntityKind } from '../core/model';
import { formatBytes, formatMegapixels } from '../core/format';
import { Prefs, SidebarTab } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { Icon, IconName } from './shared/icon';
import { SortMenu } from './sort-menu';

interface Chip { key: string; label: string; icon?: IconName; remove: () => void }
/** Scope chip prefix and icon per entity kind (`entities` filter); tags read `#name`. */
const SCOPE: Record<EntityKind, [string, IconName]> = { MODEL: ['model:', 'user'], STUDIO: ['studio:', 'building'], PHOTOGRAPHER: ['by:', 'camera'], GENRE: ['genre:', 'sparkles'], TAG: ['#', 'hash'], LIST: ['list:', 'heart'] };
const NOUN: Record<EntityKind, string> = { MODEL: 'model', STUDIO: 'studio', PHOTOGRAPHER: 'photographer', GENRE: 'genre', TAG: 'tag', LIST: 'list' };

@Component({
  selector: 'app-topbar',
  imports: [Icon, SortMenu],
  template: `
    <button class="brand" title="Thumbnailer — settings" (click)="settings.emit()"><span class="logo"><app-icon name="images" [size]="18" /></span><span class="name">Thumbnailer</span></button>

    <button class="btn icon-btn" [class.active]="prefs.sidebar() === 'folders'" title="Folders (E)" (click)="toggleTab('folders')"><app-icon name="folder" /></button>
    <button class="btn icon-btn" [class.active]="prefs.sidebar() === 'refine'" title="Refine (F)" (click)="toggleTab('refine')">
      <app-icon name="filter" />
      @if (store.refining()) { <span class="count">{{ store.refining() }}</span> }
    </button>

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
      <button class="btn icon-btn sm ghost" (click)="prefs.setColumns(prefs.columns() - 1)" [disabled]="prefs.columns() <= 1"><app-icon name="minus" [size]="14" /></button>
      <span class="val"><app-icon name="columns" [size]="14" />{{ prefs.columns() }}</span>
      <button class="btn icon-btn sm ghost" (click)="prefs.setColumns(prefs.columns() + 1)" [disabled]="prefs.columns() >= 12"><app-icon name="plus" [size]="14" /></button>
    </div>

    <div class="segmented">
      <button [class.active]="prefs.layoutMode() === 'bricks'" (click)="prefs.layoutMode.set('bricks')" title="Bricks: portrait covers grouped in their own rows"><app-icon name="bricks" [size]="15" /></button>
      <button [class.active]="prefs.layoutMode() === 'grid'" (click)="prefs.layoutMode.set('grid')" title="Grid: strict order, one tile per cell"><app-icon name="grid" [size]="15" /></button>
    </div>

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
    .stepper { display: flex; align-items: center; gap: 2px; height: 32px; padding: 0 2px; border-radius: var(--radius-s); border: 1px solid var(--border); background: var(--surface); }
    .val { display: inline-flex; align-items: center; gap: 4px; min-width: 38px; justify-content: center; font-weight: 600; font-variant-numeric: tabular-nums; }
    @media (max-width: 1100px) { .brand .name, .chips { display: none; } }
    @media (max-width: 760px) { :host { flex-wrap: wrap; height: auto; padding: 8px 10px; } .search { order: 10; flex-basis: 100%; } .stepper, .segmented { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Topbar {
  readonly settings = output<void>();
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  private readonly catalog = inject(CatalogStore);
  protected readonly focused = signal(false);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('q');

  /** Active structured criteria as removable chips; tags typed as `#tag` are removed from the text itself. */
  protected readonly chips = computed<Chip[]>(() => {
    const q = this.store.query(), f = this.store.filters(), s = this.store;
    const out: Chip[] = [];
    const strip = (re: RegExp) => s.text.update(t => t.replace(re, ' ').replace(/\s+/g, ' ').trim());
    for (const t of q.tags ?? []) out.push({ key: 't:' + t, label: '#' + t, remove: () => { if (f.tags?.includes(t)) s.toggleTag(t); strip(new RegExp(`#${t}\\b`, 'ig')); } });
    const facet = (key: 'model' | 'studio' | 'photographer', prefix: string, keys: string) => {
      if (q[key]) out.push({ key, label: `${prefix} ${q[key]}`, remove: () => { s.setFilter(key, null); strip(new RegExp(`\\b(${keys}):("[^"]*"|\\S+)`, 'ig')); } });
    };
    facet('model', 'model:', 'model|m'); facet('studio', 'studio:', 'studio|s'); facet('photographer', 'by:', 'by|photographer|p');
    if (q.kind) out.push({ key: 'kind', label: q.kind === 'ZIP' ? 'zip archives' : q.kind === 'ARCHIVE' ? 'archives' : 'folders', remove: () => { s.setFilter('kind', null); strip(/\bkind:\S+/ig); } });
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
    // a scope set by id alone (after a reload of the tree in another mode) still gets its name
    effect(() => { const ids = this.store.filters().entities ?? []; untracked(() => this.catalog.ensureKnown(ids)); });
  }

  focusSearch() { this.input().nativeElement.focus(); this.input().nativeElement.select(); }

  protected onEscape(input: HTMLInputElement, e: Event) {
    if (input.value) { this.store.text.set(''); e.stopPropagation(); } else input.blur();
  }

  /** Opens the tab, or closes the sidebar when that tab is already showing. */
  protected toggleTab(tab: SidebarTab) { this.prefs.sidebar.update(v => v === tab ? 'none' : tab); }
}
