import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { formatBytes, formatMegapixels } from '../core/format';
import { Prefs, Theme } from '../core/prefs';
import { SORTS } from '../core/model';
import { AlbumStore } from '../core/store';
import { Icon, IconName } from './shared/icon';

interface Chip { key: string; label: string; remove: () => void }

const THEMES: Theme[] = ['system', 'light', 'dark'];
const THEME_ICON: Record<Theme, IconName> = { system: 'monitor', light: 'sun', dark: 'moon' };

@Component({
  selector: 'app-topbar',
  imports: [Icon],
  template: `
    <div class="brand" title="Thumbnailer"><span class="logo"><app-icon name="images" [size]="18" /></span><span class="name">Thumbnailer</span></div>

    <button class="btn icon-btn" [class.active]="prefs.filtersOpen()" title="Refine search (F)" (click)="prefs.filtersOpen.set(!prefs.filtersOpen())">
      <app-icon name="filter" />
      @if (store.activeFilters()) { <span class="count">{{ store.activeFilters() }}</span> }
    </button>

    <div class="search" [class.focused]="focused()">
      <app-icon name="search" [size]="16" />
      <input #q type="search" [value]="store.text()" (input)="store.text.set(q.value)" (focus)="focused.set(true)" (blur)="focused.set(false)"
             (keydown.escape)="onEscape(q, $event)" placeholder="Search albums · #tag · model: · studio: · by: · kind:zip" autocomplete="off" spellcheck="false" />
      <div class="chips">
        @for (c of chips(); track c.key) {
          <button class="chip active" (click)="c.remove()" [title]="'Remove ' + c.label"><span>{{ c.label }}</span><app-icon name="x" [size]="11" /></button>
        }
      </div>
      @if (store.loading()) { <span class="spinner"></span> }
      @if (store.text() || store.activeFilters()) {
        <button class="clear" title="Clear search and filters" (click)="store.clearFilters()"><app-icon name="x" [size]="14" /></button>
      }
    </div>

    <select class="input sort" [value]="store.sort()" (change)="store.sort.set($any($event.target).value)" title="Sort">
      @for (s of sorts; track s.value) { <option [value]="s.value">{{ s.label }}</option> }
    </select>

    <div class="stepper" title="Albums per row ( [ and ] )">
      <button class="btn icon-btn sm ghost" (click)="prefs.setColumns(prefs.columns() - 1)" [disabled]="prefs.columns() <= 1"><app-icon name="minus" [size]="14" /></button>
      <span class="val"><app-icon name="columns" [size]="14" />{{ prefs.columns() }}</span>
      <button class="btn icon-btn sm ghost" (click)="prefs.setColumns(prefs.columns() + 1)" [disabled]="prefs.columns() >= 12"><app-icon name="plus" [size]="14" /></button>
    </div>

    <div class="segmented">
      <button [class.active]="prefs.layoutMode() === 'bricks'" (click)="prefs.layoutMode.set('bricks')" title="Bricks: portrait covers grouped in their own rows"><app-icon name="bricks" [size]="15" /></button>
      <button [class.active]="prefs.layoutMode() === 'grid'" (click)="prefs.layoutMode.set('grid')" title="Grid: strict order, one tile per cell"><app-icon name="grid" [size]="15" /></button>
    </div>

    <button class="btn icon-btn" [title]="'Theme: ' + prefs.theme() + ' (T)'" (click)="cycleTheme()"><app-icon [name]="themeIcon()" /></button>
    <button class="btn icon-btn" [class.active]="prefs.detailsOpen()" title="Details panel (I)" (click)="prefs.detailsOpen.set(!prefs.detailsOpen())"><app-icon name="panelRight" /></button>
    <button class="btn icon-btn" title="Settings" (click)="settings.emit()"><app-icon name="sliders" /></button>
  `,
  styles: `
    :host { display: flex; align-items: center; gap: 8px; height: var(--topbar-h); padding: 0 12px; background: var(--bg-elev); border-bottom: 1px solid var(--border); }
    .brand { display: flex; align-items: center; gap: 8px; margin-right: 4px; font-weight: 700; letter-spacing: -0.01em; }
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
    .sort { width: 160px; flex: none; }
    .stepper { display: flex; align-items: center; gap: 2px; height: 32px; padding: 0 2px; border-radius: var(--radius-s); border: 1px solid var(--border); background: var(--surface); }
    .val { display: inline-flex; align-items: center; gap: 4px; min-width: 38px; justify-content: center; font-weight: 600; font-variant-numeric: tabular-nums; }
    @media (max-width: 1100px) { .brand .name, .chips { display: none; } .sort { width: 130px; } }
    @media (max-width: 760px) { :host { flex-wrap: wrap; height: auto; padding: 8px 10px; } .search { order: 10; flex-basis: 100%; } .stepper, .segmented { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Topbar {
  readonly settings = output<void>();
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  protected readonly sorts = SORTS;
  protected readonly focused = signal(false);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('q');

  protected readonly themeIcon = computed(() => THEME_ICON[this.prefs.theme()]);

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
    if (q.kind) out.push({ key: 'kind', label: q.kind === 'ZIP' ? 'zip archives' : 'folders', remove: () => { s.setFilter('kind', null); strip(/\bkind:\S+/ig); } });
    if (q.roots?.length) out.push({ key: 'roots', label: `${q.roots.length} root${q.roots.length > 1 ? 's' : ''}`, remove: () => s.setFilter('roots', null) });
    if (q.minBytes != null || q.maxBytes != null) out.push({ key: 'bytes', label: `size ${q.minBytes != null ? formatBytes(q.minBytes, 0) : '0'} – ${q.maxBytes != null ? formatBytes(q.maxBytes, 0) : '∞'}`, remove: () => { s.setFilter('minBytes', null); s.setFilter('maxBytes', null); } });
    if (q.minCount != null || q.maxCount != null) out.push({ key: 'count', label: `${q.minCount ?? 0} – ${q.maxCount ?? '∞'} photos`, remove: () => { s.setFilter('minCount', null); s.setFilter('maxCount', null); } });
    if (q.minMegapixels != null || q.maxMegapixels != null) out.push({ key: 'mp', label: `${q.minMegapixels != null ? formatMegapixels(q.minMegapixels * 1e6) : '0'} – ${q.maxMegapixels != null ? formatMegapixels(q.maxMegapixels * 1e6) : '∞'}`, remove: () => { s.setFilter('minMegapixels', null); s.setFilter('maxMegapixels', null); } });
    if (q.dateFrom || q.dateTo) out.push({ key: 'date', label: `${q.dateFrom ?? '…'} → ${q.dateTo ?? '…'}`, remove: () => { s.setFilter('dateFrom', null); s.setFilter('dateTo', null); } });
    if (q.minRating != null || q.maxRating != null) out.push({ key: 'rating', label: `★ ${q.minRating ?? 0}–${q.maxRating ?? 10}`, remove: () => { s.setFilter('minRating', null); s.setFilter('maxRating', null); } });
    return out;
  });

  focusSearch() { this.input().nativeElement.focus(); this.input().nativeElement.select(); }

  protected onEscape(input: HTMLInputElement, e: Event) {
    if (input.value) { this.store.text.set(''); e.stopPropagation(); } else input.blur();
  }

  protected cycleTheme() { this.prefs.theme.set(THEMES[(THEMES.indexOf(this.prefs.theme()) + 1) % THEMES.length]); }
}
