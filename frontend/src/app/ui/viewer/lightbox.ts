import { afterNextRender, ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { BytesPipe } from '../../core/format';
import { Gateway } from '../../core/gateway';
import { Album, Entry, PhotoMark } from '../../core/model';
import { ViewerScale } from '../../core/prefs';
import { Toasts } from '../../core/toasts';
import { Icon } from '../shared/icon';
import { Rating } from '../shared/rating';
import { SCALE_LABELS, scaleBox, Size } from './photo-order';

/**
 * One photo over the viewer: the image sized per scale mode (`scaleBox` from the entry's dimensions and the stage), panned by scroll or drag when
 * larger, a click toggles fit ↔ 1:1; prev/next; a caption with rating, heart, copy-to keys, metadata, scale, slideshow, full screen and cover.
 * The host is the full-screen element (`toggleFullscreen()`); toasts are mirrored while full screen (the app's sit behind the top layer).
 */
@Component({
  selector: 'app-lightbox',
  imports: [Icon, Rating, BytesPipe],
  template: `
    <div #stage class="stage" [class.pan]="pannable()" (click)="$event.target === stage && close.emit()">
      <img [src]="gw.originalUrl(album(), entry().index)" [alt]="entry().name" [class.zoom-in]="scale() !== 'actual'" [class.zoom-out]="scale() === 'actual'"
           [style.width.px]="box()?.width ?? null" [style.height.px]="box()?.height ?? null" draggable="false" decoding="async" (load)="loaded($any($event.target))"
           (pointerdown)="down($event)" (pointermove)="move($event)" (pointerup)="up($event)" (pointercancel)="up($event)" (click)="clicked($event)" />
    </div>
    @if (count() > 1) {
      <button class="nav prev" (click)="step.emit(-1)" title="Previous (←)"><app-icon name="chevronLeft" [size]="26" /></button>
      <button class="nav next" (click)="step.emit(1)" title="Next (→)"><app-icon name="chevronRight" [size]="26" /></button>
    }
    @if (slideshow()) { @for (k of [entry().index]; track k) { <div class="progress" [style.animation-duration.s]="seconds()"></div> } }
    <div class="caption" (click)="$event.stopPropagation()">
      <span class="name" [title]="entry().name">{{ entry().name }}</span>
      <span class="dim">{{ position() + 1 }} / {{ count() }} · {{ entry().width }}×{{ entry().height }} · {{ entry().bytes | bytes }}</span>
      <span class="grow"></span>
      <app-rating [compact]="true" [value]="mark()?.rating ?? 0" (valueChange)="rate.emit($event)" title="Rating (Ctrl+1…0)" />
      <button class="btn icon-btn sm ghost heart" [class.on]="mark()?.lists?.length" (click)="favorites.emit($event)" [title]="(mark()?.lists?.length ? 'In ' + mark()!.lists.length + ' favorites list(s)' : 'Add to favorites') + ' (F)'"><app-icon name="heart" [size]="15" /></button>
      @if (targets().length) {
        <span class="keys" title="Copy to target (1–0)">@for (t of targets(); track $index) { <button class="key" (click)="copyTo.emit($index)" [title]="'Copy to ' + t + ' (' + (($index + 1) % 10) + ')'">{{ ($index + 1) % 10 }}</button> }</span>
      }
      <button class="btn icon-btn sm ghost" [disabled]="!metaOk()" (click)="meta.emit()" title="Metadata (M)"><app-icon name="info" [size]="15" /></button>
      <button class="btn sm ghost mode" (click)="scaleMenu.emit($event)" title="Scale mode (S cycles)">{{ labels[scale()] }}<app-icon name="chevronDown" [size]="11" /></button>
      <button class="btn icon-btn sm ghost" [class.active]="slideshow()" (click)="slideshowToggle.emit()" [title]="(slideshow() ? 'Pause slideshow' : 'Slideshow, ' + seconds() + ' s per photo') + ' (Space)'"><app-icon [name]="slideshow() ? 'minus' : 'play'" [size]="15" /></button>
      <button class="btn icon-btn sm ghost" [class.active]="fullscreen()" (click)="toggleFullscreen()" title="Full screen (F11)"><app-icon name="maximize" [size]="15" /></button>
      <button class="btn icon-btn sm ghost" [disabled]="isCover()" (click)="cover.emit()" [title]="isCover() ? 'Current cover' : 'Use as cover (C)'"><app-icon name="cover" [size]="15" /></button>
      <button class="btn icon-btn sm ghost" (click)="close.emit()" title="Close (Esc)"><app-icon name="x" /></button>
    </div>
    @if (fullscreen()) {
      <div class="toasts">@for (t of toasts.list(); track t.id) { <div class="toast" [class]="t.kind" (click)="toasts.dismiss(t.id)">{{ t.text }}</div> }</div>
    }
  `,
  styles: `
    :host { position: fixed; inset: 0; z-index: 50; background: oklch(6% 0.01 260 / 0.94); animation: fade-up 0.15s var(--ease); outline: none; }
    :host:fullscreen { background: #000; }
    .stage { position: absolute; inset: 0; display: flex; overflow: auto; overscroll-behavior: contain; scrollbar-width: none; }
    .stage::-webkit-scrollbar { display: none; }
    img { margin: auto; flex: none; max-width: 100%; max-height: 100%; box-shadow: var(--shadow-2); user-select: none; touch-action: none; }
    img[style] { max-width: none; max-height: none; }
    img.zoom-in { cursor: zoom-in; } img.zoom-out { cursor: zoom-out; }
    .stage.pan img { cursor: grab; } .stage.pan.dragging img { cursor: grabbing; }
    .nav { position: fixed; top: 50%; transform: translateY(-50%); width: 48px; height: 72px; display: grid; place-items: center; border-radius: 10px; color: #fff; background: oklch(20% 0.01 260 / 0.5); opacity: 0.6; transition: opacity 0.15s; }
    .nav:hover { opacity: 1; }
    .prev { left: 12px; } .next { right: 12px; }
    .progress { position: fixed; left: 0; top: 0; height: 2px; width: 0; background: var(--accent); animation: grow linear forwards; }
    @keyframes grow { from { width: 0; } to { width: 100%; } }
    .caption { position: fixed; left: 50%; bottom: 14px; transform: translateX(-50%); display: flex; align-items: center; gap: 6px; min-width: min(640px, calc(100vw - 32px)); max-width: calc(100vw - 32px); padding: 6px 8px 6px 14px; border-radius: 10px; background: var(--glass); backdrop-filter: blur(16px); border: 1px solid var(--border); box-shadow: var(--shadow-2); opacity: 0.96; transition: opacity 0.2s; }
    :host:fullscreen .caption { opacity: 0.25; } :host:fullscreen .caption:hover { opacity: 1; }
    .caption .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 260px; }
    .caption .dim { color: var(--text-2); font-size: 12px; white-space: nowrap; }
    .caption app-rating { margin: 0 4px; }
    .grow { flex: 1; }
    .heart.on { color: var(--danger); }
    .keys { display: inline-flex; gap: 2px; }
    .key { min-width: 18px; height: 20px; padding: 0 4px; border-radius: 4px; border: 1px solid var(--border-strong); background: var(--surface-2); font: 11px/18px var(--mono); color: var(--text-2); }
    .key:hover { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
    .mode { gap: 3px; padding: 0 6px; font-size: 11.5px; }
    .toasts { position: fixed; left: 50%; bottom: 70px; z-index: 60; display: flex; flex-direction: column; gap: 6px; transform: translateX(-50%); pointer-events: none; }
    .toast { padding: 8px 14px; border-radius: var(--radius); background: var(--glass); backdrop-filter: blur(12px); border: 1px solid var(--border); box-shadow: var(--shadow-2); font-size: 12.5px; pointer-events: auto; cursor: pointer; animation: fade-up 0.2s var(--ease); }
    .toast.success { border-color: color-mix(in oklch, var(--success) 50%, transparent); }
    .toast.error { border-color: color-mix(in oklch, var(--danger) 60%, transparent); color: var(--danger); }
    @media (max-width: 760px) { .caption .dim, .caption .keys, .caption .mode { display: none; } }
  `,
  host: { tabindex: '-1', '(document:fullscreenchange)': 'onFullscreen()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Lightbox {
  readonly album = input.required<Album>();
  readonly entry = input.required<Entry>();
  /** Position of `entry` in the viewer order, and that order's length. */
  readonly position = input.required<number>();
  readonly count = input.required<number>();
  readonly mark = input<PhotoMark | null>(null);
  readonly scale = input.required<ViewerScale>();
  readonly slideshow = input(false);
  readonly seconds = input(5);
  readonly targets = input<string[]>([]);
  readonly isCover = input(false);
  readonly metaOk = input(true);
  /** URL of the photo that follows, fetched ahead so stepping and the slideshow do not wait. */
  readonly preload = input<string | null>(null);

  readonly close = output<void>();
  readonly step = output<number>();
  /** A click on the photo: fit ↔ 1:1. */
  readonly toggleScale = output<void>();
  readonly scaleMenu = output<MouseEvent>();
  readonly rate = output<number>();
  readonly favorites = output<MouseEvent>();
  readonly copyTo = output<number>();
  readonly meta = output<void>();
  readonly cover = output<void>();
  readonly slideshowToggle = output<void>();

  protected readonly gw = inject(Gateway);
  protected readonly toasts = inject(Toasts);
  protected readonly labels = SCALE_LABELS;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly stage = viewChild.required<ElementRef<HTMLElement>>('stage');

  protected readonly fullscreen = signal(!!document.fullscreenElement);
  /** The photo's pixel size: the index knows it; a photo it could not probe is measured on load. */
  private readonly natural = signal<Size | null>(null);
  private readonly viewport = signal<Size>({ width: 0, height: 0 });
  protected readonly box = computed(() => { const n = this.natural(), v = this.viewport(); return n && v.width ? scaleBox(this.scale(), n, v) : null; });
  protected readonly pannable = computed(() => { const b = this.box(), v = this.viewport(); return !!b && (b.width > v.width || b.height > v.height); });
  private drag: { x: number; y: number; left: number; top: number; moved: boolean } | null = null;

  constructor() {
    effect(() => { const e = this.entry(); this.natural.set(e.width && e.height ? { width: e.width, height: e.height } : null); });
    effect(() => { const u = this.preload(); if (u) new Image().src = u; });
    const ro = new ResizeObserver(() => { const s = this.stage().nativeElement; this.viewport.set({ width: s.clientWidth, height: s.clientHeight }); });
    afterNextRender(() => { ro.observe(this.stage().nativeElement); this.host.focus({ preventScroll: true }); }); // keys go to the lightbox, not the grid beneath
    inject(DestroyRef).onDestroy(() => { ro.disconnect(); if (document.fullscreenElement === this.host) void document.exitFullscreen(); });
  }

  protected onFullscreen() { this.fullscreen.set(!!document.fullscreenElement); }

  toggleFullscreen() {
    if (document.fullscreenElement) void document.exitFullscreen();
    else this.host.requestFullscreen().catch(e => this.toasts.error(e, 'Full screen: '));
  }

  protected loaded(img: HTMLImageElement) {
    const n = this.natural();
    if (img.naturalWidth && (!n || n.width !== img.naturalWidth || n.height !== img.naturalHeight)) this.natural.set({ width: img.naturalWidth, height: img.naturalHeight });
  }

  protected down(e: PointerEvent) {
    if (e.button !== 0 || !this.pannable()) return;
    const s = this.stage().nativeElement;
    this.drag = { x: e.clientX, y: e.clientY, left: s.scrollLeft, top: s.scrollTop, moved: false };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    s.classList.add('dragging');
  }

  protected move(e: PointerEvent) {
    const d = this.drag;
    if (!d) return;
    const dx = e.clientX - d.x, dy = e.clientY - d.y, s = this.stage().nativeElement;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) d.moved = true;
    s.scrollLeft = d.left - dx; s.scrollTop = d.top - dy;
  }

  protected up(e: PointerEvent) {
    if (!this.drag) return;
    (e.target as HTMLElement).releasePointerCapture?.(e.pointerId);
    this.stage().nativeElement.classList.remove('dragging');
    // the click that follows a drag is swallowed by `clicked`, then the drag state is cleared
    if (!this.drag.moved) this.drag = null;
  }

  protected clicked(e: MouseEvent) {
    e.stopPropagation();
    if (this.drag?.moved) { this.drag = null; return; }
    this.toggleScale.emit();
  }
}
