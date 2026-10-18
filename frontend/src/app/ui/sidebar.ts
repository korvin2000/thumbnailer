import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { CatalogStore } from '../core/catalog-store';
import { Gateway } from '../core/gateway';
import { EntityKind } from '../core/model';
import { GroupBy, Prefs } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { EntityTree, KIND_ICON, KIND_LABEL } from './entity-tree';
import { FiltersPanel } from './filters-panel';
import { FolderTree } from './folder-tree';
import { ContextMenu } from './shared/context-menu';
import { Icon, IconName } from './shared/icon';

/** The Browse tab's group-by modes: Folders = the folder tree, the rest = an entity tree of that kind. */
const GROUPS: { key: GroupBy; label: string; icon: IconName; kind?: EntityKind }[] = [
  { key: 'folders', label: 'Folders', icon: 'folder' },
  { key: 'studio', label: KIND_LABEL.STUDIO, icon: KIND_ICON.STUDIO, kind: 'STUDIO' },
  { key: 'model', label: KIND_LABEL.MODEL, icon: KIND_ICON.MODEL, kind: 'MODEL' },
  { key: 'photographer', label: KIND_LABEL.PHOTOGRAPHER, icon: KIND_ICON.PHOTOGRAPHER, kind: 'PHOTOGRAPHER' },
  { key: 'genre', label: KIND_LABEL.GENRE, icon: KIND_ICON.GENRE, kind: 'GENRE' },
  { key: 'list', label: KIND_LABEL.LIST, icon: KIND_ICON.LIST, kind: 'LIST' },
];

/** Left panel with two tabs: Browse (folder tree or an entity tree, picked by `Prefs.groupBy`, over the fixed Views strip) and Refine (search criteria); `Prefs.sidebar` picks the tab. */
@Component({
  selector: 'app-sidebar',
  imports: [Icon, FolderTree, EntityTree, FiltersPanel, DecimalPipe],
  template: `
    <header>
      <div class="segmented">
        <button [class.active]="prefs.sidebar() === 'folders'" (click)="prefs.sidebar.set('folders')" title="Browse (E)"><app-icon [name]="group().icon" [size]="14" />Browse</button>
        <button [class.active]="prefs.sidebar() === 'refine'" (click)="prefs.sidebar.set('refine')" title="Refine (F)">
          <app-icon name="filter" [size]="14" />Refine @if (store.refining()) { <span class="badge">{{ store.refining() }}</span> }
        </button>
      </div>
      <span class="grow"></span>
      @if (prefs.sidebar() === 'refine') { <button class="btn ghost sm" (click)="store.resetRefine()" [disabled]="!store.refining() && !store.text()">Reset</button> }
      @else { <button class="btn sm ghost groupby" [title]="'Grouped by ' + group().label + ' — change'" (click)="openGroupMenu($event)"><app-icon name="layers" [size]="14" />{{ group().label }}<app-icon name="chevronDown" [size]="12" /></button> }
      <button class="btn icon-btn sm ghost" (click)="prefs.sidebar.set('none')" title="Close sidebar (Esc)"><app-icon name="x" /></button>
    </header>
    @if (prefs.sidebar() !== 'refine' && scope(); as s) {
      <div class="scope" [title]="s.title">
        <app-icon [name]="s.icon" [size]="13" /><span class="crumb">{{ s.group }}</span><span class="sep">›</span><span class="name">{{ s.name }}</span>
        <span class="n">{{ store.total() | number }}</span>
        <button class="btn icon-btn sm ghost" title="Show all albums" (click)="clearScope()"><app-icon name="x" [size]="12" /></button>
      </div>
    }
    <div class="body">
      @if (prefs.sidebar() === 'refine') { <app-filters-panel /> }
      @else if (group().kind; as kind) { <app-entity-tree [kind]="kind" /> }
      @else { <app-folder-tree /> }
    </div>
    @if (prefs.sidebar() !== 'refine') {
      <div class="views">
        <div class="line">
          <button class="view" [class.active]="store.filters().hidden" title="Hidden albums: safe-deleted, out of every other view" (click)="toggleView('hidden')">
            <app-icon name="eyeOff" [size]="14" /><span class="name">Hidden</span><span class="n">{{ counts().hidden ?? '…' }}</span>
          </button>
          @if (counts().hidden) { <button class="btn sm ghost unhide" title="Make every hidden album visible again" (click)="unhideAll()">Unhide all</button> }
        </div>
        <button class="view" [class.active]="store.filters().duplicates" title="Albums with the same photo names and sizes at other paths" (click)="toggleView('duplicates')">
          <app-icon name="layers" [size]="14" /><span class="name">Duplicates</span><span class="n">{{ counts().duplicates ?? '…' }}</span>
        </button>
      </div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; width: var(--filters-w); height: 100%; background: var(--bg-elev); border-right: 1px solid var(--border); }
    header { display: flex; flex: none; align-items: center; gap: 4px; height: 44px; padding: 0 8px 0 10px; border-bottom: 1px solid var(--border); }
    .grow { flex: 1; }
    .badge { min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: var(--accent); color: var(--on-accent); font-size: 10px; font-weight: 700; line-height: 16px; text-align: center; }
    .groupby { gap: 4px; padding: 0 5px 0 7px; font-weight: 500; }
    .scope { display: flex; flex: none; align-items: center; gap: 5px; height: 30px; padding: 0 4px 0 14px; border-bottom: 1px solid var(--border); background: var(--accent-soft); color: var(--accent-strong); font-size: 12px; white-space: nowrap; }
    .scope .crumb, .scope .sep { color: var(--text-2); }
    .scope .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; font-weight: 600; }
    .scope .n { font-size: 11px; font-variant-numeric: tabular-nums; opacity: 0.8; }
    .body { flex: 1; min-height: 0; overflow: auto; }
    .views { display: flex; flex-direction: column; gap: 2px; flex: none; padding: 6px 8px 8px; border-top: 1px solid var(--border); }
    .line { display: flex; align-items: center; gap: 4px; }
    .line .view { flex: 1; }
    .view { display: flex; align-items: center; gap: 7px; height: 28px; padding: 0 10px 0 12px; border-radius: var(--radius-s); color: var(--text-2); font-size: 12.5px; }
    .view:hover { background: var(--surface-2); color: var(--text); }
    .view.active { background: var(--accent-soft); color: var(--accent-strong); }
    .view .name { flex: 1; text-align: left; }
    .view .n { font-size: 11px; font-variant-numeric: tabular-nums; color: var(--text-3); }
    .view.active .n { color: inherit; opacity: 0.75; }
    .unhide { font-size: 11.5px; color: var(--text-3); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sidebar {
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  protected readonly catalog = inject(CatalogStore);
  private readonly gw = inject(Gateway);
  private readonly menu = inject(ContextMenu);
  private readonly toasts = inject(Toasts);

  protected readonly group = computed(() => GROUPS.find(g => g.key === this.prefs.groupBy()) ?? GROUPS[0]);
  /** Hidden and duplicate album counts, recounted after every catalog, scan or result change (two one-row searches). */
  protected readonly counts = signal<{ hidden: number | null; duplicates: number | null }>({ hidden: null, duplicates: null });
  /** The entity scope the grid is narrowed to (the location bar shows folders only): group › name, or the Unassigned row. */
  protected readonly scope = computed(() => {
    const f = this.store.filters(), ids = f.entities ?? [];
    if (f.missing) { const g = GROUPS.find(x => x.kind === f.missing)!; return { icon: 'alert' as IconName, group: g.label, name: 'Unassigned', title: `Albums without a ${g.label.slice(0, -1).toLowerCase()}` }; }
    if (!ids.length) return null;
    const names = ids.map(id => this.catalog.entityOf(id)), first = names[0], g = GROUPS.find(x => x.kind === first?.kind);
    const name = names.map(e => e?.name ?? '…').join(' › ');
    return { icon: g?.icon ?? 'layers', group: g?.label ?? 'Catalog', name, title: name };
  });
  private countTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    effect(() => {
      this.catalog.version(); this.store.treeVersion(); this.store.albums();
      untracked(() => { clearTimeout(this.countTimer); this.countTimer = setTimeout(() => void this.recount(), this.counts().hidden === null ? 0 : 700); });
    });
  }

  protected openGroupMenu(e: MouseEvent) {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    this.menu.open(r.left, r.bottom + 4, GROUPS.map(g => ({ label: g.label, icon: g.icon, checked: g.key === this.prefs.groupBy(), run: () => this.prefs.groupBy.set(g.key) })));
  }

  protected clearScope() { this.store.setFilter('entities', null); this.store.setFilter('missing', null); }

  protected toggleView(key: 'hidden' | 'duplicates') { this.store.setFilter(key, this.store.filters()[key] ? null : true); }

  protected async unhideAll() {
    const n = await this.catalog.bulkUpdate({ query: { hidden: true } }, { hidden: false });
    if (n !== null) { this.toasts.show(`${n} album${n === 1 ? '' : 's'} visible again`, 'success'); if (this.store.filters().hidden) this.store.setFilter('hidden', null); }
  }

  private async recount() {
    const count = async (f: 'hidden' | 'duplicates') => { try { return (await this.gw.search({ [f]: true, sort: 'NEWEST', offset: 0, limit: 1 })).total; } catch { return null; } };
    const [hidden, duplicates] = await Promise.all([count('hidden'), count('duplicates')]);
    this.counts.set({ hidden, duplicates });
  }
}
