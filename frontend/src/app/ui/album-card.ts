import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { BytesPipe, MegapixelsPipe } from '../core/format';
import { Gateway } from '../core/gateway';
import { Album } from '../core/model';
import { Icon } from './shared/icon';

/** One album in the grid: cover, permanent name/size label, hover metadata and quick actions. */
@Component({
  selector: 'app-album-card',
  imports: [Icon, BytesPipe, MegapixelsPipe, DatePipe],
  template: `
    @let a = album();
    <img [src]="gw.coverUrl(a)" [alt]="a.name" loading="lazy" decoding="async" draggable="false" [class.loaded]="loaded()" (load)="loaded.set(true)" />
    <div class="badges">
      <span class="badge" [title]="a.kind === 'DIRECTORY' ? 'Folder' : a.kind === 'ZIP' ? 'Zip archive' : 'Archive'"><app-icon [name]="a.kind === 'DIRECTORY' ? 'folder' : 'archive'" [size]="12" /></span>
      @if (a.rating) { <span class="badge rating"><app-icon name="star" [size]="10" />{{ a.rating }}</span> }
    </div>
    <div class="actions">
      <button class="act" title="Details (I)" (click)="info.emit(); $event.stopPropagation()"><app-icon name="info" [size]="15" /></button>
      <button class="act" title="Open album (Enter)" (click)="open.emit(); $event.stopPropagation()"><app-icon name="maximize" [size]="15" /></button>
    </div>
    <div class="scrim">
      <div class="meta">
        @if (a.model || a.studio) {
          <div class="line"><app-icon name="user" [size]="12" /><span>{{ a.model || '—' }}@if (a.studio) { <span class="dim"> · {{ a.studio }}</span> }</span></div>
        }
        @if (a.photographer) { <div class="line"><app-icon name="camera" [size]="12" /><span>{{ a.photographer }}</span></div> }
        @if (a.shotDate) { <div class="line"><app-icon name="calendar" [size]="12" /><span>{{ a.shotDate | date: 'mediumDate' }}</span></div> }
        @if (showTags() && a.tags.length) {
          <div class="tags">@for (t of a.tags; track t) { <span class="tag">#{{ t }}</span> }</div>
        }
      </div>
      <div class="title" [title]="a.name">{{ a.name }}</div>
      <div class="sub">{{ a.imageCount }} photos · {{ a.bytes | bytes }} · {{ a.avgPixels | mp }}</div>
    </div>
  `,
  styles: `
    :host {
      display: block; position: relative; width: 100%; height: 100%; overflow: hidden; cursor: pointer;
      border-radius: var(--radius); background: var(--tint); box-shadow: var(--shadow-1);
      transition: box-shadow 0.2s, outline-color 0.2s; outline: 2px solid transparent; outline-offset: 0;
    }
    :host(:hover) { box-shadow: var(--shadow-2); }
    :host(.selected) { outline-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft), var(--shadow-2); }
    img { width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity 0.4s, transform 0.6s var(--ease); }
    img.loaded { opacity: 1; }
    :host(:hover) img { transform: scale(1.035); }
    .badges { position: absolute; top: 8px; left: 8px; display: flex; gap: 4px; }
    .badge {
      display: inline-flex; align-items: center; gap: 3px; height: 20px; padding: 0 6px; border-radius: 6px;
      background: oklch(10% 0.01 260 / 0.55); color: oklch(95% 0 0); font-size: 11px; font-weight: 600;
      backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px);
    }
    .badge.rating { color: var(--accent); }
    .actions { position: absolute; top: 8px; right: 8px; display: flex; gap: 4px; opacity: 0; transform: translateY(-4px); transition: opacity 0.15s, transform 0.15s; }
    :host(:hover) .actions, :host(.selected) .actions { opacity: 1; transform: none; }
    .act {
      display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 6px;
      background: oklch(10% 0.01 260 / 0.6); color: oklch(95% 0 0); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px);
    }
    .act:hover { background: var(--accent); color: var(--on-accent); }
    .scrim { position: absolute; inset: 0; display: flex; flex-direction: column; justify-content: flex-end; padding: 9px 10px; background: var(--scrim); pointer-events: none; color: oklch(97% 0 0); }
    .title { font-weight: 600; font-size: 12.5px; line-height: 1.3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-shadow: 0 1px 2px oklch(0% 0 0 / 0.5); }
    .sub { font-size: 11px; opacity: 0.78; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .meta { max-height: 0; opacity: 0; overflow: hidden; transition: max-height 0.25s var(--ease), opacity 0.2s; font-size: 11.5px; line-height: 1.5; }
    :host(:hover) .meta, :host(.selected) .meta { max-height: 140px; opacity: 1; margin-bottom: 4px; }
    .line { display: flex; align-items: center; gap: 5px; white-space: nowrap; overflow: hidden; }
    .line span { overflow: hidden; text-overflow: ellipsis; }
    .dim { opacity: 0.7; }
    .tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }
    .tag { font-size: 10.5px; padding: 0 6px; border-radius: 999px; background: oklch(100% 0 0 / 0.16); }
    :host(.portrait) .meta { font-size: 11px; }
  `,
  host: {
    '[class.selected]': 'selected()', '[class.portrait]': 'album().coverPortrait', '[style.--tint]': 'tint()',
    '(click)': 'select.emit()', '(dblclick)': 'open.emit()', '(mouseenter)': 'hover.emit(album().id)', '(mouseleave)': 'hover.emit(null)',
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlbumCard {
  readonly album = input.required<Album>();
  readonly selected = input(false);
  readonly showTags = input(true);
  readonly select = output<void>();
  readonly open = output<void>();
  readonly info = output<void>();
  readonly hover = output<string | null>();

  protected readonly gw = inject(Gateway);
  protected readonly loaded = signal(false);
  /** Placeholder colour while the cover loads, stable per album. */
  protected readonly tint = computed(() => {
    let h = 0;
    for (const c of this.album().id) h = (h * 31 + c.charCodeAt(0)) >>> 0;
    return `oklch(28% 0.05 ${h % 360})`;
  });
}
