import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, signal, untracked } from '@angular/core';
import { Gateway } from '../core/gateway';
import { Folder } from '../core/model';
import { lastSegment } from '../core/paths';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Icon, IconName } from './shared/icon';

/** One visible line of the tree; `path` null = the Library row. */
interface Row { key: string; path: string | null; label: string; title: string; count: number; depth: number; icon: IconName; nested: boolean; open: boolean; reachable: boolean }

const INDENT = 14;
const prefix = (dir: string) => dir.endsWith('/') ? dir : dir + '/';

/** Explorer-like tree over the stored album paths: Library › roots › folders, loaded lazily per expanded node. */
@Component({
  selector: 'app-folder-tree',
  imports: [Icon, DecimalPipe],
  host: { tabindex: '0', '(keydown)': 'onKey($event)' },
  template: `
    @for (r of rows(); track r.key) {
      <div class="row" [class.selected]="r.path === store.folder()" [class.dim]="!r.reachable" [style.padding-left.px]="6 + indent * r.depth" [title]="r.title"
           (click)="store.goTo(r.path)" (dblclick)="r.nested && toggle(r.path!)">
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
    :host { display: block; padding: 6px 0 20px; outline: none; user-select: none; }
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
  protected readonly store = inject(AlbumStore);
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  protected readonly indent = INDENT;

  private readonly children = signal(new Map<string, Folder[]>());
  private readonly expanded = signal(new Set<string>());
  protected readonly loading = signal(new Set<string>());
  private readonly seenRoots = new Set<string>();
  private rootsKey = '';
  /** Bumped when the tree is cleared, so a listing requested before is dropped. */
  private epoch = 0;

  protected readonly rows = computed<Row[]>(() => {
    const roots = this.store.roots(), kids = this.children(), open = this.expanded();
    const out: Row[] = [{ key: '', path: null, label: 'Library', title: 'All indexed folders', count: roots.reduce((n, r) => n + r.albumCount, 0), depth: 0, icon: 'home', nested: false, open: false, reachable: true }];
    const walk = (path: string, label: string, count: number, depth: number, nested: boolean, reachable: boolean) => {
      const isOpen = nested && open.has(path), list = kids.get(path);
      out.push({ key: path, path, label, title: reachable ? path : path + ' — not reachable', count, depth, icon: isOpen ? 'folderOpen' : 'folder', nested: nested && (list?.length !== 0), open: isOpen, reachable });
      if (isOpen) for (const f of list ?? []) walk(f.path, f.name, f.albums, depth + 1, f.nested, true);
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
