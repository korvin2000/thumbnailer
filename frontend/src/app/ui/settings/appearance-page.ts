import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Prefs, VIEWER_SCALES, VIEWER_SORTS } from '../../core/prefs';
import { Icon } from '../shared/icon';
import { SCALE_LABELS, SIDE_PRESETS, SORT_LABELS } from '../viewer/photo-order';

/** Browser-local preferences; every control applies instantly. The viewer card holds what the album viewer opens with. */
@Component({
  selector: 'app-appearance-page',
  imports: [Icon],
  template: `
    <h3>Appearance</h3>
    <p class="hint">Stored in this browser and applied as you change them. The theme starts as the system's and then keeps your choice.</p>
    <section class="card">
      <h4>Theme</h4>
      <div class="field"><span>Theme</span>
        <div class="segmented">
          <button [class.active]="prefs.theme() === 'light'" (click)="prefs.theme.set('light')"><app-icon name="sun" [size]="13" />Light</button>
          <button [class.active]="prefs.theme() === 'dark'" (click)="prefs.theme.set('dark')"><app-icon name="moon" [size]="13" />Dark</button>
        </div></div>
    </section>
    <section class="card">
      <h4>Album grid</h4>
      <div class="field"><span>Layout</span>
        <div class="segmented">
          <button [class.active]="prefs.layoutMode() === 'bricks'" (click)="prefs.layoutMode.set('bricks')"><app-icon name="bricks" [size]="13" />Bricks</button>
          <button [class.active]="prefs.layoutMode() === 'grid'" (click)="prefs.layoutMode.set('grid')"><app-icon name="grid" [size]="13" />Grid</button>
        </div></div>
      <label class="field"><span>Albums per row</span><span class="slider"><input type="range" min="1" max="12" [value]="prefs.columns()" (input)="prefs.setColumns(+$any($event.target).value)" /><b>{{ prefs.columns() }}</b></span></label>
      <label class="check"><input type="checkbox" [checked]="prefs.showTags()" (change)="prefs.showTags.set($any($event.target).checked)" /> Show tags on album cards</label>
      <label class="check"><input type="checkbox" [checked]="prefs.followHover()" (change)="prefs.followHover.set($any($event.target).checked)" /> Details panel follows the hovered album</label>
    </section>
    <section class="card">
      <h4>Album viewer</h4>
      <p class="hint">What the viewer opens with; the viewer's own menus change the same values.</p>
      <label class="field"><span>Photos per row</span><span class="slider"><input type="range" min="1" max="12" [value]="prefs.viewerColumns()" (input)="prefs.setViewerColumns(+$any($event.target).value)" /><b>{{ prefs.viewerColumns() }}</b></span></label>
      <div class="field"><span>Lightbox scale</span>
        <div class="segmented">@for (s of scales; track s) { <button [class.active]="prefs.viewerScale() === s" (click)="prefs.viewerScale.set(s)">{{ scaleLabels[s] }}</button> }</div></div>
      <label class="field"><span>Sort photos by</span>
        <select class="input sm" [value]="prefs.viewerSort()" (change)="prefs.viewerSort.set($any($event.target).value)">@for (s of sorts; track s) { <option [value]="s">{{ sortLabels[s] }}</option> }</select></label>
      <div class="field"><span>Longer side at least</span>
        <div class="segmented">@for (p of sides; track p) { <button [class.active]="prefs.viewerMinSide() === p" (click)="prefs.viewerMinSide.set(p)">{{ p ? p + ' px' : 'Any' }}</button> }</div></div>
      <label class="field"><span>Slideshow interval</span><span class="slider"><input type="range" min="1" max="30" [value]="prefs.slideshowSeconds()" (input)="prefs.slideshowSeconds.set(+$any($event.target).value)" /><b>{{ prefs.slideshowSeconds() }} s</b></span></label>
    </section>
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
    .slider { display: inline-flex; align-items: center; gap: 10px; }
    .slider input { width: 200px; }
    .slider b { min-width: 20px; font-weight: 500; font-family: var(--mono); font-size: 12px; }
    select.input { width: auto; min-width: 140px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppearancePage {
  protected readonly prefs = inject(Prefs);
  protected readonly scales = VIEWER_SCALES;
  protected readonly sorts = VIEWER_SORTS;
  protected readonly sides = SIDE_PRESETS;
  protected readonly scaleLabels = SCALE_LABELS;
  protected readonly sortLabels = SORT_LABELS;
}
