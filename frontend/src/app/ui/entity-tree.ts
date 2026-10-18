import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, input, output, signal, untracked } from '@angular/core';
import { CatalogStore } from '../core/catalog-store';
import { Gateway } from '../core/gateway';
import { Entity, EntityKind, EntityList, EntityQuery } from '../core/model';
import { Selection } from '../core/selection';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Confirm } from './shared/confirm';
import { EntityPicker } from './shared/entity-picker';
import { Icon, IconName } from './shared/icon';

/** A right-click on a row, for the menu builder (F4): the row's entity (null on All / Unassigned), the selected ids and the pointer. */
export interface EntityTreeContext { kind: EntityKind; entity: Entity | null; selected: number[]; x: number; y: number }

/** One visible line; `scope` = what a plain click narrows the grid to (entity ids: all must match, so a child row carries its parent too). */
interface Row { key: string; /** The selection id (the entity's id as text); empty on All / Unassigned. */ sid: string; entity: Entity | null; label: string; count: number | null; depth: number; icon: IconName; nested: boolean; open: boolean; scope: number[] | 'all' | 'missing' }

export const KIND_ICON: Record<EntityKind, IconName> = { MODEL: 'user', STUDIO: 'building', PHOTOGRAPHER: 'camera', GENRE: 'sparkles', TAG: 'hash', LIST: 'heart' };
export const KIND_LABEL: Record<EntityKind, string> = { MODEL: 'Models', STUDIO: 'Studios', PHOTOGRAPHER: 'Photographers', GENRE: 'Genres', TAG: 'Hashtags', LIST: 'Favorites' };
export const NOUN: Record<EntityKind, string> = { MODEL: 'model', STUDIO: 'studio', PHOTOGRAPHER: 'photographer', GENRE: 'genre', TAG: 'tag', LIST: 'list' };
/** The second level under a row (`entities {under}`): a studio's models, a model's studios…; lists have none. */
const UNDER: Partial<Record<EntityKind, EntityKind>> = { STUDIO: 'MODEL', MODEL: 'STUDIO', PHOTOGRAPHER: 'MODEL', GENRE: 'MODEL' };
const PAGE = 100, INDENT = 14, UNUSED_SCAN = 2000;
const EMPTY: EntityList = { items: [], total: 0 };
const same = (a: number[], b: number[]) => a.length === b.length && a.every(x => b.includes(x));

/** The Browse tree of one entity kind: All · Unassigned · entities (paged, filterable, sortable, multi-selectable) with a second level where the server answers `under`. */
@Component({
  selector: 'app-entity-tree',
  imports: [Icon, DecimalPipe],
  host: { tabindex: '0', '(keydown)': 'onKey($event)' },
  template: `
    <div class="tools">
      <label class="filter">
        <app-icon name="search" [size]="13" />
        <input #q [placeholder]="'Filter ' + label[kind()].toLowerCase()" autocomplete="off" spellcheck="false" [value]="filter()" (input)="filter.set(q.value)" (keydown.escape)="filter.set(''); $event.stopPropagation()" />
        @if (filter()) { <button class="clear" type="button" title="Clear filter" (click)="filter.set(''); q.focus()"><app-icon name="x" [size]="11" /></button> }
      </label>
      <button class="btn icon-btn sm ghost" type="button" [class.active]="byCount()" [title]="byCount() ? 'Sorted by album count — sort by name' : 'Sorted by name — sort by album count'" (click)="byCount.set(!byCount())"><app-icon [name]="byCount() ? 'sortDesc' : 'sortAsc'" [size]="15" /></button>
      @if (kind() !== 'LIST') { <button class="btn icon-btn sm ghost" type="button" [title]="'Delete unused ' + noun[kind()] + 's (unconfirmed, no albums)…'" (click)="deleteUnused()"><app-icon name="trash" [size]="14" /></button> }
    </div>
    @if (selection.size(); as n) {
      <div class="strip">
        <span class="n">{{ n }} selected</span>
        <button class="btn sm ghost" type="button" title="Mark as confirmed" (click)="confirmSelected()"><app-icon name="check" [size]="13" /> Confirm</button>
        <button class="btn sm ghost" type="button" title="Merge the selected rows into another one" (click)="mergeSelected()"><app-icon name="layers" [size]="13" /> Merge…</button>
        <button class="btn sm ghost danger" type="button" title="Delete the selected rows" (click)="deleteSelected()"><app-icon name="trash" [size]="13" /></button>
        <button class="btn icon-btn sm ghost" type="button" title="Deselect (Esc)" (click)="selection.clear()"><app-icon name="x" [size]="13" /></button>
      </div>
    }
    @for (r of rows(); track r.key) {
      <div class="row" [class.selected]="isScope(r)" [class.checked]="r.entity && selection.has(r.sid)" [class.auto]="r.entity?.auto"
           [style.padding-left.px]="6 + indent * r.depth" [title]="r.entity?.auto ? r.label + ' — from album text, not confirmed' : r.label"
           (click)="pick(r, $event)" (dblclick)="r.nested && toggle(r.entity!.id)" (contextmenu)="onContext($event, r)">
        <span class="chev" [class.open]="r.open" [class.none]="!r.nested" (click)="r.nested && toggle(r.entity!.id); $event.stopPropagation()" (dblclick)="$event.stopPropagation()">
          @if (r.entity && loadingIds().has(r.entity.id)) { <span class="spinner"></span> } @else if (r.nested) { <app-icon name="chevronRight" [size]="14" /> }
        </span>
        <app-icon [name]="r.icon" [size]="15" />
        <span class="name">{{ r.label }}</span>
        @if (r.entity?.auto) { <span class="dot"></span> }
        <span class="n">@if (r.count === null) { … } @else { {{ r.count | number }} }</span>
      </div>
    }
    @if (loading()) { <div class="state"><span class="spinner"></span> Loading…</div> }
    @else if (!rows().length) { <div class="state">Nothing matches</div> }
    @else if (more() > 0) { <button class="more" type="button" (click)="loadMore()">{{ more() | number }} more…</button> }
  `,
  styles: `
    :host { display: block; padding: 0 0 20px; outline: none; user-select: none; }
    .tools { position: sticky; top: 0; z-index: 1; display: flex; gap: 4px; align-items: center; padding: 6px 8px 4px 10px; background: var(--bg-elev); }
    .filter { flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; height: 26px; padding: 0 3px 0 8px; border: 1px solid var(--border); border-radius: var(--radius-s); background: var(--bg); color: var(--text-3); cursor: text; transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease); }
    .filter:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .filter input { flex: 1; min-width: 0; height: 100%; padding: 0; border: 0; background: transparent; color: var(--text); font: inherit; font-size: 12px; outline: none; }
    .filter input::placeholder { color: var(--text-3); }
    .clear { display: inline-flex; flex: none; width: 18px; height: 18px; align-items: center; justify-content: center; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--text-3); cursor: pointer; }
    .clear:hover { color: var(--text); background: var(--surface-2); }
    .strip { position: sticky; top: 36px; z-index: 1; display: flex; align-items: center; gap: 2px; margin: 0 8px 4px; padding: 3px 4px 3px 8px; border-radius: var(--radius-s); background: var(--accent-soft); color: var(--accent-strong); font-size: 12px; animation: fade-up 0.15s var(--ease); }
    .strip .n { flex: 1; font-weight: 600; white-space: nowrap; }
    .strip .btn { color: inherit; padding: 0 6px; }
    .strip .danger:hover:not(:disabled) { color: var(--danger); }
    .row { display: flex; align-items: center; gap: 5px; height: 28px; margin: 0 6px; padding-right: 10px; border-radius: var(--radius-s); color: var(--text-2); cursor: pointer; white-space: nowrap; }
    .row:hover { background: var(--surface-2); color: var(--text); }
    .row.checked { background: var(--surface-2); box-shadow: inset 2px 0 0 var(--accent); color: var(--text); }
    .row.selected { background: var(--accent-soft); color: var(--accent-strong); }
    .row.auto .name { opacity: 0.7; }
    .chev { display: inline-flex; flex: none; width: 18px; height: 18px; align-items: center; justify-content: center; border-radius: 4px; color: var(--text-3); transition: transform 0.15s var(--ease); }
    .chev:hover { color: var(--text); background: var(--surface-2); }
    .chev.open { transform: rotate(90deg); }
    .chev.none { pointer-events: none; }
    .row.selected .chev { color: inherit; }
    .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
    .dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--accent); opacity: 0.7; }
    .n { flex: none; font-size: 11px; font-variant-numeric: tabular-nums; color: var(--text-3); }
    .row.selected .n { color: inherit; opacity: 0.75; }
    .state { display: flex; align-items: center; gap: 8px; padding: 10px 16px; color: var(--text-3); font-size: 12px; }
    .more { display: block; margin: 4px 6px 0; padding: 5px 0 5px 42px; border-radius: var(--radius-s); color: var(--accent-strong); font-size: 12px; text-align: left; width: calc(100% - 12px); }
    .more:hover { background: var(--surface-2); }
    :host:focus-visible .row.selected { box-shadow: inset 0 0 0 1px var(--accent); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EntityTree {
  readonly kind = input.required<EntityKind>();
  /** Right-click on a row; nothing is bound until the menus land (F4). */
  readonly contextMenu = output<EntityTreeContext>();

  protected readonly store = inject(AlbumStore);
  protected readonly catalog = inject(CatalogStore);
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);
  private readonly picker = inject(EntityPicker);
  private readonly confirm = inject(Confirm);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  protected readonly indent = INDENT;
  protected readonly label = KIND_LABEL;
  protected readonly noun = NOUN;
  /** Selected rows (entity ids as strings) for the batch actions; a plain click also scopes the grid. */
  readonly selection = new Selection();

  protected readonly filter = signal('');
  protected readonly byCount = signal(false);
  protected readonly loading = signal(false);
  /** The unfiltered list (as many pages as were asked for) and, while the filter box names a prefix the server must search, that answer. */
  private readonly base = signal<EntityList>(EMPTY);
  private readonly found = signal<EntityList | null>(null);
  private readonly children = signal(new Map<number, Entity[]>());
  private readonly expanded = signal(new Set<number>());
  protected readonly loadingIds = signal(new Set<number>());
  /** The server does not answer `under` yet: no chevrons. */
  private readonly noUnder = signal(false);
  private readonly missingCount = signal<number | null>(null);
  private gen = 0;
  private searchTimer?: ReturnType<typeof setTimeout>;
  private loadedKind: EntityKind | null = null;
  private loadedByCount = false;

  /** A filter typed while the base list is paged is searched on the server (prefix); over a complete list it is a local substring match. */
  private readonly remote = computed(() => !!this.filter().trim() && this.base().total > this.base().items.length);
  private readonly list = computed(() => {
    const q = this.filter().trim().toLowerCase(), found = this.found();
    return found ? found.items : q ? this.base().items.filter(e => e.name.toLowerCase().includes(q)) : this.base().items;
  });
  protected readonly more = computed(() => { const f = this.found(); return f ? f.total - f.items.length : this.filter().trim() ? 0 : this.base().total - this.base().items.length; });

  protected readonly rows = computed<Row[]>(() => {
    const kind = this.kind(), q = this.filter().trim(), kids = this.children(), open = this.expanded(), nested = !this.noUnder() && !!UNDER[kind], child = UNDER[kind];
    const out: Row[] = [];
    if (!q) {
      out.push({ key: 'all', sid: '', entity: null, label: 'All', count: this.store.roots().reduce((n, r) => n + r.albumCount, 0), depth: 0, icon: 'home', nested: false, open: false, scope: 'all' });
      out.push({ key: 'missing', sid: '', entity: null, label: `Unassigned`, count: this.missingCount(), depth: 0, icon: 'alert', nested: false, open: false, scope: 'missing' });
    }
    for (const e of this.list()) {
      const isOpen = nested && open.has(e.id), list = kids.get(e.id);
      out.push({ key: String(e.id), sid: String(e.id), entity: e, label: e.name, count: e.albums, depth: q ? 0 : 1, icon: KIND_ICON[kind], nested: nested && list?.length !== 0, open: isOpen, scope: [e.id] });
      if (isOpen) for (const c of list ?? []) out.push({ key: e.id + '/' + c.id, sid: String(c.id), entity: c, label: c.name, count: c.albums, depth: (q ? 0 : 1) + 1, icon: KIND_ICON[child!], nested: false, open: false, scope: [e.id, c.id] });
    }
    return out;
  });

  constructor() {
    // the kind, the sort and every catalog or scan change reload the base list (keeping the pages already asked for), the children and the Unassigned count
    effect(() => {
      const kind = this.kind(), byCount = this.byCount();
      this.catalog.version(); this.store.treeVersion();
      untracked(() => {
        const fresh = kind !== this.loadedKind || byCount !== this.loadedByCount;
        if (fresh) { this.base.set(EMPTY); this.found.set(null); this.children.set(new Map()); this.expanded.set(new Set()); this.selection.clear(); this.noUnder.set(false); this.loadedKind = kind; this.loadedByCount = byCount; }
        void this.loadBase(fresh ? PAGE : Math.max(PAGE, this.base().items.length));
        for (const id of this.expanded()) void this.loadChildren(id);
        void this.countMissing();
        if (this.remote()) this.search(0);
      });
    });
    effect(() => { this.filter(); this.remote(); untracked(() => this.search(150)); });
  }

  // ---- loading ----

  private opts(extra: EntityQuery): EntityQuery { return { ...(this.byCount() ? { byCount: true } : {}), ...extra }; }

  private async loadBase(limit: number) {
    const gen = ++this.gen, kind = this.kind();
    this.loading.set(true);
    try { const p = await this.catalog.entities(kind, this.opts({ offset: 0, limit: Math.min(500, limit) })); if (gen === this.gen) this.base.set(p); }
    catch (e) { if (gen === this.gen) this.toasts.error(e, `Cannot list ${KIND_LABEL[kind].toLowerCase()}: `); }
    finally { if (gen === this.gen) this.loading.set(false); }
  }

  protected async loadMore() {
    const kind = this.kind(), found = this.found(), from = found ?? this.base(), gen = this.gen;
    try {
      const p = await this.catalog.entities(kind, this.opts({ ...(found ? { text: this.filter().trim() } : {}), offset: from.items.length, limit: PAGE }));
      if (gen !== this.gen) return;
      const merge = (l: EntityList) => ({ items: [...l.items, ...p.items.filter(e => !l.items.some(x => x.id === e.id))], total: p.total });
      if (found) this.found.update(f => f && merge(f)); else this.base.update(merge);
    } catch (e) { this.toasts.error(e, 'Loading failed: '); }
  }

  /** Server prefix search of the filter text; cleared when the box is empty or the whole list is here. */
  private search(wait: number) {
    clearTimeout(this.searchTimer);
    if (!this.remote()) { this.found.set(null); return; }
    const gen = this.gen, text = this.filter().trim(), kind = this.kind();
    this.searchTimer = setTimeout(async () => {
      try { const p = await this.catalog.entities(kind, this.opts({ text, offset: 0, limit: PAGE })); if (gen === this.gen && this.filter().trim() === text) this.found.set(p); }
      catch (e) { this.toasts.error(e, 'Search failed: '); }
    }, wait);
  }

  private async loadChildren(id: number) {
    const kind = UNDER[this.kind()], gen = this.gen;
    if (!kind) return;
    this.loadingIds.update(s => new Set(s).add(id));
    try { const p = await this.catalog.entities(kind, { under: id, byCount: true, limit: 500 }); if (gen === this.gen) this.children.update(m => new Map(m).set(id, p.items)); }
    catch (e) { // the server answers "not implemented" until the catalog track lands: the level disappears instead of failing on every click
      if (gen === this.gen && !this.noUnder()) { this.noUnder.set(true); this.expanded.set(new Set()); this.toasts.error(e, 'No second level: '); }
    } finally { this.loadingIds.update(s => { const n = new Set(s); n.delete(id); return n; }); }
  }

  private async countMissing() {
    const kind = this.kind(), gen = this.gen;
    try { const r = await this.gw.search({ missing: kind, sort: 'NEWEST', offset: 0, limit: 1 }); if (gen === this.gen) this.missingCount.set(r.total); }
    catch { if (gen === this.gen) this.missingCount.set(null); }
  }

  // ---- rows ----

  protected isScope(r: Row): boolean {
    const f = this.store.filters(), ids = f.entities ?? [];
    return r.scope === 'all' ? !ids.length && !f.missing : r.scope === 'missing' ? f.missing === this.kind() : !f.missing && same(r.scope, ids);
  }

  /** Plain click: this row scopes the grid and is the only selected row; Ctrl / Shift only change the selection. */
  protected pick(r: Row, e?: MouseEvent) {
    const mods = { ctrl: !!(e?.ctrlKey || e?.metaKey), shift: !!e?.shiftKey };
    if (r.entity) this.selection.click(r.sid, this.rows().filter(x => x.entity).map(x => x.sid), mods);
    else if (!mods.ctrl && !mods.shift) this.selection.clear();
    if (mods.ctrl || mods.shift) return;
    this.store.setFilter('missing', r.scope === 'missing' ? this.kind() : null);
    this.store.setFilter('entities', Array.isArray(r.scope) ? r.scope : null);
  }

  protected toggle(id: number) {
    if (this.expanded().has(id)) this.expanded.update(s => { const n = new Set(s); n.delete(id); return n; });
    else { this.expanded.update(s => new Set(s).add(id)); if (!this.children().has(id)) void this.loadChildren(id); }
  }

  protected onContext(e: MouseEvent, r: Row) {
    e.preventDefault();
    if (r.entity && !this.selection.has(r.sid)) this.selection.set([r.sid]);
    this.contextMenu.emit({ kind: this.kind(), entity: r.entity, selected: this.selectedIds(), x: e.clientX, y: e.clientY });
  }

  // ---- batch actions on the selection ----

  private selectedIds() { return [...this.selection.ids()].map(Number); }
  private nameOf(id: number) { return this.catalog.entityOf(id)?.name ?? `#${id}`; }
  private plural(n: number) { return `${n} ${NOUN[this.kind()]}${n === 1 ? '' : 's'}`; }

  protected async confirmSelected() {
    const ids = this.selectedIds();
    if (ids.length && await this.catalog.confirm(ids)) { this.toasts.show(`${this.plural(ids.length)} confirmed`, 'success'); this.selection.clear(); }
  }

  protected async mergeSelected() {
    const ids = this.selectedIds();
    if (!ids.length) return;
    const into = await this.picker.pick({ kind: this.kind(), title: `Merge ${this.plural(ids.length)} into…` });
    const from = into ? ids.filter(id => id !== into.id) : [];
    if (into && from.length && await this.catalog.merge(from, into.id)) {
      this.toasts.show(`Merged into ${into.name}`, 'success');
      this.selection.clear();
      this.store.setFilter('entities', [into.id]);
    }
  }

  protected async deleteSelected() { await this.deleteAll(this.selectedIds(), 'Their albums lose this credit; album texts naming them are cleared.'); }

  /** Unconfirmed entities of the kind without albums, found over the first pages. */
  protected async deleteUnused() {
    const kind = this.kind(), all: Entity[] = [];
    try { for (let total = 1; all.length < total && all.length < UNUSED_SCAN;) { const p = await this.catalog.entities(kind, { byCount: true, offset: all.length, limit: 500 }); all.push(...p.items); total = p.total; if (!p.items.length) break; } }
    catch (e) { this.toasts.error(e, 'Cannot list: '); return; }
    const unused = all.filter(e => e.auto && e.albums === 0);
    if (!unused.length) this.toasts.show(`No unused ${NOUN[kind]}s`);
    else await this.deleteAll(unused.map(e => e.id), 'Unconfirmed and linked to no album.');
  }

  private async deleteAll(ids: number[], message: string) {
    if (!ids.length) return;
    const ok = await this.confirm.ask({ title: `Delete ${this.plural(ids.length)}?`, message, items: ids.map(id => this.nameOf(id)), showAll: ids.length <= 60, danger: true, okLabel: 'Delete' });
    if (ok && await this.catalog.delete(ids)) {
      this.toasts.show(`${this.plural(ids.length)} deleted`, 'success');
      this.selection.clear();
      const f = this.store.filters().entities;
      if (f?.some(id => ids.includes(id))) this.store.setFilter('entities', null);
    }
  }

  // ---- keyboard: like the folder tree, plus Esc (deselect), Ctrl+A (select every visible entity row), Delete ----

  protected onKey(e: KeyboardEvent) {
    if (e.target instanceof HTMLInputElement) return;
    const rows = this.rows(), i = rows.findIndex(r => this.isScope(r)), cur = rows[i];
    const go = (r?: Row) => { if (r) { this.pick(r); requestAnimationFrame(() => this.host.querySelector('.row.selected')?.scrollIntoView({ block: 'nearest' })); } };
    if ((e.ctrlKey || e.metaKey) && (e.key === 'a' || e.key === 'A')) { this.selection.selectAll(rows.filter(r => r.entity).map(r => r.sid)); e.preventDefault(); return; }
    switch (e.key) {
      case 'ArrowDown': go(rows[i + 1]); break;
      case 'ArrowUp': go(rows[i - 1]); break;
      case 'Home': go(rows[0]); break;
      case 'End': go(rows[rows.length - 1]); break;
      case 'ArrowRight': if (!cur?.nested) return; cur.open ? go(rows[i + 1]) : this.toggle(cur.entity!.id); break;
      case 'ArrowLeft': if (cur?.open) this.toggle(cur.entity!.id); else go(rows.slice(0, i).reverse().find(r => r.depth < (cur?.depth ?? 0))); break;
      case 'Escape': if (!this.selection.size()) return; this.selection.clear(); break;
      case 'Delete': if (!this.selection.size()) return; void this.deleteSelected(); break;
      default: return;
    }
    e.preventDefault();
  }
}
