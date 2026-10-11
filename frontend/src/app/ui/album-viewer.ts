import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, input, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { BytesPipe, MegapixelsPipe } from '../core/format';
import { Gateway } from '../core/gateway';
import { Tile } from '../core/layout';
import { AlbumDetails, Entry } from '../core/model';
import { Prefs } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Icon } from './shared/icon';
import { Rating } from './shared/rating';
import { TileGrid } from './tile-grid';

/** Route `/album/:id`: the photos of one album in an equal-size grid, a lightbox, and cover selection. */
@Component({
  selector: 'app-album-viewer',
  imports: [Icon, Rating, TileGrid, BytesPipe, MegapixelsPipe, DatePipe],
  template: `
    <header class="bar">
      <button class="btn icon-btn ghost" (click)="close()" title="Back to library (Esc)"><app-icon name="arrowLeft" /></button>
      @if (album(); as a) {
        <div class="title">
          <h1 [title]="a.name">{{ a.name }}</h1>
          <div class="meta">
            <span>{{ a.imageCount }} photos</span><span>{{ a.bytes | bytes }}</span><span>{{ a.avgPixels | mp }}</span>
            @if (a.model) { <span><app-icon name="user" [size]="12" />{{ a.model }}</span> }
            @if (a.studio) { <span><app-icon name="building" [size]="12" />{{ a.studio }}</span> }
            @if (a.photographer) { <span><app-icon name="camera" [size]="12" />{{ a.photographer }}</span> }
            @if (a.shotDate) { <span><app-icon name="calendar" [size]="12" />{{ a.shotDate | date: 'mediumDate' }}</span> }
            @for (t of a.tags; track t) { <span class="tag">#{{ t }}</span> }
          </div>
        </div>
        <app-rating [value]="a.rating" (valueChange)="store.patch(a.id, { rating: $event })" />
        <button class="btn sm" (click)="store.reveal(a.id)" title="Open the containing folder"><app-icon name="externalLink" [size]="13" /> Show in folder</button>
        <button class="btn icon-btn" [class.active]="prefs.detailsOpen()" (click)="prefs.detailsOpen.set(!prefs.detailsOpen())" title="Details (I)"><app-icon name="panelRight" /></button>
      } @else if (error()) {
        <div class="title"><h1>{{ error() }}</h1></div>
      }
    </header>

    <app-tile-grid class="grid" [tiles]="tiles()" mode="grid" [columns]="prefs.columns()" [gap]="8" [padding]="10"
                   [selectedId]="selected() === null ? null : '' + selected()" (select)="selected.set(+$event)" (open)="lightbox.set(+$event)">
      <ng-template let-e>
        @let a = album()!;
        <div class="photo" [class.cover]="e.name === a.cover" [class.selected]="e.index === selected()" (click)="selected.set(e.index)" (dblclick)="lightbox.set(e.index)">
          <img [src]="gw.thumbUrl(a, e.index)" [alt]="e.name" loading="lazy" decoding="async" draggable="false" />
          @if (e.name === a.cover) { <span class="badge"><app-icon name="cover" [size]="11" /> Cover</span> }
          <div class="actions">
            <button class="act" title="Use as album cover" (click)="setCover(e); $event.stopPropagation()"><app-icon name="cover" [size]="14" /></button>
            <button class="act" title="View (Enter)" (click)="lightbox.set(e.index); $event.stopPropagation()"><app-icon name="maximize" [size]="14" /></button>
          </div>
          <div class="info"><span class="name">{{ e.name }}</span><span class="dims">{{ e.width }}×{{ e.height }} · {{ e.bytes | bytes }}</span></div>
        </div>
      </ng-template>
    </app-tile-grid>

    @if (current(); as e) {
      <div class="lightbox" #lightboxEl tabindex="-1" (click)="lightbox.set(null)">
        <img [src]="gw.originalUrl(album()!, e.index)" [alt]="e.name" [class.actual]="actualSize()" (click)="actualSize.set(!actualSize()); $event.stopPropagation()" [title]="actualSize() ? 'Fit to screen' : 'Actual size'" />
        <button class="nav prev" (click)="step(-1); $event.stopPropagation()" title="Previous (←)"><app-icon name="chevronLeft" [size]="26" /></button>
        <button class="nav next" (click)="step(1); $event.stopPropagation()" title="Next (→)"><app-icon name="chevronRight" [size]="26" /></button>
        <div class="caption" (click)="$event.stopPropagation()">
          <span class="name">{{ e.name }}</span>
          <span class="dim">{{ e.index + 1 }} / {{ tiles().length }} · {{ e.width }}×{{ e.height }} · {{ e.bytes | bytes }}</span>
          <span class="grow"></span>
          <button class="btn sm" (click)="setCover(e)" [disabled]="e.name === album()?.cover"><app-icon name="cover" [size]="13" /> {{ e.name === album()?.cover ? 'Current cover' : 'Use as cover (C)' }}</button>
          <button class="btn icon-btn sm" (click)="lightbox.set(null)" title="Close (Esc)"><app-icon name="x" /></button>
        </div>
      </div>
    }
  `,
  styles: `
    :host { position: fixed; inset: 0; z-index: 30; display: flex; flex-direction: column; background: var(--bg); animation: fade-up 0.18s var(--ease); }
    .bar { display: flex; align-items: center; gap: 10px; min-height: var(--topbar-h); padding: 6px 12px; background: var(--bg-elev); border-bottom: 1px solid var(--border); }
    .title { flex: 1; min-width: 0; }
    h1 { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .meta { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 11.5px; color: var(--text-2); }
    .meta span { display: inline-flex; align-items: center; gap: 4px; }
    .meta .tag { color: var(--accent-strong); }
    .grid { flex: 1; min-height: 0; }
    .photo { position: relative; width: 100%; height: 100%; overflow: hidden; border-radius: 6px; background: var(--surface-2); cursor: pointer; outline: 2px solid transparent; transition: outline-color 0.15s; }
    .photo.selected { outline-color: var(--accent); }
    .photo.cover { box-shadow: 0 0 0 2px var(--accent) inset; }
    .photo img { width: 100%; height: 100%; object-fit: cover; }
    .badge { position: absolute; top: 6px; left: 6px; display: inline-flex; align-items: center; gap: 4px; padding: 2px 7px; border-radius: 5px; background: var(--accent); color: var(--on-accent); font-size: 10.5px; font-weight: 700; }
    .actions { position: absolute; top: 6px; right: 6px; display: flex; gap: 4px; opacity: 0; transition: opacity 0.15s; }
    .photo:hover .actions { opacity: 1; }
    .act { display: inline-flex; width: 26px; height: 26px; align-items: center; justify-content: center; border-radius: 6px; background: oklch(10% 0.01 260 / 0.6); color: #fff; backdrop-filter: blur(6px); }
    .act:hover { background: var(--accent); color: var(--on-accent); }
    .info { position: absolute; left: 0; right: 0; bottom: 0; display: flex; flex-direction: column; padding: 14px 8px 6px; background: var(--scrim); color: #fff; font-size: 11px; line-height: 1.35; pointer-events: none; }
    .info .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .info .dims { opacity: 0.75; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .lightbox { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; background: oklch(6% 0.01 260 / 0.94); animation: fade-up 0.15s var(--ease); overflow: auto; outline: none; }
    .lightbox img { max-width: calc(100vw - 32px); max-height: calc(100vh - 80px); object-fit: contain; cursor: zoom-in; box-shadow: var(--shadow-2); }
    .lightbox img.actual { max-width: none; max-height: none; cursor: zoom-out; }
    .nav { position: fixed; top: 50%; transform: translateY(-50%); width: 48px; height: 72px; display: grid; place-items: center; border-radius: 10px; color: #fff; background: oklch(20% 0.01 260 / 0.5); opacity: 0.6; transition: opacity 0.15s; }
    .nav:hover { opacity: 1; }
    .prev { left: 12px; } .next { right: 12px; }
    .caption { position: fixed; left: 50%; bottom: 14px; transform: translateX(-50%); display: flex; align-items: center; gap: 12px; min-width: min(640px, calc(100vw - 32px)); max-width: calc(100vw - 32px); padding: 8px 10px 8px 14px; border-radius: 10px; background: var(--glass); backdrop-filter: blur(16px); border: 1px solid var(--border); box-shadow: var(--shadow-2); }
    .caption .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .caption .dim { color: var(--text-2); font-size: 12px; white-space: nowrap; }
    .grow { flex: 1; }
    @media (max-width: 760px) { .bar app-rating, .bar .btn.sm { display: none; } }
  `,
  host: { '(document:keydown)': 'onKey($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlbumViewer {
  readonly id = input.required<string>();

  protected readonly gw = inject(Gateway);
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);

  protected readonly details = signal<AlbumDetails | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal<number | null>(null);
  protected readonly lightbox = signal<number | null>(null);
  protected readonly actualSize = signal(false);
  private readonly lightboxEl = viewChild<ElementRef<HTMLElement>>('lightboxEl');

  protected readonly album = computed(() => this.details()?.album ?? null);
  protected readonly tiles = computed<Tile<Entry>[]>(() => (this.details()?.entries ?? []).map(e => ({ id: String(e.index), aspect: e.width && e.height ? e.width / e.height : 1.5, data: e })));
  protected readonly current = computed(() => { const i = this.lightbox(); return i === null ? null : this.details()?.entries[i] ?? null; });

  constructor() {
    effect(() => { const id = this.id(); untracked(() => this.load(id)); });
    // keyboard focus moves into the lightbox so arrows drive it, not the grid underneath
    effect(() => { if (this.lightbox() !== null) this.lightboxEl()?.nativeElement.focus(); });
    this.gw.events.pipe(takeUntilDestroyed()).subscribe(e => {
      // a re-index can renumber entries, so the whole album is reloaded rather than patched
      if (e.type === 'albumChanged' && e.album.id === this.id() && e.album.revision !== this.album()?.revision) this.load(this.id());
      if (e.type === 'albumRemoved' && e.albumId === this.id()) this.close();
    });
  }

  private async load(id: string) {
    const stale = this.details()?.album.id !== id;
    if (stale) { this.details.set(null); this.selected.set(null); this.lightbox.set(null); }
    this.error.set(null);
    try {
      const d = await this.gw.album(id);
      if (this.id() !== id) return;
      this.details.set(d);
      this.store.selectedId.set(id);
      const n = d.entries.length;
      this.selected.update(i => i === null || i < n ? i : null);
      this.lightbox.update(i => i === null || i < n ? i : null);
    } catch (e) { if (this.id() === id) this.error.set(e instanceof Error ? e.message : 'Album not found'); }
  }

  protected close() { this.router.navigate(['/']); }

  protected async setCover(e: Entry) {
    const a = this.album();
    if (a && await this.store.patch(a.id, { cover: e.name })) this.toasts.show(`Cover set to ${e.name}`, 'success');
  }

  protected step(delta: number) {
    const n = this.tiles().length, i = this.lightbox();
    if (n && i !== null) { this.lightbox.set((i + delta + n) % n); this.actualSize.set(false); }
  }

  protected onKey(e: KeyboardEvent) {
    if (e.ctrlKey || e.metaKey || e.altKey || (e.target as HTMLElement)?.closest('input, textarea, select')) return;
    const open = this.lightbox() !== null;
    switch (e.key) {
      case 'Escape': open ? this.lightbox.set(null) : this.close(); break;
      case 'ArrowRight': if (open) this.step(1); else return; break;
      case 'ArrowLeft': if (open) this.step(-1); else return; break;
      case 'c': case 'C': { const cur = this.current() ?? (this.selected() !== null ? this.details()?.entries[this.selected()!] : null); if (cur) this.setCover(cur); else return; break; }
      default: return;
    }
    e.preventDefault();
    e.stopPropagation();
  }
}
