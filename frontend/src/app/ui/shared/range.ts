import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

export interface RangeValue { lo: number; hi: number }

/** Dual-thumb range over integer steps; the caller maps steps to real units via `format`. */
@Component({
  selector: 'app-range',
  template: `
    <div class="track"><div class="fill" [style.left.%]="pct(lo())" [style.right.%]="100 - pct(hi())"></div></div>
    <input type="range" [min]="min()" [max]="max()" [value]="lo()" (input)="setLo(+$any($event.target).value)" aria-label="Minimum" />
    <input type="range" [min]="min()" [max]="max()" [value]="hi()" (input)="setHi(+$any($event.target).value)" aria-label="Maximum" />
    <div class="labels"><span>{{ format()(lo()) }}</span><span>{{ format()(hi()) }}</span></div>
  `,
  styles: `
    :host { display: block; position: relative; height: 38px; padding-top: 8px; }
    .track { position: absolute; left: 0; right: 0; top: 14px; height: 4px; border-radius: 2px; background: var(--border); }
    .fill { position: absolute; top: 0; bottom: 0; background: var(--accent); border-radius: 2px; }
    input[type='range'] { position: absolute; left: 0; right: 0; top: 8px; width: 100%; height: 16px; margin: 0; appearance: none; background: transparent; pointer-events: none; }
    input[type='range']::-webkit-slider-thumb { appearance: none; width: 16px; height: 16px; border-radius: 50%; background: var(--surface); border: 2px solid var(--accent); box-shadow: var(--shadow-1); pointer-events: auto; cursor: grab; }
    input[type='range']::-moz-range-thumb { width: 12px; height: 12px; border-radius: 50%; background: var(--surface); border: 2px solid var(--accent); pointer-events: auto; cursor: grab; }
    input[type='range']:focus-visible { outline: none; }
    input[type='range']:focus-visible::-webkit-slider-thumb { box-shadow: 0 0 0 3px var(--accent-soft); }
    .labels { position: absolute; left: 0; right: 0; top: 24px; display: flex; justify-content: space-between; font-size: 11px; color: var(--text-2); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Range {
  readonly min = input(0);
  readonly max = input(10);
  readonly lo = input(0);
  readonly hi = input(10);
  readonly format = input<(v: number) => string>(v => String(v));
  readonly changed = output<RangeValue>();

  protected readonly span = computed(() => Math.max(1, this.max() - this.min()));
  protected pct(v: number) { return (v - this.min()) / this.span() * 100; }
  protected setLo(v: number) { this.changed.emit({ lo: Math.min(v, this.hi()), hi: this.hi() }); }
  protected setHi(v: number) { this.changed.emit({ lo: this.lo(), hi: Math.max(v, this.lo()) }); }
}
