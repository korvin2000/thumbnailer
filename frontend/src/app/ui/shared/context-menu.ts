import { NgTemplateOutlet } from '@angular/common';
import { afterRenderEffect, ChangeDetectionStrategy, Component, effect, ElementRef, inject, Injectable, linkedSignal, signal, viewChild } from '@angular/core';
import { Icon, IconName } from './icon';

/** One menu row; `children` opens a submenu (one level), static or loaded on open. */
export interface MenuItem { label: string; icon?: IconName; keys?: string; disabled?: boolean; danger?: boolean; checked?: boolean; children?: MenuEntry[] | (() => Promise<MenuEntry[]>); run?: () => void }
export interface MenuSeparator { sep: true }
export type MenuEntry = MenuItem | MenuSeparator;
export interface MenuState { x: number; y: number; items: MenuEntry[] }

/** The app's one context menu: callers build the items from current state on every open. */
@Injectable({ providedIn: 'root' })
export class ContextMenu {
  readonly state = signal<MenuState | null>(null);
  open(x: number, y: number, items: MenuEntry[]) { this.state.set({ x, y, items }); }
  close() { this.state.set(null); }
}

interface Sub { index: number; x: number; y: number; items: MenuEntry[] | null }

const isItem = (e: MenuEntry | undefined): e is MenuItem => !!e && !('sep' in e);
/** Puts a panel at a point, pulled back inside the viewport. */
const place = (el: HTMLElement, x: number, y: number) => {
  el.style.left = `${x}px`; el.style.top = `${y}px`;
  const r = el.getBoundingClientRect();
  if (r.right > innerWidth - 4) el.style.left = `${Math.max(4, innerWidth - r.width - 4)}px`;
  if (r.bottom > innerHeight - 4) el.style.top = `${Math.max(4, innerHeight - r.height - 4)}px`;
};

/** Renders `ContextMenu.state` as top-layer popovers (above open dialogs); closes on Esc, outside click, scroll, resize and blur; ↑ ↓ → ← ⏎ while open. */
@Component({
  selector: 'app-context-menu',
  imports: [Icon, NgTemplateOutlet],
  template: `
    @if (menu.state(); as s) {
      <div #panel class="menu panel" popover="manual" role="menu" tabindex="-1" (keydown)="key($event)" (contextmenu)="$event.preventDefault()">
        <ng-container *ngTemplateOutlet="list; context: { $implicit: s.items, level: 0 }" />
      </div>
      @if (sub(); as sb) {
        <div #subPanel class="menu panel" popover="manual" role="menu" tabindex="-1" (keydown)="key($event)" (contextmenu)="$event.preventDefault()">
          @if (sb.items) { <ng-container *ngTemplateOutlet="list; context: { $implicit: sb.items, level: 1 }" /> }
          @else { <div class="loading"><span class="spinner"></span></div> }
        </div>
      }
    }
    <ng-template #list let-items let-level="level">
      @for (e of items; track $index) {
        @if (e.sep) { <div class="sep" role="separator"></div> }
        @else {
          <button role="menuitem" [attr.data-i]="$index" [class.active]="active()[level] === $index" [class.danger]="e.danger" [disabled]="e.disabled"
                  (mouseenter)="hover(level, $index)" (click)="activate(level, $index)">
            <span class="ic">@if (e.checked) { <app-icon name="check" [size]="14" /> } @else if (e.icon) { <app-icon [name]="e.icon" [size]="14" /> }</span>
            <span class="lb">{{ e.label }}</span>
            @if (e.keys) { <span class="keys">{{ e.keys }}</span> }
            @if (e.children) { <app-icon name="chevronRight" [size]="13" class="more" /> }
          </button>
        }
      }
    </ng-template>
  `,
  styles: `
    .menu { position: fixed; inset: auto; margin: 0; min-width: 190px; max-width: 320px; max-height: calc(100vh - 8px); overflow: auto; padding: 4px; border-radius: var(--radius); color: var(--text); outline: none; }
    button { display: flex; align-items: center; gap: 8px; width: 100%; height: 28px; padding: 0 8px; border-radius: var(--radius-s); font-size: 12.5px; text-align: left; white-space: nowrap; }
    button.active:not(:disabled) { background: var(--surface-2); }
    button.danger { color: var(--danger); }
    .ic { display: inline-flex; width: 14px; flex: none; color: var(--text-3); }
    .lb { flex: 1; overflow: hidden; text-overflow: ellipsis; }
    .keys { color: var(--text-3); font-family: var(--mono); font-size: 11px; }
    .more { color: var(--text-3); }
    .sep { height: 1px; margin: 4px 6px; background: var(--border); }
    .loading { display: flex; justify-content: center; padding: 8px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContextMenuView {
  protected readonly menu = inject(ContextMenu);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly subPanel = viewChild<ElementRef<HTMLElement>>('subPanel');

  /** Every open starts closed-sub, nothing active, keys on the main panel. */
  protected readonly sub = linkedSignal<MenuState | null, Sub | null>({ source: this.menu.state, computation: () => null });
  protected readonly active = linkedSignal<MenuState | null, [number, number]>({ source: this.menu.state, computation: () => [-1, -1] });
  private readonly level = linkedSignal<MenuState | null, number>({ source: this.menu.state, computation: () => 0 });

  constructor() {
    afterRenderEffect(() => {
      const s = this.menu.state(), sb = this.sub(), level = this.level(), main = this.panel()?.nativeElement, side = this.subPanel()?.nativeElement;
      if (main && s) { if (!main.matches(':popover-open')) main.showPopover(); place(main, s.x, s.y); }
      if (side && sb) { if (!side.matches(':popover-open')) side.showPopover(); place(side, sb.x, sb.y); }
      (level && side ? side : main)?.focus({ preventScroll: true });
    });
    effect(onCleanup => {
      if (!this.menu.state()) return;
      const away = (e: Event) => { if (!(e.target instanceof Node && this.host.nativeElement.contains(e.target))) this.menu.close(); };
      const close = () => this.menu.close();
      document.addEventListener('pointerdown', away, true); document.addEventListener('scroll', away, true);
      addEventListener('resize', close); addEventListener('blur', close);
      onCleanup(() => {
        document.removeEventListener('pointerdown', away, true); document.removeEventListener('scroll', away, true);
        removeEventListener('resize', close); removeEventListener('blur', close);
      });
    });
  }

  protected hover(level: number, i: number) {
    this.level.set(level);
    this.active.update(a => level ? [a[0], i] : [i, -1]);
    if (level === 0) void this.openSub(i, false);
  }

  /** Click / Enter: a parent opens its submenu, a leaf closes the menu and runs. */
  protected activate(level: number, i: number) {
    const it = this.items(level)[i];
    if (!isItem(it) || it.disabled) return;
    if (it.children) return void this.openSub(i, true);
    this.menu.close();
    it.run?.();
  }

  protected key(e: KeyboardEvent) {
    e.stopPropagation(); // the menu owns the keyboard while open (App.onKey listens on the document)
    const level = this.level(), cur = this.active()[level], it = this.items(level)[cur];
    switch (e.key) {
      case 'ArrowDown': case 'ArrowUp': this.move(level, e.key === 'ArrowDown' ? 1 : -1); break;
      case 'ArrowRight': if (level === 0 && isItem(it) && it.children) void this.openSub(cur, true); break;
      case 'ArrowLeft': if (level === 1) this.leaveSub(); break;
      case 'Enter': case ' ': if (cur >= 0) this.activate(level, cur); break;
      case 'Escape': if (level === 1) this.leaveSub(); else this.menu.close(); break;
      case 'Tab': this.menu.close(); break;
      default: return;
    }
    e.preventDefault();
  }

  private items(level: number): MenuEntry[] { return (level ? this.sub()?.items : this.menu.state()?.items) ?? []; }

  private move(level: number, step: number) {
    const items = this.items(level);
    for (let n = 1, i = this.active()[level]; n <= items.length; n++) {
      const j = (i + step * n + items.length * 2) % items.length, it = items[j];
      if (isItem(it) && !it.disabled) return this.active.update(a => level ? [a[0], j] : [j, -1]);
    }
  }

  private async openSub(index: number, focus: boolean) {
    const it = this.items(0)[index];
    if (!isItem(it) || !it.children || it.disabled) return this.sub.set(null);
    if (this.sub()?.index !== index) {
      const r = this.panel()!.nativeElement.querySelector(`[data-i="${index}"]`)!.getBoundingClientRect();
      const sub: Sub = { index, x: r.right - 4, y: r.top - 5, items: Array.isArray(it.children) ? it.children : null };
      this.sub.set(sub);
      if (!sub.items) {
        const items = await (it.children as () => Promise<MenuEntry[]>)().catch(() => [{ label: 'Could not load', disabled: true }]);
        if (this.sub() !== sub) return;
        this.sub.set({ ...sub, items });
      }
    }
    if (focus) { this.level.set(1); this.move(1, 1); }
  }

  private leaveSub() { this.sub.set(null); this.level.set(0); }
}
