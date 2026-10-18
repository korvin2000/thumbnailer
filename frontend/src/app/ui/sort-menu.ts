import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal } from '@angular/core';
import { SORT_FIELDS, SortField } from '../core/model';
import { AlbumStore } from '../core/store';
import { Icon } from './shared/icon';

/** Sort control: field picker (popover) + direction toggle, both mapped onto the single wire `Sort` value. */
@Component({
  selector: 'app-sort-menu',
  imports: [Icon],
  host: { '(document:click)': 'onDocClick($event)', '(document:keydown.escape)': 'open.set(false)' },
  template: `
    <button class="field" [class.open]="open()" title="Sort by" aria-haspopup="menu" [attr.aria-expanded]="open()" (click)="open.set(!open())">
      <span>{{ field().label }}</span><app-icon name="chevronDown" [size]="12" />
    </button>
    <button class="dir" [title]="desc() ? 'Descending — click for ascending' : 'Ascending — click for descending'" (click)="pick(field())">
      <app-icon [name]="desc() ? 'sortDesc' : 'sortAsc'" [size]="15" />
    </button>
    @if (open()) {
      <div class="menu panel" role="menu">
        @for (f of fields; track f.label) {
          @let on = f === field();
          <button class="item" role="menuitemradio" [class.on]="on" [attr.aria-checked]="on" [title]="on ? 'Click again to reverse' : ''" (click)="pick(f)">
            <span>{{ f.label }}</span>@if (on) { <app-icon [name]="desc() ? 'sortDesc' : 'sortAsc'" [size]="14" /> }
          </button>
        }
      </div>
    }
  `,
  styles: `
    :host { position: relative; display: inline-flex; flex: none; height: 32px; border: 1px solid var(--border); border-radius: var(--radius-s); background: var(--surface); transition: border-color 0.15s; }
    :host:hover { border-color: var(--border-strong); }
    .field, .dir { display: inline-flex; align-items: center; color: var(--text); transition: background 0.15s, color 0.15s; }
    .field { gap: 6px; min-width: 98px; padding: 0 8px 0 11px; justify-content: space-between; font-weight: 500; white-space: nowrap; border-radius: calc(var(--radius-s) - 1px) 0 0 calc(var(--radius-s) - 1px); }
    .field app-icon { color: var(--text-3); transition: transform 0.15s var(--ease); }
    .field.open app-icon { transform: rotate(180deg); }
    .dir { justify-content: center; width: 32px; border-left: 1px solid var(--border); color: var(--text-2); border-radius: 0 calc(var(--radius-s) - 1px) calc(var(--radius-s) - 1px) 0; }
    .field:hover, .field.open, .dir:hover { background: var(--surface-2); color: var(--text); }
    .menu { position: absolute; top: calc(100% + 6px); right: 0; z-index: 30; min-width: 190px; padding: 4px; border-radius: var(--radius); animation: fade-up 0.15s var(--ease); }
    .item { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; height: 28px; padding: 0 8px 0 10px; border-radius: var(--radius-s); color: var(--text-2); text-align: left; }
    .item:hover { background: var(--surface-2); color: var(--text); }
    .item.on { background: var(--accent-soft); color: var(--accent-strong); font-weight: 500; }
    .item.on app-icon { color: var(--accent); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SortMenu {
  private readonly store = inject(AlbumStore);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  protected readonly fields = SORT_FIELDS;
  protected readonly open = signal(false);
  protected readonly field = computed(() => SORT_FIELDS.find(f => f.desc === this.store.sort() || f.asc === this.store.sort()) ?? SORT_FIELDS[0]);
  protected readonly desc = computed(() => this.store.sort() !== this.field().asc);

  /** A new field starts in its default direction; the current one flips. */
  protected pick(f: SortField) {
    this.store.sort.set(f !== this.field() ? (f.ascFirst ? f.asc : f.desc) : this.desc() ? f.asc : f.desc);
    this.open.set(false);
  }

  protected onDocClick(e: Event) { if (this.open() && !this.host.contains(e.target as Node)) this.open.set(false); }
}
