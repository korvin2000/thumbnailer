import { afterRenderEffect, ChangeDetectionStrategy, Component, effect, ElementRef, inject, Injectable, linkedSignal, signal, viewChildren } from '@angular/core';
import { Icon, IconName } from './icon';

/** One menu row; `children` opens a submenu (any depth), static or loaded when it opens. */
export interface MenuItem { label: string; icon?: IconName; keys?: string; disabled?: boolean; danger?: boolean; checked?: boolean; children?: MenuEntry[] | (() => Promise<MenuEntry[]>); run?: () => void }
export interface MenuSeparator { sep: true }
export type MenuEntry = MenuItem | MenuSeparator;
export interface MenuState { x: number; y: number; items: MenuEntry[] }
/** Viewport rectangle; a point has `w = h = 0`. */
export interface Box { x: number; y: number; w: number; h: number }

/** The app's one context menu: callers build the items from current state on every open. */
@Injectable({ providedIn: 'root' })
export class ContextMenu {
  readonly state = signal<MenuState | null>(null);
  open(x: number, y: number, items: MenuEntry[]) { this.state.set({ x, y, items }); }
  close() { this.state.set(null); }
}

const MARGIN = 4, PAD = 5, OVERLAP = 4, HOVER_DELAY = 120;
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(v, hi));
const isItem = (e: MenuEntry | undefined): e is MenuItem => !!e && !('sep' in e);

/**
 * Top-left corner of a `w × h` panel in a `vw × vh` viewport. A root menu opens at its point and flips to the left of / above it when it
 * would overflow; a submenu (`sub`) hangs off its parent row: to its right, else to its left, first row level with the parent row, slid up
 * when it would overflow. Never closer than 4 px to an edge.
 */
export function placeMenu(a: Box, w: number, h: number, vw: number, vh: number, sub = false): { x: number; y: number } {
  const fit = (want: number, flipped: number, size: number, max: number) => want + size <= max - MARGIN ? want : flipped >= MARGIN ? flipped : clamp(want, MARGIN, max - size - MARGIN);
  return sub
    ? { x: fit(a.x + a.w - OVERLAP, a.x - w + OVERLAP, w, vw), y: clamp(a.y - PAD, MARGIN, vh - h - MARGIN) }
    : { x: fit(a.x, a.x - w, w, vw), y: fit(a.y, a.y - h, h, vh) };
}

/** One open panel: the root, or the submenu of row `parent` of the panel before it (`items` null while loading). */
interface Panel { items: MenuEntry[] | null; anchor: Box; parent: number }

/**
 * Renders `ContextMenu.state` as a stack of top-layer popovers (above open dialogs), placed by `placeMenu`. Submenus open on hover (after a
 * short grace so the pointer may cross other rows) or →; ↑ ↓ Home End move, ⏎ runs, ← / Esc close one level, letters jump by label. Closes on
 * outside pointer, scroll, resize and blur, then gives the keyboard back to the opener.
 */
@Component({
  selector: 'app-context-menu',
  imports: [Icon],
  template: `
    @for (p of panels(); track $index; let level = $index) {
      <div #panel class="menu panel" popover="manual" role="menu" tabindex="-1" (keydown)="key($event)" (mouseenter)="enter(level)" (contextmenu)="$event.preventDefault()">
        @if (p.items; as items) {
          @for (e of items; track $index) {
            @if (!isItem(e)) { <div class="sep" role="separator"></div> }
            @else {
              <button type="button" [attr.role]="e.checked === undefined ? 'menuitem' : 'menuitemcheckbox'" [attr.aria-checked]="e.checked" [attr.aria-haspopup]="e.children ? 'menu' : null"
                      [attr.data-i]="$index" [class.active]="active()[level] === $index" [class.danger]="e.danger" [disabled]="e.disabled"
                      (mouseenter)="hover(level, $index)" (click)="activate(level, $index)">
                <span class="ic">@if (e.checked) { <app-icon name="check" [size]="14" /> } @else if (e.icon) { <app-icon [name]="e.icon" [size]="14" /> }</span>
                <span class="lb">{{ e.label }}</span>
                @if (e.keys) { <span class="keys">{{ e.keys }}</span> }
                @if (e.children) { <app-icon name="chevronRight" [size]="13" class="more" /> }
              </button>
            }
          }
        } @else { <div class="loading"><span class="spinner"></span></div> }
      </div>
    }
  `,
  styles: `
    .menu { position: fixed; inset: auto; margin: 0; min-width: 190px; max-width: 320px; max-height: calc(100vh - 8px); overflow: auto; padding: 4px; border-radius: var(--radius); color: var(--text); outline: none; animation: fade-up 0.12s var(--ease); }
    button { display: flex; align-items: center; gap: 8px; width: 100%; height: 28px; padding: 0 8px; border-radius: var(--radius-s); font-size: 12.5px; text-align: left; white-space: nowrap; }
    button.active:not(:disabled) { background: var(--surface-2); }
    button:disabled { color: var(--text-3); cursor: default; }
    button.danger { color: var(--danger); }
    button.danger.active:not(:disabled) { background: color-mix(in oklch, var(--danger) 14%, transparent); }
    .ic { display: inline-flex; width: 14px; flex: none; color: var(--text-3); }
    button.danger .ic { color: inherit; }
    .lb { flex: 1; overflow: hidden; text-overflow: ellipsis; }
    .keys { padding-left: 12px; color: var(--text-3); font-family: var(--mono); font-size: 11px; }
    .more { margin-right: -3px; color: var(--text-3); }
    .sep { height: 1px; margin: 4px 6px; background: var(--border); }
    .loading { display: flex; justify-content: center; padding: 8px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContextMenuView {
  protected readonly menu = inject(ContextMenu);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly panelEls = viewChildren<ElementRef<HTMLElement>>('panel');
  protected readonly isItem = isItem;

  /** Open panels, root first; every open starts with the root alone, nothing active, keys on the root. */
  protected readonly panels = linkedSignal<MenuState | null, Panel[]>({ source: this.menu.state, computation: s => s ? [{ items: s.items, anchor: { x: s.x, y: s.y, w: 0, h: 0 }, parent: -1 }] : [] });
  /** Highlighted row per level, -1 = none. */
  protected readonly active = linkedSignal<MenuState | null, number[]>({ source: this.menu.state, computation: () => [-1] });
  /** The panel that owns the keyboard. */
  private readonly level = linkedSignal<MenuState | null, number>({ source: this.menu.state, computation: () => 0 });
  private timer?: ReturnType<typeof setTimeout>;

  constructor() {
    afterRenderEffect(() => {
      const panels = this.panels(), level = this.level();
      this.panelEls().forEach((ref, i) => {
        const el = ref.nativeElement, p = panels[i];
        if (!p) return;
        if (!el.matches(':popover-open')) el.showPopover();
        const { x, y } = placeMenu(p.anchor, el.offsetWidth, el.offsetHeight, innerWidth, innerHeight, i > 0);
        el.style.left = `${x}px`; el.style.top = `${y}px`;
      });
      this.panelEls()[level]?.nativeElement.focus({ preventScroll: true });
    });
    effect(onCleanup => {
      if (!this.menu.state()) return;
      const opener = document.activeElement;
      const away = (e: Event) => { if (!(e.target instanceof Node && this.host.contains(e.target))) this.menu.close(); };
      const close = () => this.menu.close();
      document.addEventListener('pointerdown', away, true); document.addEventListener('scroll', away, true);
      addEventListener('resize', close); addEventListener('blur', close);
      onCleanup(() => {
        document.removeEventListener('pointerdown', away, true); document.removeEventListener('scroll', away, true);
        removeEventListener('resize', close); removeEventListener('blur', close);
        clearTimeout(this.timer);
        // the opener (the grid after Shift+F10) gets the keyboard back unless a click or a dialog already took it
        const a = document.activeElement;
        if (opener instanceof HTMLElement && opener.isConnected && (!a || a === document.body || this.host.contains(a))) opener.focus({ preventScroll: true });
      });
    });
  }

  protected hover(level: number, i: number) {
    clearTimeout(this.timer);
    this.level.set(level);
    this.setActive(level, i);
    this.timer = setTimeout(() => void this.openSub(level, i, false), HOVER_DELAY);
  }

  /** The pointer entered a panel: its ancestors highlight the rows that opened it, and a pending hover elsewhere no longer closes it. */
  protected enter(level: number) {
    clearTimeout(this.timer);
    this.level.set(level);
    this.active.update(a => a.map((v, l) => l < level ? this.panels()[l + 1]?.parent ?? v : v));
  }

  /** Click / Enter: a parent opens its submenu, a leaf closes the menu and runs. */
  protected activate(level: number, i: number) {
    const it = this.items(level)[i];
    if (!isItem(it) || it.disabled) return;
    if (it.children) return void this.openSub(level, i, true);
    this.menu.close();
    it.run?.();
  }

  protected key(e: KeyboardEvent) {
    e.stopPropagation(); // the menu owns the keyboard while open (App.onKey listens on the document)
    const level = this.level(), cur = this.active()[level] ?? -1, it = this.items(level)[cur];
    switch (e.key) {
      case 'ArrowDown': this.move(level, 1); break;
      case 'ArrowUp': this.move(level, -1); break;
      case 'Home': this.move(level, 1, -1); break;
      case 'End': this.move(level, -1, this.items(level).length); break;
      case 'ArrowRight': if (isItem(it) && it.children && !it.disabled) void this.openSub(level, cur, true); break;
      case 'ArrowLeft': if (level) this.leave(level); break;
      case 'Enter': case ' ': if (cur >= 0) this.activate(level, cur); break;
      case 'Escape': if (level) this.leave(level); else this.menu.close(); break;
      case 'Tab': this.menu.close(); break;
      default: if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) this.jump(level, e.key); else return;
    }
    e.preventDefault();
  }

  private items(level: number): MenuEntry[] { return this.panels()[level]?.items ?? []; }
  private setActive(level: number, i: number) { this.active.update(a => [...a.slice(0, level), i]); }

  /** Highlights the next enabled row from `from` in `step` direction, wrapping. */
  private move(level: number, step: 1 | -1, from = this.active()[level] ?? -1) {
    const items = this.items(level), n = items.length;
    for (let k = 1; k <= n; k++) { const j = (from + step * k + 2 * n) % n, it = items[j]; if (isItem(it) && !it.disabled) return this.setActive(level, j); }
  }

  /** Type-ahead: the next enabled row whose label starts with the typed character. */
  private jump(level: number, ch: string) {
    const items = this.items(level), n = items.length, from = this.active()[level] ?? -1, c = ch.toLowerCase();
    for (let k = 1; k <= n; k++) { const j = (from + k) % n, it = items[j]; if (isItem(it) && !it.disabled && it.label.toLowerCase().startsWith(c)) return this.setActive(level, j); }
  }

  /** Opens row `i`'s submenu below `level` (closing deeper panels); a row without children only closes them. `focus` moves the keyboard in once the items are there. */
  private async openSub(level: number, i: number, focus: boolean) {
    const it = this.items(level)[i], keep = this.panels().slice(0, level + 1);
    if (!isItem(it) || !it.children || it.disabled) { if (this.panels().length > keep.length) this.panels.set(keep); return; }
    let p = this.panels()[level + 1];
    if (p?.parent !== i) {
      const r = this.panelEls()[level]?.nativeElement.querySelector(`[data-i="${i}"]`)?.getBoundingClientRect();
      if (!r) return;
      p = { items: Array.isArray(it.children) ? it.children : null, anchor: { x: r.left, y: r.top, w: r.width, h: r.height }, parent: i };
      this.panels.set([...keep, p]);
      this.setActive(level + 1, -1);
      if (!p.items) {
        const items = await (it.children as () => Promise<MenuEntry[]>)().catch(() => [{ label: 'Could not load', disabled: true } as MenuItem]);
        if (this.panels()[level + 1] !== p) return;
        this.panels.update(ps => ps.map(x => x === p ? { ...x, items } : x));
      }
    } else if (this.panels().length > level + 2) this.panels.set([...keep, p]);
    if (focus) { this.level.set(level + 1); this.move(level + 1, 1, -1); }
  }

  private leave(level: number) { this.panels.update(ps => ps.slice(0, level)); this.level.set(level - 1); }
}
