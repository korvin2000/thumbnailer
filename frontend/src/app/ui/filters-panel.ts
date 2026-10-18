import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { formatBytes } from '../core/format';
import { Facet, Filters, Kind } from '../core/model';
import { AlbumStore } from '../core/store';
import { Icon } from './shared/icon';
import { Range, RangeValue } from './shared/range';

/** Slider scales: index → value; the last entry means "no upper bound". */
const SIZES = [0, 1e6, 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 1e9, 2e9, 5e9, 10e9, 50e9, Infinity];
const COUNTS = [0, 5, 10, 20, 50, 100, 200, 500, 1000, Infinity];
const MEGAPIXELS = [0, 1, 2, 4, 8, 12, 16, 24, 36, 50, 100, Infinity];

@Component({
  selector: 'app-filters-panel',
  imports: [Icon, Range],
  template: `
    <section>
      <h3><app-icon name="hash" [size]="13" /> Tags</h3>
      <form (submit)="addTag(tag.value); tag.value = ''; $event.preventDefault()">
        <input #tag class="input sm" placeholder="Add a tag and press Enter" list="facet-tags" />
        <datalist id="facet-tags">@for (t of facets().tags; track t.value) { <option [value]="t.value"></option> }</datalist>
      </form>
      <div class="chips">
        @for (t of tagChoices(); track t.value) {
          <button class="chip" [class.active]="activeTags().includes(t.value)" (click)="store.toggleTag(t.value)">#{{ t.value }}<span class="n">{{ t.count }}</span></button>
        }
      </div>
    </section>

    <section class="facets">
      <label><span><app-icon name="user" [size]="13" /> Model</span>
        <select class="input sm" [value]="f().model ?? ''" (change)="store.setFilter('model', $any($event.target).value)">
          <option value="">Any</option>@for (x of facets().models; track x.value) { <option [value]="x.value">{{ x.value }} ({{ x.count }})</option> }
        </select></label>
      <label><span><app-icon name="building" [size]="13" /> Studio</span>
        <select class="input sm" [value]="f().studio ?? ''" (change)="store.setFilter('studio', $any($event.target).value)">
          <option value="">Any</option>@for (x of facets().studios; track x.value) { <option [value]="x.value">{{ x.value }} ({{ x.count }})</option> }
        </select></label>
      <label><span><app-icon name="camera" [size]="13" /> Photographer</span>
        <select class="input sm" [value]="f().photographer ?? ''" (change)="store.setFilter('photographer', $any($event.target).value)">
          <option value="">Any</option>@for (x of facets().photographers; track x.value) { <option [value]="x.value">{{ x.value }} ({{ x.count }})</option> }
        </select></label>
    </section>

    <section>
      <h3><app-icon name="archive" [size]="13" /> Kind</h3>
      <div class="segmented kinds">
        <button [class.active]="!f().kind" (click)="store.setFilter('kind', null)" title="Folders and archives">All</button>
        <button [class.active]="f().kind === 'DIRECTORY'" (click)="setKind('DIRECTORY')" title="Plain folders">Folders</button>
        <button [class.active]="f().kind === 'ZIP'" (click)="setKind('ZIP')" title="Zip archives">Zips</button>
        <button [class.active]="f().kind === 'ARCHIVE'" (click)="setKind('ARCHIVE')" title="Other archives: rar, 7z, tar, iso">Archives</button>
      </div>
    </section>

    <section>
      <h3><app-icon name="calendar" [size]="13" /> Shot between</h3>
      <div class="dates">
        <input class="input sm" type="date" [value]="f().dateFrom ?? ''" (change)="store.setFilter('dateFrom', $any($event.target).value)" />
        <span>→</span>
        <input class="input sm" type="date" [value]="f().dateTo ?? ''" (change)="store.setFilter('dateTo', $any($event.target).value)" />
      </div>
    </section>

    <section>
      <h3><app-icon name="database" [size]="13" /> Album size</h3>
      <app-range [max]="sizes.length - 1" [lo]="idx(sizes, f().minBytes, 0)" [hi]="idx(sizes, f().maxBytes, sizes.length - 1)" [format]="fmtSize" (changed)="setRange('minBytes', 'maxBytes', sizes, $event)" />
    </section>
    <section>
      <h3><app-icon name="images" [size]="13" /> Photos per album</h3>
      <app-range [max]="counts.length - 1" [lo]="idx(counts, f().minCount, 0)" [hi]="idx(counts, f().maxCount, counts.length - 1)" [format]="fmtCount" (changed)="setRange('minCount', 'maxCount', counts, $event)" />
    </section>
    <section>
      <h3><app-icon name="zoom" [size]="13" /> Quality (average resolution)</h3>
      <app-range [max]="mps.length - 1" [lo]="idx(mps, f().minMegapixels, 0)" [hi]="idx(mps, f().maxMegapixels, mps.length - 1)" [format]="fmtMp" (changed)="setRange('minMegapixels', 'maxMegapixels', mps, $event)" />
    </section>
    <section>
      <h3><app-icon name="star" [size]="13" /> Rating</h3>
      <app-range [max]="10" [lo]="f().minRating ?? 0" [hi]="f().maxRating ?? 10" [format]="fmtRating" (changed)="setRating($event)" />
    </section>
  `,
  styles: `
    :host { display: block; padding: 6px 14px 20px; }
    section { padding: 12px 0; border-bottom: 1px solid var(--border); }
    section:last-child { border-bottom: 0; }
    h3 { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--text-3); }
    .kinds { display: flex; width: 100%; }
    .kinds > button { flex: 1; justify-content: center; padding: 0 4px; }
    .chips { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
    .facets { display: flex; flex-direction: column; gap: 8px; }
    .facets label { display: grid; grid-template-columns: 110px 1fr; align-items: center; gap: 8px; font-size: 12px; color: var(--text-2); }
    .facets label span { display: inline-flex; align-items: center; gap: 6px; }
    .dates { display: flex; align-items: center; gap: 6px; color: var(--text-3); }
    .dates .input { flex: 1; min-width: 0; width: auto; padding: 0 6px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiltersPanel {
  protected readonly store = inject(AlbumStore);
  protected readonly sizes = SIZES;
  protected readonly counts = COUNTS;
  protected readonly mps = MEGAPIXELS;

  protected readonly f = this.store.filters;
  protected readonly facets = computed(() => this.store.facets() ?? { tags: [], models: [], studios: [], photographers: [] });
  protected readonly activeTags = computed(() => this.store.query().tags ?? []);
  /** Active tags first (even without facet counts), then the most frequent ones. */
  protected readonly tagChoices = computed<Facet[]>(() => {
    const known = this.facets().tags, active = this.activeTags();
    const extra = active.filter(t => !known.some(k => k.value === t)).map(value => ({ value, count: 0 }));
    return [...extra, ...known].sort((a, b) => +active.includes(b.value) - +active.includes(a.value) || b.count - a.count).slice(0, 40);
  });

  protected readonly fmtSize = (i: number) => SIZES[i] === Infinity ? '∞' : SIZES[i] === 0 ? '0' : formatBytes(SIZES[i], 0);
  protected readonly fmtCount = (i: number) => COUNTS[i] === Infinity ? '∞' : String(COUNTS[i]);
  protected readonly fmtMp = (i: number) => MEGAPIXELS[i] === Infinity ? '∞' : MEGAPIXELS[i] + ' MP';
  protected readonly fmtRating = (v: number) => v === 0 ? 'unrated' : '★ ' + v;

  protected idx(scale: number[], value: number | undefined, fallback: number): number {
    if (value == null) return fallback;
    let best = 0;
    scale.forEach((v, i) => { if (Math.abs(v - value) < Math.abs(scale[best] - value)) best = i; });
    return best;
  }

  protected setRange(minKey: keyof Filters, maxKey: keyof Filters, scale: number[], r: RangeValue) {
    this.store.setFilter(minKey as 'minBytes', r.lo === 0 ? null : scale[r.lo]);
    this.store.setFilter(maxKey as 'maxBytes', scale[r.hi] === Infinity ? null : scale[r.hi]);
  }

  protected setRating(r: RangeValue) {
    this.store.setFilter('minRating', r.lo === 0 ? null : r.lo);
    this.store.setFilter('maxRating', r.hi === 10 ? null : r.hi);
  }

  protected setKind(k: Kind) { this.store.setFilter('kind', this.f().kind === k ? null : k); }

  protected addTag(raw: string) {
    const tag = raw.trim().replace(/^#/, '').toLowerCase();
    if (tag && !this.activeTags().includes(tag)) this.store.toggleTag(tag);
  }
}
