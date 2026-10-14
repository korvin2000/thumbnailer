import { ChangeDetectionStrategy, Component, model } from '@angular/core';
import { Render, Settings } from '../../core/model';
import { Icon } from '../shared/icon';

const SIZES = [256, 384, 512, 640, 768, 1024];

/** Controls for one `Render` spec: format, quality, size, threads. */
@Component({
  selector: 'app-render-editor',
  imports: [Icon],
  template: `
    @let r = render();
    <div class="field"><span>Format</span>
      <div class="segmented">
        <button [class.active]="r.format === 'WEBP'" (click)="set({ format: 'WEBP' })">WebP</button>
        <button [class.active]="r.format === 'JPEG'" (click)="set({ format: 'JPEG' })">JPEG</button>
      </div></div>
    <label class="field"><span>Quality</span><span class="slider"><input type="range" min="1" max="100" [value]="r.quality" (input)="set({ quality: +$any($event.target).value })" /><b>{{ r.quality }}</b></span></label>
    <label class="field"><span>Size (longer side)</span>
      <select class="input sm" [value]="r.size" (change)="set({ size: +$any($event.target).value })">
        @for (s of sizes; track s) { <option [value]="s" [selected]="s === r.size">{{ s }} px</option> }
      </select></label>
    <div class="field"><span>Threads</span>
      <span class="stepper">
        <button class="btn icon-btn sm" (click)="set({ threads: Math.max(0, r.threads - 1) })" [disabled]="r.threads <= 0"><app-icon name="minus" [size]="13" /></button>
        <b>{{ r.threads || 'auto' }}</b>
        <button class="btn icon-btn sm" (click)="set({ threads: Math.min(64, r.threads + 1) })" [disabled]="r.threads >= 64"><app-icon name="plus" [size]="13" /></button>
        <span class="hint muted">0 = one per CPU</span>
      </span></div>
  `,
  styles: `
    :host { display: block; }
    .slider { display: inline-flex; align-items: center; gap: 10px; }
    .slider input { width: 200px; }
    .slider b, .stepper b { min-width: 34px; font-weight: 500; font-family: var(--mono); font-size: 12px; text-align: center; }
    select { width: 120px; }
    .stepper { display: inline-flex; align-items: center; gap: 6px; }
    .stepper .hint { margin-left: 6px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RenderEditor {
  readonly render = model.required<Render>();
  protected readonly sizes = SIZES;
  protected readonly Math = Math;
  protected set(p: Partial<Render>) { this.render.update(r => ({ ...r, ...p })); }
}

/** Cover and thumbnail render specs, edited in the shared draft. */
@Component({
  selector: 'app-thumbnails-page',
  imports: [RenderEditor],
  template: `
    <h3>Thumbnails</h3>
    <p class="hint">How covers and photo thumbnails are rendered. Smaller or lower-quality images are faster to make and to show.</p>
    <section class="card">
      <h4>Album covers</h4>
      <p class="hint">Stored in the cover store; WebP keeps them small. Changing size, quality or format re-renders covers on the next scan or view.</p>
      <app-render-editor [render]="draft().covers" (renderChange)="patch({ covers: $event })" />
    </section>
    <section class="card">
      <h4>Photo thumbnails</h4>
      <p class="hint">Rendered on demand while browsing an album, never stored: a fast JPEG is best.</p>
      <app-render-editor [render]="draft().thumbs" (renderChange)="patch({ thumbs: $event })" />
    </section>
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ThumbnailsPage {
  readonly draft = model.required<Settings>();
  protected patch(p: Partial<Settings>) { this.draft.update(d => ({ ...d, ...p })); }
}
