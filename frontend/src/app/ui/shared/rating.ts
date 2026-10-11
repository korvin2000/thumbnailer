import { ChangeDetectionStrategy, Component, model, signal } from '@angular/core';

/** 1–10 rating as ten rising bars; clicking the current value clears it. */
@Component({
  selector: 'app-rating',
  template: `
    @for (n of steps; track n) {
      <button type="button" class="bar" [class.on]="n <= (hover() || value())" [class.peek]="hover() && n <= hover() && n > value()"
              [style.height.px]="8 + n * 1.4" [title]="n + ' / 10'" (mouseenter)="hover.set(n)" (mouseleave)="hover.set(0)" (click)="set(n)"></button>
    }
    <span class="num">{{ hover() || value() ? (hover() || value()) + '/10' : 'Unrated' }}</span>
  `,
  styles: `
    :host { display: inline-flex; align-items: flex-end; gap: 3px; height: 24px; }
    .bar { width: 10px; border-radius: 2px 2px 1px 1px; background: var(--border-strong); transition: background 0.12s, transform 0.12s; }
    .bar:hover { transform: scaleY(1.08); }
    .bar.on { background: var(--accent); }
    .bar.peek { background: color-mix(in oklch, var(--accent) 55%, var(--border-strong)); }
    .num { margin-left: 8px; font-size: 12px; color: var(--text-2); min-width: 52px; align-self: center; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Rating {
  readonly value = model(0);
  protected readonly hover = signal(0);
  protected readonly steps = Array.from({ length: 10 }, (_, i) => i + 1);

  protected set(n: number) { this.value.set(n === this.value() ? 0 : n); }
}
