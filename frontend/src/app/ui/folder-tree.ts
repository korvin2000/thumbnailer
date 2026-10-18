import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, output, signal, untracked } from '@angular/core';
import { Gateway } from '../core/gateway';
import { Folder } from '../core/model';
import { lastSegment } from '../core/paths';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Icon, IconName } from './shared/icon';

/** A right-click on a row: its folder (null = the Library row) and the pointer. */
export interface FolderMenuEvent { path: string | null; x: number; y: number }

/** One visible line of the tree; `path` null = the Library row. */
interface Row { key: string; path: string | null; label: string; title: string; count: number; depth: number; icon: IconName; nested: boolean; open: boolean; reachable: boolean }

const INDENT = 14, EXPAND_CAP = 300;
const prefix = (dir: string) => dir.endsWith('/') ? dir : dir + '/';

/** Explorer-like tree over the stored album paths: Library › roots › folders, loaded lazily per expanded node. */
@Component({
  selector: 'app-folder-tree',
  imports: [Icon, DecimalPipe],
  host: { tabindex: '0', '(keydown)': 'onKey($event)' },
  template: `
    <div class="tools">
      <label class="filter">
        <app-icon name="search" [size]="13" />
        <input #q placeholder="Filter folders" autocomplete="off" spellcheck="false" [value]="filter()" (input)="filter.set(q.value)" (keydown.escape)="filter.set(''); $event.stopPropagation()" />
        @if (filter()) { <button class="clear" type="button" title="Clear filter" (click)="filter.set(''); q.focus()"><app-icon name="x" [size]="11" /></button> }
      </label>
      <button class="btn icon-btn sm ghost" type="button" title="Collapse all" (click)="collapseAll()"><app-icon name="collapseAll" [size]="15" /></button>
      <button class="btn icon-btn sm ghost" type="button" [title]="store.folder() === null ? 'Expand all folders' : 'Expand selected folder'" (click)="expandSelected()"><app-icon name="expandAll" [size]="15" /></button>
    </div>
    @for (r of rows(); track r.key) {
      <div class="row" [class.selected]="r.path === store.folder()" [class.dim]="!r.reachable" [style.padding-left.px]="6 + indent * r.depth" [title]="r.title"
           (click)="store.goTo(r.path)" (dblclick)="r.nested && toggle(r.path!)" (contextmenu)="onContext($event, r)">
        <span class="chev" [class.open]="r.open" [class.none]="!r.nested" (click)="r.nested && toggle(r.path!); $event.stopPropagation()" (dblclick)="$event.stopPropagation()">
          @if (r.path !== null && loading().has(r.path)) { <span class="spinner"></span> } @else if (r.nested) { <app-icon name="chevronRight" [size]="14" /> }
        </span>
        <app-icon [name]="r.icon" [size]="15" />
        <span class="name">{{ r.label }}</span>
        <span class="n">{{ r.count | number }}</span>
      </div>
    }
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
    .row { display: flex; align-items: center; gap: 5px; height: 28px; margin: 0 6px; padding-right: 10px; border-radius: var(--radius-s); color: var(--text-2); cursor: pointer; white-space: nowrap; }
    .row:hover { background: var(--surface-2); color: var(--text); }
    .row.selected { background: var(--accent-soft); color: var(--accent-strong); }
    .row.dim { opacity: 0.5; }
    .chev { display: inline-flex; flex: none; width: 18px; height: 18px; align-items: center; justify-content: center; border-radius: 4px; color: var(--text-3); transition: transform 0.15s var(--ease); }
    .chev:hover { color: var(--text); background: var(--surface-2); }
    .chev.open { transform: rotate(90deg); }
    .chev.none { pointer-events: none; }
    .row.selected .chev { color: inherit; }
    .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
    .n { flex: none; font-size: 11px; font-variant-numeric: tabular-nums; color: var(--text-3); }
    .row.selected .n { color: inherit; opacity: 0.75; }
    :host:focus-visible .row.selected { box-shadow: inset 0 0 0 1px var(--accent); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FolderTree {
  /** Right-click on a row (the row becomes the current folder first, as in Explorer); the menu is built by `Actions.openFolderMenu`. */
  readonly contextMenu = output<FolderMenuEvent>();

  protected readonly store = inject(AlbumStore);
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  protected readonly indent = INDENT;

  private readonly children = signal(new Map<string, Folder[]>());
  private readonly expanded = signal(new Set<string>());
  protected readonly filter = signal('');
  protected readonly loading = signal(new Set<string>());
  private readonly seenRoots = new Set<string>();
  private rootsKey = '';
  /** Bumped when the tree is cleared, so a listing requested before is dropped. */
  private epoch = 0;

  protected readonly rows = computed<Row[]>(() => {
    const roots = this.store.roots(), kids = this.children(), open = this.expanded(), q = this.filter().trim().toLowerCase();
    const out: Row[] = [{ key: '', path: null, label: 'Library', title: 'All indexed folders', count: roots.reduce((n, r) => n + r.albumCount, 0), depth: 0, icon: 'home', nested: false, open: false, reachable: true }];
    // filtering walks every loaded node regardless of `expanded` and keeps matches plus their ancestors; returns whether the subtree hit
    const walk = (path: string, label: string, count: number, depth: number, nested: boolean, reachable: boolean): boolean => {
      const list = kids.get(path), isOpen = q ? nested && !!list?.length : nested && open.has(path), at = out.length;
      let hit = !q || label.toLowerCase().includes(q);
      out.push({ key: path, path, label, title: reachable ? path : path + ' — not reachable', count, depth, icon: isOpen ? 'folderOpen' : 'folder', nested: nested && (list?.length !== 0), open: isOpen, reachable });
      if (isOpen) for (const f of list ?? []) hit = walk(f.path, f.name, f.albums, depth + 1, f.nested, true) || hit;
      if (!hit) out.length = at;
      return hit;
    };
    for (const r of roots) walk(r.path, lastSegment(r.path), r.albumCount, 1, r.exists, r.exists);
    return out;
  });

  constructor() {
    // another library or another set of roots starts from a clean tree; roots that appear open to their first level, reachable only
    effect(() => {
      const roots = this.store.roots(), key = [this.store.settings()?.dataDir ?? '', ...roots.map(r => r.path)].join('|');
      untracked(() => {
        if (key !== this.rootsKey) { this.rootsKey = key; this.epoch++; this.children.set(new Map()); this.expanded.set(new Set()); this.seenRoots.clear(); }
        for (const r of roots) if (r.exists && !this.seenRoots.has(r.path)) { this.seenRoots.add(r.path); this.expand(r.path); }
      });
    });
    effect(() => { const f = this.store.folder(); untracked(() => void this.reveal(f)); });
    // stored folders changed: reload what is open, forget what is collapsed so re-expanding fetches fresh children
    effect(() => {
      this.store.treeVersion();
      untracked(() => {
        const open = this.expanded(), reload = [...this.children().keys()].filter(p => open.has(p));
        this.children.update(m => new Map([...m].filter(([p]) => open.has(p))));
        for (const p of reload) void this.load(p);
      });
    });
  }

  protected toggle(path: string) {
    if (this.expanded().has(path)) this.expanded.update(s => { const n = new Set(s); n.delete(path); return n; });
    else this.expand(path);
  }

  private expand(path: string) {
    this.expanded.update(s => new Set(s).add(path));
    if (!this.children().has(path)) void this.load(path);
  }

  protected collapseAll() { this.expanded.set(new Set()); }

  protected onContext(e: MouseEvent, r: Row) {
    e.preventDefault();
    this.store.goTo(r.path);
    this.contextMenu.emit({ path: r.path, x: e.clientX, y: e.clientY });
  }

  /** Breadth-first loads and expands the selected folder's subtree (Library: every reachable root), at most EXPAND_CAP folders. */
  protected async expandSelected() {
    const epoch = this.epoch, from = this.store.folder();
    const queue = from === null ? this.store.roots().filter(r => r.exists).map(r => r.path) : [from];
    for (let n = 0; queue.length; n++) {
      if (n === EXPAND_CAP) { this.toasts.show(`Expanded the first ${EXPAND_CAP} folders`); break; }
      const p = queue.shift()!, list = this.children().get(p) ?? await this.load(p);
      if (epoch !== this.epoch) return;
      this.expanded.update(s => new Set(s).add(p));
      for (const f of list) if (f.nested) queue.push(f.path);
    }
    requestAnimationFrame(() => this.host.querySelector('.row.selected')?.scrollIntoView({ block: 'nearest' }));
  }

  private async load(path: string): Promise<Folder[]> {
    this.loading.update(s => new Set(s).add(path));
    const epoch = this.epoch;
    try {
      const list = await this.gw.folders(path);
      if (epoch === this.epoch) this.children.update(m => new Map(m).set(path, list));
      return list;
    } catch (e) { this.toasts.error(e, 'Cannot list folders: '); return []; }
    finally { this.loading.update(s => { const n = new Set(s); n.delete(path); return n; }); }
  }

  /** Opens every ancestor of the selected folder (loading as needed) and scrolls its row into view. */
  private async reveal(path: string | null) {
    const root = path && this.store.rootOf(path);
    if (root) {
      let p = root.path.replace(/\/+$/, '');
      const rest = path.slice(p.length).split('/').filter(Boolean);
      rest.pop();
      for (const seg of ['', ...rest]) {
        if (seg) p += '/' + seg;
        if (!this.children().has(p)) await this.load(p);
        this.expanded.update(s => new Set(s).add(p));
      }
    }
    requestAnimationFrame(() => this.host.querySelector('.row.selected')?.scrollIntoView({ block: 'nearest' }));
  }

  protected onKey(e: KeyboardEvent) {
    if (e.target instanceof HTMLInputElement) return;
    const rows = this.rows(), i = rows.findIndex(r => r.path === this.store.folder()), cur = rows[i];
    const go = (r?: Row) => { if (r) this.store.goTo(r.path); };
    switch (e.key) {
      case 'ArrowDown': go(rows[i + 1]); break;
      case 'ArrowUp': go(rows[i - 1]); break;
      case 'Home': go(rows[0]); break;
      case 'End': go(rows[rows.length - 1]); break;
      case 'ArrowRight': if (!cur?.nested) return; cur.open ? go(rows[i + 1]) : this.expand(cur.path!); break;
      case 'ArrowLeft': if (cur?.open) this.toggle(cur.path!); else go(rows.slice(0, i).reverse().find(r => r.depth < (cur?.depth ?? 0))); break;
      default: return;
    }
    e.preventDefault();
  }
}
