import { afterRenderEffect, ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { CatalogStore } from '../../core/catalog-store';
import { joinNames, parseNames } from '../../core/entity-fields';
import { Entity, EntityKind } from '../../core/model';

/** The name list text split at its last separator outside quotes: `head` as typed (ends with that separator) and the `token` being typed, trimmed and unquoted. */
export function splitLast(text: string): { head: string; token: string } {
  let cut = 0, q = false;
  for (let i = 0; i < text.length; i++) { const c = text[i]; if (c === '"') q = !q; else if (!q && (c === ',' || c === ';')) cut = i + 1; }
  return { head: text.slice(0, cut), token: text.slice(cut).trim().replace(/^"/, '') };
}

/** `text` with the token being typed replaced by `name`: the head's names and `name`, re-joined canonically (`joinNames`: `, ` apart, a name with a separator quoted, a repeat dropped). */
export function completeLast(text: string, name: string): string {
  const names = parseNames(splitLast(text).head);
  return joinNames(names.includes(name) ? names : [...names, name]);
}

const LIMIT = 12, DEBOUNCE = 120, fold = (s: string) => s.trim().toLowerCase();
let seq = 0;

/**
 * Name-list input with a combobox over the catalog entities of `kind`: focus, click or typing opens it; with nothing typed after the last
 * separator it lists the most used entities, otherwise the prefix matches of that token (newest answer wins); names already in the list are
 * left out. ↑ ↓ move, ⏎ picks the active row (none: commits the text), Esc closes, Tab closes. A pick completes the token with the entity's
 * primary name and commits at once; otherwise the text commits on ⏎ or blur, once per change. The list is a top-layer popover, so no panel clips it.
 */
@Component({
  selector: 'app-name-combo',
  template: `
    <input #box class="input sm" role="combobox" autocomplete="off" spellcheck="false" aria-autocomplete="list" [attr.aria-expanded]="visible()" [attr.aria-controls]="id"
           [attr.aria-activedescendant]="visible() && active() >= 0 ? id + '-' + active() : null" [value]="text()" [placeholder]="placeholder()"
           (input)="typed($any($event.target).value)" (focus)="show()" (mousedown)="show()" (blur)="left()" (keydown)="key($event)" />
    <div #list class="list panel" popover="manual" role="listbox" [id]="id" (mousedown)="$event.preventDefault()">
      @for (e of options(); track e.id) {
        <div role="option" [id]="id + '-' + $index" [class.active]="active() === $index" [attr.aria-selected]="active() === $index" (mouseenter)="active.set($index)" (click)="pick(e)">
          <span class="name">{{ e.name }}</span><span class="n">{{ e.albums }}</span>
        </div>
      } @empty { <div class="state">Nothing found</div> }
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .list { position: fixed; inset: auto; margin: 0; padding: 4px; max-height: 240px; overflow: auto; border-radius: var(--radius); color: var(--text); animation: fade-up 0.12s var(--ease); }
    [role=option] { display: flex; align-items: center; gap: 8px; height: 26px; padding: 0 8px; border-radius: var(--radius-s); font-size: 12.5px; white-space: nowrap; cursor: pointer; }
    [role=option].active { background: var(--surface-2); }
    .name { flex: 1; overflow: hidden; text-overflow: ellipsis; }
    .n { color: var(--text-3); font-size: 11px; font-variant-numeric: tabular-nums; }
    .state { padding: 4px 8px; color: var(--text-3); font-size: 12px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NameCombo {
  readonly kind = input.required<EntityKind>();
  /** The committed text; a new value replaces what is being typed (another album). */
  readonly value = input('');
  readonly placeholder = input('');
  readonly commit = output<string>();

  private readonly catalog = inject(CatalogStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly box = viewChild.required<ElementRef<HTMLInputElement>>('box');
  private readonly list = viewChild.required<ElementRef<HTMLElement>>('list');
  protected readonly id = `name-combo-${++seq}`;

  protected readonly text = linkedSignal(() => this.value());
  private readonly committed = linkedSignal(() => this.value());
  private readonly parts = computed(() => splitLast(this.text()));
  protected readonly open = signal(false);
  private readonly items = signal<Entity[]>([]);
  private readonly loading = signal(false);
  /** Highlighted row, -1 = none; every answer starts over. */
  protected readonly active = linkedSignal<Entity[], number>({ source: this.items, computation: () => -1 });
  protected readonly options = computed(() => { const have = new Set(parseNames(this.parts().head).map(fold)); return this.items().filter(e => !have.has(fold(e.name))); });
  /** Shown while open and there is something to say: rows, or no match for a typed token. */
  protected readonly visible = computed(() => this.open() && (this.options().length > 0 || (!!this.parts().token && !this.loading())));
  private gen = 0;
  private timer?: ReturnType<typeof setTimeout>;

  constructor() {
    afterRenderEffect(() => {
      const el = this.list().nativeElement, shown = el.matches(':popover-open');
      if (!this.visible()) { if (shown) el.hidePopover(); return; }
      if (!shown) el.showPopover();
      this.place();
    });
    effect(onCleanup => {
      if (!this.open()) return;
      const away = (e: Event) => { if (!(e.target instanceof Node && this.host.contains(e.target))) this.open.set(false); };
      // a scroll around the input (the panel bringing it into view on focus) moves the list along; out of the scroller's sight it closes
      const follow = (e: Event) => {
        if (e.target instanceof Node && this.list().nativeElement.contains(e.target)) return;
        const r = this.box().nativeElement.getBoundingClientRect(), s = e.target instanceof Element ? e.target.getBoundingClientRect() : { top: 0, bottom: innerHeight };
        if (r.bottom < s.top || r.top > s.bottom) this.open.set(false); else if (this.visible()) this.place();
      };
      const close = () => this.open.set(false);
      document.addEventListener('pointerdown', away, true); document.addEventListener('scroll', follow, true); addEventListener('resize', close);
      onCleanup(() => { document.removeEventListener('pointerdown', away, true); document.removeEventListener('scroll', follow, true); removeEventListener('resize', close); });
    });
  }

  /** Under the input (above when it does not fit), at least 220 px wide, inside the viewport. */
  private place() {
    const el = this.list().nativeElement, r = this.box().nativeElement.getBoundingClientRect(), w = Math.min(Math.max(r.width, 220), innerWidth - 8);
    el.style.width = `${w}px`;
    const h = el.offsetHeight, below = r.bottom + 2;
    el.style.left = `${Math.max(4, Math.min(r.left, innerWidth - w - 4))}px`;
    el.style.top = `${below + h <= innerHeight - 4 || r.top - h - 2 < 4 ? below : r.top - h - 2}px`;
  }

  protected show() { if (!this.open()) { this.open.set(true); this.load(this.parts().token); } }

  protected typed(v: string) { this.text.set(v); this.open.set(true); this.load(this.parts().token); }

  protected left() { this.open.set(false); clearTimeout(this.timer); this.commitNow(); }

  protected key(e: KeyboardEvent) {
    const n = this.options().length, open = this.visible();
    switch (e.key) {
      case 'ArrowDown': case 'ArrowUp': { const down = e.key === 'ArrowDown'; if (!this.open()) this.show(); else if (n) this.active.update(i => i < 0 ? (down ? 0 : n - 1) : (i + (down ? 1 : -1) + n) % n); break; }
      case 'Enter': { const it = open ? this.options()[this.active()] : undefined; if (it) this.pick(it); else { this.open.set(false); this.commitNow(); } break; }
      case 'Escape': if (!open) return; e.stopPropagation(); this.open.set(false); break; // App.onKey has its own Esc; the list owns this one
      case 'Tab': this.open.set(false); return;
      default: return;
    }
    e.preventDefault();
  }

  /** Completes the token being typed with the entity's primary name, commits and keeps the caret at the end for more. */
  protected pick(e: Entity) {
    const t = completeLast(this.text(), e.name), el = this.box().nativeElement;
    this.text.set(t); el.value = t; el.setSelectionRange(t.length, t.length);
    this.open.set(false);
    this.commitNow();
  }

  private commitNow() { const t = this.text(); if (t !== this.committed()) { this.committed.set(t); this.commit.emit(t); } }

  /** Most used entities for an empty token, else the token's prefix matches after a short pause; an answer to an older ask is dropped. */
  private load(token: string) {
    clearTimeout(this.timer);
    const gen = ++this.gen, kind = this.kind();
    this.loading.set(true);
    const run = async () => {
      try { const r = await this.catalog.entities(kind, token ? { text: token, limit: LIMIT } : { byCount: true, limit: LIMIT }); if (gen === this.gen) this.items.set(r.items); }
      catch { if (gen === this.gen) this.items.set([]); }
      finally { if (gen === this.gen) this.loading.set(false); }
    };
    if (token) this.timer = setTimeout(run, DEBOUNCE); else void run();
  }
}
