import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Prefs } from '../../core/prefs';
import { Icon } from '../shared/icon';

/** Browser-local preferences; every control applies instantly. */
@Component({
  selector: 'app-appearance-page',
  imports: [Icon],
  template: `
    <h3>Appearance</h3>
    <p class="hint">Stored in this browser and applied as you change them.</p>
    <section class="card">
      <h4>Theme</h4>
      <div class="field"><span>Theme</span>
        <div class="segmented">
          <button [class.active]="prefs.theme() === 'system'" (click)="prefs.theme.set('system')"><app-icon name="monitor" [size]="13" />System</button>
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
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
    .slider { display: inline-flex; align-items: center; gap: 10px; }
    .slider input { width: 200px; }
    .slider b { min-width: 20px; font-weight: 500; font-family: var(--mono); font-size: 12px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppearancePage {
  protected readonly prefs = inject(Prefs);
}
