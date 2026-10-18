import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, input, signal, untracked, viewChild } from '@angular/core';
import { Gateway } from '../core/gateway';
import { Folder } from '../core/model';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { lastSegment, normPath } from '../core/paths';
import { ContextMenu, MenuEntry } from './shared/context-menu';
import { Icon } from './shared/icon';

interface Crumb { path: string; label: string; title: string }

const POP_W = 240;

/** Slim bar above the grid: ↑ · Library › crumbs (each with a children popover) · editable path · selection chip · count · Rescan. */
@Component({
  selector: 'app-location-bar',
  imports: [Icon, DecimalPipe],
  host: { '(document:pointerdown)': 'onDocDown($event)' },
  template: `
    <button class="btn icon-btn sm ghost" title="Up to the parent folder" [disabled]="!store.folder()" (click)="go(parent())"><app-icon name="arrowUp" [size]="15" /></button>
    @if (editing()) {
      <input #path class="input sm mono" [value]="store.folder() ?? ''" placeholder="Folder path, Enter to go" spellcheck="false" autocomplete="off"
             (keydown.enter)="commit(path.value)" (keydown.escape)="editing.set(false); $event.stopPropagation()" (blur)="editing.set(false)" />
    } @else {
      <div #crumbsEl class="crumbs" [class.over]="overflowing()" (click)="onBarClick($event)">
        <div #trail class="trail">
          <span class="crumb">
            <button class="go" [class.current]="!crumbs().length" title="All indexed folders" (click)="go(null)"><app-icon name="home" [size]="14" /><span>Library</span></button>
            <button class="dd" [class.open]="menu() === null" title="Indexed folders" (click)="openMenu(null, $event)"><app-icon name="chevronDown" [size]="12" /></button>
          </span>
          @for (c of crumbs(); track c.path; let last = $last) {
            <span class="sep">›</span>
            <span class="crumb">
              <button class="go" [class.current]="last" [title]="c.title" (click)="go(c.path)"><span>{{ c.label }}</span></button>
              <button class="dd" [class.open]="menu() === c.path" title="Sub-folders" (click)="openMenu(c.path, $event)"><app-icon name="chevronDown" [size]="12" /></button>
            </span>
          }
        </div>
      </div>
      <span class="grow" (click)="edit()"></span>
    }
    @if (store.selection.size() >= 2) {
      <span class="chip active sel" title="Albums in the selection">
        <span>{{ store.selection.size() | number }} selected</span>
        @if (selectionMenu()) { <button class="dd-sel" title="Actions for the selection" (click)="openSelectionMenu($event)"><app-icon name="chevronDown" [size]="12" /></button> }
        <button title="Deselect all (Esc)" (click)="store.selection.clear()"><app-icon name="x" [size]="12" /></button>
      </span>
    }
    <span class="count">@if (store.loading()) { <span class="spinner"></span> }{{ store.total() | number }} albums</span>
    <button class="btn icon-btn sm ghost" [class.active]="editing()" title="Edit the path" (click)="edit()"><app-icon name="edit" [size]="14" /></button>
    @if (store.scan()?.running) {
      <button class="btn sm" title="Stop the running scan" (click)="store.cancelScan()"><span class="spinner"></span>Cancel</button>
    } @else {
      <button class="btn sm" [disabled]="!store.roots().length" [title]="store.folder() ? 'Rescan this folder' : 'Rescan all folders'" (click)="rescan()"><app-icon name="refresh" [size]="14" />Rescan</button>
    }
    @if (menu() !== undefined) {
      <div #pop class="pop panel" tabindex="-1" [style.left.px]="menuLeft()" (keydown.escape)="menu.set(undefined); $event.stopPropagation()">
        @if (menuItems(); as items) {
          @if (!items.length) { <div class="none">No sub-folders</div> }
          @for (f of items; track f.path) {
            <button class="item" [class.current]="f.path === menuCurrent()" [title]="f.path" (click)="go(f.path)"><app-icon name="folder" [size]="14" /><span class="name">{{ f.name }}</span><span class="n">{{ f.albums | number }}</span></button>
          }
        } @else { <div class="none"><span class="spinner"></span></div> }
      </div>
    }
  `,
  styles: `
    :host { position: relative; display: flex; align-items: center; gap: 4px; height: var(--locbar-h); padding: 0 10px 0 8px; background: var(--bg-elev); border-bottom: 1px solid var(--border); font-size: 12.5px; }
    .crumbs { display: flex; justify-content: flex-end; min-width: 0; height: 100%; overflow: hidden; cursor: text; }
    .crumbs.over { mask-image: linear-gradient(to right, transparent, #000 36px); -webkit-mask-image: linear-gradient(to right, transparent, #000 36px); }
    .trail { display: flex; flex: none; align-items: center; height: 100%; margin-right: auto; }
    .crumb { display: inline-flex; align-items: center; }
    .go { display: inline-flex; align-items: center; gap: 5px; height: 24px; max-width: 220px; padding: 0 6px; border-radius: 4px; color: var(--text-2); }
    .go > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .go:hover { background: var(--surface-2); color: var(--text); }
    .go.current { color: var(--text); font-weight: 600; }
    .dd { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 24px; margin-left: -3px; border-radius: 4px; color: var(--text-3); }
    .dd:hover, .dd.open { background: var(--surface-2); color: var(--text); }
    .sep { padding: 0 1px; color: var(--text-3); }
    .grow { flex: 1; align-self: stretch; cursor: text; }
    input.input { flex: 1; min-width: 120px; }
    .count { display: inline-flex; align-items: center; gap: 6px; margin: 0 4px; color: var(--text-3); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
    .sel { flex: none; gap: 2px; margin-left: 4px; padding-right: 4px; font-variant-numeric: tabular-nums; animation: fade-up 0.15s var(--ease); > span { padding-right: 4px; } button { height: 18px; padding: 0 2px; border-radius: 4px; &:hover { background: var(--accent-soft); } } }
    .pop { position: absolute; top: calc(100% - 2px); z-index: 40; min-width: 240px; max-width: 340px; max-height: 320px; overflow: auto; padding: 4px; border-radius: var(--radius); outline: none; animation: fade-up 0.15s var(--ease); }
    .item { display: flex; align-items: center; gap: 7px; width: 100%; height: 26px; padding: 0 8px; border-radius: var(--radius-s); color: var(--text-2); text-align: left; }
    .item:hover { background: var(--surface-2); color: var(--text); }
    .item.current { background: var(--accent-soft); color: var(--accent-strong); }
    .item .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .item .n { font-size: 11px; font-variant-numeric: tabular-nums; color: var(--text-3); }
    .item.current .n { color: inherit; opacity: 0.75; }
    .none { display: flex; align-items: center; justify-content: center; height: 32px; color: var(--text-3); font-size: 12px; }
    @media (max-width: 760px) { .count { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocationBar {
  /** Builds the selection chip's ▾ menu on every open (the chip has no ▾ without it). */
  readonly selectionMenu = input<(() => MenuEntry[]) | null>(null);

  protected readonly store = inject(AlbumStore);
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);
  private readonly contextMenu = inject(ContextMenu);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly pathInput = viewChild<ElementRef<HTMLInputElement>>('path');
  private readonly crumbsEl = viewChild<ElementRef<HTMLElement>>('crumbsEl');
  private readonly trailEl = viewChild<ElementRef<HTMLElement>>('trail');
  private readonly popEl = viewChild<ElementRef<HTMLElement>>('pop');

  protected readonly editing = signal(false);
  protected readonly overflowing = signal(false);
  /** Crumb whose children popover is open: null = Library, undefined = closed. */
  protected readonly menu = signal<string | null | undefined>(undefined);
  protected readonly menuItems = signal<Folder[] | null>(null);
  protected readonly menuLeft = signal(0);

  /** Root crumb (last path segment, full path in the title) followed by one crumb per segment below it. */
  protected readonly crumbs = computed<Crumb[]>(() => {
    const f = this.store.folder();
    if (!f) return [];
    const root = this.store.rootOf(f), base = (root?.path ?? f).replace(/\/+$/, '');
    const out: Crumb[] = [{ path: root?.path ?? f, label: lastSegment(base), title: base }];
    let p = base;
    for (const seg of f.slice(base.length).split('/').filter(Boolean)) { p += '/' + seg; out.push({ path: p, label: seg, title: p }); }
    return out;
  });
  protected readonly parent = computed(() => { const c = this.crumbs(); return c.length > 1 ? c[c.length - 2].path : null; });
  /** The crumb that follows the open menu's crumb, highlighted in the list as the branch we are in. */
  protected readonly menuCurrent = computed(() => { const m = this.menu(), c = this.crumbs(); return m === null ? c[0]?.path : c[c.findIndex(x => x.path === m) + 1]?.path; });

  constructor() {
    effect(() => { const el = this.pathInput()?.nativeElement; if (el) { el.focus(); el.select(); } });
    effect(() => { this.popEl()?.nativeElement.focus({ preventScroll: true }); });
    const ro = new ResizeObserver(() => this.measure());
    effect(onCleanup => {
      const els = [this.crumbsEl()?.nativeElement, this.trailEl()?.nativeElement].filter((e): e is HTMLElement => !!e);
      els.forEach(e => ro.observe(e));
      onCleanup(() => els.forEach(e => ro.unobserve(e)));
    });
    effect(() => { this.crumbs(); untracked(() => this.menu.set(undefined)); });
  }

  protected go(path: string | null) { this.menu.set(undefined); this.editing.set(false); this.store.goTo(path); }

  protected edit() { this.menu.set(undefined); this.editing.set(true); }

  protected onBarClick(e: Event) { if (!(e.target as Element).closest('button')) this.edit(); }

  /** Normalizes the typed path and goes there when it equals a root or lies below one (the root's stored case wins). */
  protected commit(raw: string) {
    const p = normPath(raw);
    if (!p) { this.go(null); return; }
    const root = this.store.rootOf(p);
    if (!root) { this.toasts.show('Not inside an indexed folder', 'error'); return; }
    const base = root.path.replace(/\/+$/, '');
    this.go(base + p.slice(base.length));
  }

  protected rescan() { const f = this.store.folder(); void this.store.startScan(f ? [f] : undefined); }

  /** The selection menu hangs below the chip. */
  protected openSelectionMenu(e: Event) {
    const r = (e.currentTarget as HTMLElement).closest('.chip')!.getBoundingClientRect();
    this.contextMenu.open(r.left, r.bottom + 4, this.selectionMenu()?.() ?? []);
  }

  protected async openMenu(path: string | null, e: Event) {
    if (this.menu() === path) { this.menu.set(undefined); return; }
    const crumb = (e.currentTarget as HTMLElement).closest('.crumb')!.getBoundingClientRect(), bar = this.host.getBoundingClientRect();
    this.menuLeft.set(Math.max(4, Math.min(crumb.left - bar.left, bar.width - POP_W - 4)));
    this.menuItems.set(null);
    this.menu.set(path);
    try {
      const items = path === null
        ? this.store.roots().map(r => ({ name: lastSegment(r.path), path: r.path, albums: r.albumCount, nested: r.exists }))
        : await this.gw.folders(path);
      if (this.menu() === path) this.menuItems.set(items);
    } catch (err) { this.menu.set(undefined); this.toasts.error(err, 'Cannot list folders: '); }
  }

  protected onDocDown(e: Event) {
    if (this.menu() !== undefined && !(e.target as Element).closest('.pop, .dd')) this.menu.set(undefined);
  }

  private measure() {
    const box = this.crumbsEl()?.nativeElement, trail = this.trailEl()?.nativeElement;
    this.overflowing.set(!!box && !!trail && trail.offsetWidth > box.clientWidth);
  }
}
