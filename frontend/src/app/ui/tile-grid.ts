import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy, Component, computed, contentChild, DestroyRef, effect, ElementRef, inject, input, output, signal,
  TemplateRef, untracked,
} from '@angular/core';
import { layout, LayoutMode, Placement, responsiveColumns, Tile } from '../core/layout';
import { Selection } from '../core/selection';

/** A context menu was asked for: on a tile (`id`), or on the empty canvas (null); `x, y` = viewport point to open it at. */
export interface TileMenuEvent { id: string | null; x: number; y: number }

/**
 * Virtualized grid: lays tiles out with the layout engine and renders only the placements near the viewport.
 * The projected `<ng-template let-item let-placement="placement">` draws one tile; the host is the scroll container.
 * `select` is the focused tile (one), kept apart from the multi-`selection` the grid drives when it is given one.
 */
@Component({
  selector: 'app-tile-grid',
  imports: [NgTemplateOutlet],
  template: `
    <div class="canvas" [style.height.px]="layout().height + 2 * padding()" (click)="onClick($event)" (contextmenu)="onMenu($event)">
      @for (p of visible(); track p.tile.id) {
        <div class="tile" [class.selected]="p.tile.id === selectedId()" [attr.data-id]="p.tile.id"
             [style.transform]="'translate(' + (p.x + padding()) + 'px,' + (p.y + padding()) + 'px)'" [style.width.px]="p.w" [style.height.px]="p.h">
          <ng-container *ngTemplateOutlet="tileTemplate(); context: { $implicit: p.tile.data, placement: p }" />
        </div>
      }
    </div>
    @if (!tiles().length && empty()) {
      <div class="empty"><ng-container *ngTemplateOutlet="empty()!" /></div>
    }
  `,
  styles: `
    :host { display: block; position: relative; overflow: auto; height: 100%; outline: none; overscroll-behavior: contain; }
    .canvas { position: relative; }
    .tile { position: absolute; top: 0; left: 0; contain: layout paint style; user-select: none; transition: transform 0.3s var(--ease), width 0.3s var(--ease), height 0.3s var(--ease); }
    .empty { position: absolute; inset: 0; display: grid; place-items: center; }
  `,
  host: { tabindex: '0', '(scroll)': 'onScroll()', '(keydown)': 'onKey($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TileGrid<T> {
  readonly tiles = input.required<Tile<T>[]>();
  readonly mode = input<LayoutMode>('bricks');
  readonly columns = input(5);
  readonly gap = input(10);
  readonly padding = input(14);
  readonly selectedId = input<string | null>(null);
  readonly hasMore = input(false);
  readonly empty = input<TemplateRef<unknown> | null>(null);
  /** When given, clicks and arrows apply the selection rules over the tile order: plain = only this, Ctrl toggles (Ctrl+Space too), Shift ranges. */
  readonly selection = input<Selection | null>(null);

  readonly select = output<string>();
  readonly open = output<string>();
  readonly reachedEnd = output<void>();
  /** Right-click on a tile or the canvas, Shift+F10 / the menu key on the focused tile. */
  readonly contextMenu = output<TileMenuEvent>();

  protected readonly tileTemplate = contentChild.required(TemplateRef);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly width = signal(0);
  private readonly viewport = signal(0);
  private readonly scrollTop = signal(0);
  private anchor: string | null = null;
  private frame = 0;

  protected readonly layout = computed(() => layout(this.tiles(), {
    width: this.width() - 2 * this.padding(), columns: responsiveColumns(this.columns(), this.width()),
    gap: this.gap(), mode: this.mode(),
  }));

  protected readonly visible = computed(() => {
    const top = this.scrollTop() - this.padding(), over = this.viewport() * 0.75;
    const bottom = top + this.viewport();
    return this.layout().placements.filter(p => p.y < bottom + over && p.y + p.h > top - over);
  });

  constructor() {
    const ro = new ResizeObserver(() => { this.width.set(this.host.clientWidth); this.viewport.set(this.host.clientHeight); });
    ro.observe(this.host);
    inject(DestroyRef).onDestroy(() => ro.disconnect());

    effect(() => {
      const v = this.visible(), all = this.layout().placements;
      if (this.hasMore() && v.length && v[v.length - 1] === all[all.length - 1]) untracked(() => this.reachedEnd.emit());
    });

    // Reflow (column/width/mode change) keeps the tile that was at the top of the viewport in place.
    effect(() => {
      this.columns(); this.width(); this.mode();
      const id = this.anchor;
      untracked(() => {
        const p = id && this.layout().placements.find(x => x.tile.id === id);
        if (p) this.host.scrollTop = p.y + this.padding();
      });
    });
  }

  scrollTo(id: string, align: 'nearest' | 'start' = 'nearest') {
    const p = this.layout().placements.find(x => x.tile.id === id);
    if (!p) return;
    const top = p.y + this.padding(), bottom = top + p.h, st = this.host.scrollTop, vh = this.host.clientHeight;
    if (align === 'start') this.host.scrollTop = top - this.padding();
    else if (top < st) this.host.scrollTop = top - this.padding();
    else if (bottom > st + vh) this.host.scrollTop = bottom - vh + this.padding();
  }

  protected onScroll() {
    if (this.frame) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.scrollTop.set(this.host.scrollTop);
      const top = this.host.scrollTop - this.padding();
      this.anchor = this.layout().placements.find(p => p.y + p.h > top)?.tile.id ?? null;
    });
  }

  /** A click that reached the canvas through a tile applies the selection rules (the tile's own `select` sets the focus). */
  protected onClick(e: MouseEvent) {
    const id = this.tileAt(e);
    if (id !== null && !(e.target as HTMLElement).closest('button, a')) this.pick(id, e);
  }

  protected onMenu(e: MouseEvent) {
    e.preventDefault();
    const id = this.tileAt(e);
    if (id !== null) this.select.emit(id);
    this.contextMenu.emit({ id, x: e.clientX, y: e.clientY });
  }

  protected onKey(e: KeyboardEvent) {
    const all = this.layout().placements;
    if (!all.length || (e.target as HTMLElement).closest('button, a, input, select, textarea')) return;
    const i = all.findIndex(p => p.tile.id === this.selectedId()), cur = i >= 0 ? all[i] : undefined;
    let next: Placement<T> | undefined;
    switch (e.key) {
      case 'ArrowRight': next = all[Math.min(all.length - 1, i + 1)]; break;
      case 'ArrowLeft': next = all[Math.max(0, i - 1)]; break;
      case 'ArrowDown': next = this.vertical(all, i, 1); break;
      case 'ArrowUp': next = this.vertical(all, i, -1); break;
      case 'Home': next = all[0]; break;
      case 'End': next = all[all.length - 1]; break;
      case 'Enter': if (cur) this.open.emit(cur.tile.id); break;
      case ' ': if (cur && (e.ctrlKey || e.metaKey)) this.pick(cur.tile.id, e); else return; break;
      case 'F10': case 'ContextMenu': // Shift+F10 = the menu key: the menu hangs below the focused tile
        if (!cur || (e.key === 'F10' && !e.shiftKey)) return;
        { const r = this.host.querySelector(`[data-id="${cur.tile.id}"]`)?.getBoundingClientRect(); this.contextMenu.emit({ id: cur.tile.id, x: r?.left ?? 0, y: r?.bottom ?? 0 }); }
        break;
      default: return;
    }
    e.preventDefault();
    if (next) { this.select.emit(next.tile.id); this.scrollTo(next.tile.id); if (!e.ctrlKey && !e.metaKey) this.pick(next.tile.id, e); }
  }

  private tileAt(e: Event): string | null { return (e.target as HTMLElement).closest<HTMLElement>('.tile')?.dataset['id'] ?? null; }

  /** Ranges follow the visual order (bricks mode reorders tiles), not the input order. */
  private pick(id: string, mods: { ctrlKey: boolean; metaKey: boolean; shiftKey: boolean }) {
    this.selection()?.click(id, this.layout().placements.map(p => p.tile.id), { ctrl: mods.ctrlKey || mods.metaKey, shift: mods.shiftKey });
  }

  /** Tile in the adjacent row whose centre is closest horizontally. */
  private vertical(all: Placement<T>[], i: number, dir: 1 | -1): Placement<T> | undefined {
    if (i < 0) return all[0];
    const cur = all[i], row = cur.row + dir, cx = cur.x + cur.w / 2;
    const candidates = all.filter(p => p.row === row);
    return candidates.reduce<Placement<T> | undefined>((best, p) => !best || Math.abs(p.x + p.w / 2 - cx) < Math.abs(best.x + best.w / 2 - cx) ? p : best, undefined);
  }
}
