import { DatePipe } from '@angular/common';
import { afterNextRender, ChangeDetectionStrategy, Component, computed, effect, inject, Injector, input, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { CatalogStore } from '../core/catalog-store';
import { BytesPipe, MegapixelsPipe } from '../core/format';
import { Gateway } from '../core/gateway';
import { Tile } from '../core/layout';
import { AlbumDetails, Entry, ENTRY_META, ENTRY_META_KNOWN, EntityKind, MetaRow, Op, PhotoMark } from '../core/model';
import { OpsStore } from '../core/ops-store';
import { lastSegment } from '../core/paths';
import { Prefs, VIEWER_SCALES, VIEWER_SORTS, ViewerScale, ViewerSort } from '../core/prefs';
import { Selection } from '../core/selection';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Confirm } from './shared/confirm';
import { ContextMenu, MenuEntry } from './shared/context-menu';
import { EntityPicker } from './shared/entity-picker';
import { Icon, IconName } from './shared/icon';
import { Rating } from './shared/rating';
import { TileGrid } from './tile-grid';
import { Lightbox } from './viewer/lightbox';
import { MetaDialog } from './viewer/meta-dialog';
import { BYTE_PRESETS, filter, nextScale, order, SCALE_LABELS, SIDE_PRESETS, SORT_DESC_FIRST, SORT_LABELS } from './viewer/photo-order';

const ROLE_ICONS: [EntityKind, IconName][] = [['MODEL', 'user'], ['STUDIO', 'building'], ['PHOTOGRAPHER', 'camera']];
/** Digit keys `1`…`9`,`0` → target / rating slot 0…9. */
const slot = (key: string) => /^[0-9]$/.test(key) ? (+key + 9) % 10 : -1;

/**
 * Route `/album/:id`: the album's photos in viewer order (sort, size filter), a multi-selection by entry name, per-photo marks (rating, favorites),
 * copy-to targets, metadata and bio text, and the lightbox. One keyboard handler (`onKey`) serves grid and lightbox; `App.onKey` yields on viewer routes.
 */
@Component({
  selector: 'app-album-viewer',
  imports: [Icon, Rating, TileGrid, Lightbox, MetaDialog, BytesPipe, MegapixelsPipe, DatePipe],
  template: `
    <header class="bar">
      <button class="btn icon-btn ghost" (click)="close()" title="Back to library (Esc)"><app-icon name="arrowLeft" /></button>
      @if (album(); as a) {
        <div class="title">
          <h1 [title]="a.name">{{ a.name }}</h1>
          <div class="meta">
            <span>{{ shown().length === a.imageCount ? a.imageCount : shown().length + ' of ' + a.imageCount }} photos</span><span>{{ a.bytes | bytes }}</span><span>{{ a.avgPixels | mp }}</span>
            @for (r of roles; track r[0]) { @for (l of links()[r[0]] ?? []; track l.entity) { <button class="link" (click)="catalog.openCard(l.entity); prefs.detailsOpen.set(true)" [title]="'Show ' + l.name"><app-icon [name]="r[1]" [size]="12" />{{ l.name }}</button> } }
            @if (a.shotDate) { <span><app-icon name="calendar" [size]="12" />{{ a.shotDate | date: 'mediumDate' }}</span> }
            @for (t of a.tags; track t) { <span class="tag">#{{ t }}</span> }
          </div>
        </div>
        @if (sel.size() > 1) { <button class="chip active" (click)="sel.clear()" title="Deselect (Esc)">{{ sel.size() }} selected <app-icon name="x" [size]="12" /></button> }
        @if (a.bio) { <button class="btn icon-btn" [class.active]="bioOpen()" (click)="toggleBio()" title="Bio text (B)"><app-icon name="edit" [size]="16" /></button> }
        <button class="btn sm" (click)="sortMenu($event)" title="Sort order"><app-icon [name]="desc() ? 'sortDesc' : 'sortAsc'" [size]="14" />{{ sortLabels[prefs.viewerSort()] }}</button>
        <span class="anchor">
          <button class="btn icon-btn" [class.active]="filterOpen() || hidden() > 0" (click)="filterOpen.set(!filterOpen())" title="Minimum size"><app-icon name="filter" [size]="15" />@if (hidden()) { <span class="count">{{ hidden() }}</span> }</button>
          @if (filterOpen()) {
            <div class="pop panel">
              <div class="row"><span>Longer side ≥</span><span class="segmented">@for (p of sides; track p) { <button [class.active]="prefs.viewerMinSide() === p" (click)="prefs.viewerMinSide.set(p)">{{ p || 'any' }}</button> }</span></div>
              <div class="row"><span>File size ≥</span><span class="segmented">@for (p of byteSteps; track p) { <button [class.active]="minBytes() === p" (click)="minBytes.set(p)">{{ p ? (p | bytes: 0) : 'any' }}</button> }</span></div>
              <div class="row dim">{{ shown().length }} of {{ entries().length }} shown</div>
            </div>
          }
        </span>
        <button class="btn sm" (click)="scaleMenu($event)" title="Lightbox scale (S cycles)"><app-icon name="zoom" [size]="14" />{{ scaleLabels[prefs.viewerScale()] }}</button>
        <button class="btn icon-btn" (click)="fullscreen()" title="Full screen (F11)"><app-icon name="maximize" [size]="16" /></button>
        <app-rating [value]="a.rating" (valueChange)="store.patch(a.id, { rating: $event })" />
        <button class="btn icon-btn" (click)="store.reveal(a.id)" title="Show in folder"><app-icon name="externalLink" [size]="15" /></button>
        <button class="btn icon-btn" [class.active]="prefs.detailsOpen()" (click)="prefs.detailsOpen.set(!prefs.detailsOpen())" title="Details (I)"><app-icon name="panelRight" /></button>
      } @else if (error()) {
        <div class="title"><h1>{{ error() }}</h1></div>
      }
    </header>

    <div class="body">
      <app-tile-grid class="grid" [tiles]="tiles()" mode="grid" [columns]="prefs.columns()" [gap]="8" [padding]="10" [empty]="emptyState"
                     [selectedId]="selected() === null ? null : '' + selected()" (select)="focus(+$event)" (open)="openLightbox(+$event)">
        <ng-template let-e>
          @let a = album()!;
          @let m = marks()[e.name];
          <div class="photo" [class.cover]="e.name === a.cover" [class.selected]="e.index === selected()" [class.checked]="sel.has(e.name)"
               (click)="pick(e, $event)" (dblclick)="openLightbox(e.index)" (contextmenu)="photoMenu(e, $event)">
            <img [src]="gw.thumbUrl(a, e.index)" [alt]="e.name" loading="lazy" decoding="async" draggable="false" />
            <div class="badges">
              @if (e.name === a.cover) { <span class="badge"><app-icon name="cover" [size]="11" /> Cover</span> }
              @if (m?.rating) { <span class="badge soft">★ {{ m.rating }}</span> }
              @if (m?.lists?.length) { <span class="badge soft heart" [title]="'In ' + m.lists.length + ' favorites list(s)'"><app-icon name="heart" [size]="11" /></span> }
            </div>
            @if (sel.size() > 1) { <span class="check" [class.on]="sel.has(e.name)"><app-icon name="check" [size]="12" /></span> }
            <div class="actions">
              <button class="act" title="Use as album cover (C)" (click)="setCover(e); $event.stopPropagation()"><app-icon name="cover" [size]="14" /></button>
              <button class="act" title="View (Enter)" (click)="openLightbox(e.index); $event.stopPropagation()"><app-icon name="maximize" [size]="14" /></button>
              <button class="act" [class.on]="m?.lists?.length" title="Favorites (F)" (click)="favoritesMenu($event, e); $event.stopPropagation()"><app-icon name="heart" [size]="14" /></button>
            </div>
            <div class="info">
              <span class="name">{{ e.name }}</span>
              <span class="row"><span class="dims">{{ e.width }}×{{ e.height }} · {{ e.bytes | bytes }}</span><app-rating class="rate" [compact]="true" [value]="m?.rating ?? 0" (valueChange)="rate(namesFor(e), $event)" /></span>
            </div>
          </div>
        </ng-template>
      </app-tile-grid>
      <ng-template #emptyState>@if (album()) { <div class="empty"><app-icon name="filter" [size]="28" /><p>No photo passes the size filter.</p><button class="btn sm" (click)="prefs.viewerMinSide.set(0); minBytes.set(0)">Show all</button></div> }</ng-template>

      @if (bioOpen()) {
        <aside class="bio">
          <header><app-icon name="edit" [size]="14" /><span [title]="album()?.bio">{{ album()?.bio }}</span>
            <button class="btn icon-btn sm ghost" [disabled]="!bioText()" (click)="copyText(bioText()!, 'Bio copied')" title="Copy"><app-icon name="copy" [size]="13" /></button>
            <button class="btn icon-btn sm ghost" (click)="bioOpen.set(false)" title="Close (B)"><app-icon name="x" /></button></header>
          @if (bioText(); as t) { <pre>{{ t }}</pre> } @else { <div class="loading"><span class="spinner"></span></div> }
        </aside>
      }
    </div>

    @if (current(); as e) {
      <app-lightbox [album]="album()!" [entry]="e" [position]="position()" [count]="shown().length" [mark]="marks()[e.name] ?? null" [scale]="scale()" [slideshow]="slideshow()"
                    [seconds]="prefs.slideshowSeconds()" [targets]="targets()" [isCover]="e.name === album()!.cover" [metaOk]="metaOk(e)" [preload]="preloadUrl()"
                    (close)="closeLightbox()" (step)="step($event)" (toggleScale)="zoom.set(scale() === 'actual' ? 'fit' : 'actual')" (scaleMenu)="scaleMenu($event)"
                    (rate)="rate([e.name], $event)" (favorites)="favoritesMenu($event, e)" (copyTo)="copyTo([e.name], $event)" (meta)="showMeta(e)" (cover)="setCover(e)" (slideshowToggle)="toggleSlideshow()" />
    }
    @if (meta(); as m) { <app-meta-dialog [title]="m.title" [rows]="m.rows" (close)="meta.set(null)" /> }
  `,
  styles: `
    :host { position: fixed; inset: 0; z-index: 30; display: flex; flex-direction: column; background: var(--bg); animation: fade-up 0.18s var(--ease); }
    .bar { display: flex; align-items: center; gap: 8px; min-height: var(--topbar-h); padding: 6px 12px; background: var(--bg-elev); border-bottom: 1px solid var(--border); }
    .title { flex: 1; min-width: 0; }
    h1 { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .meta { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 11.5px; color: var(--text-2); }
    .meta span, .meta .link { display: inline-flex; align-items: center; gap: 4px; }
    .meta .link:hover { color: var(--accent-strong); text-decoration: underline; }
    .meta .tag { color: var(--accent-strong); }
    .bar .btn.sm { gap: 5px; }
    .anchor { position: relative; display: inline-flex; }
    .pop { position: absolute; top: calc(100% + 6px); right: 0; z-index: 40; display: flex; flex-direction: column; gap: 8px; padding: 10px 12px; border-radius: var(--radius); font-size: 12px; white-space: nowrap; animation: fade-up 0.15s var(--ease); }
    .pop .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    .pop .row > span:first-child { color: var(--text-2); }
    .pop .dim { color: var(--text-3); font-size: 11.5px; }
    .body { position: relative; flex: 1; min-height: 0; display: flex; }
    .grid { flex: 1; min-width: 0; min-height: 0; }
    .empty { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--text-3); }
    .bio { flex: none; width: min(380px, 40vw); display: flex; flex-direction: column; border-left: 1px solid var(--border); background: var(--bg-elev); animation: fade-up 0.15s var(--ease); }
    .bio header { display: flex; align-items: center; gap: 8px; flex: none; height: 38px; padding: 0 6px 0 12px; border-bottom: 1px solid var(--border); font-size: 12px; }
    .bio header span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .bio pre { flex: 1; margin: 0; padding: 12px 14px; overflow: auto; font: 12px/1.5 var(--mono); white-space: pre-wrap; overflow-wrap: anywhere; color: var(--text); }
    .bio .loading { display: flex; justify-content: center; padding: 20px; }
    .photo { position: relative; width: 100%; height: 100%; overflow: hidden; border-radius: 6px; background: var(--surface-2); cursor: pointer; outline: 2px solid transparent; transition: outline-color 0.15s; }
    .photo.selected { outline-color: var(--accent); }
    .photo.checked { outline-color: var(--accent); outline-offset: -2px; }
    .photo.checked img { opacity: 0.85; }
    .photo.cover { box-shadow: 0 0 0 2px var(--accent) inset; }
    .photo img { width: 100%; height: 100%; object-fit: cover; }
    .badges { position: absolute; top: 6px; left: 6px; display: flex; gap: 4px; }
    .badge { display: inline-flex; align-items: center; gap: 4px; height: 18px; padding: 0 7px; border-radius: 5px; background: var(--accent); color: var(--on-accent); font-size: 10.5px; font-weight: 700; }
    .badge.soft { background: oklch(10% 0.01 260 / 0.6); color: #fff; backdrop-filter: blur(6px); }
    .badge.heart { padding: 0 5px; color: var(--danger); }
    .check { position: absolute; bottom: 42px; right: 8px; display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; border: 2px solid #fff; background: oklch(10% 0.01 260 / 0.5); color: transparent; box-shadow: var(--shadow-1); }
    .check.on { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
    .actions { position: absolute; top: 6px; right: 6px; display: flex; gap: 4px; opacity: 0; transition: opacity 0.15s; }
    .photo:hover .actions { opacity: 1; }
    .act { display: inline-flex; width: 26px; height: 26px; align-items: center; justify-content: center; border-radius: 6px; background: oklch(10% 0.01 260 / 0.6); color: #fff; backdrop-filter: blur(6px); }
    .act:hover { background: var(--accent); color: var(--on-accent); }
    .act.on { color: var(--danger); }
    .info { position: absolute; left: 0; right: 0; bottom: 0; display: flex; flex-direction: column; padding: 14px 8px 6px; background: var(--scrim); color: #fff; font-size: 11px; line-height: 1.35; pointer-events: none; }
    .info .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .info .row { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
    .info .dims { opacity: 0.75; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .info .rate { pointer-events: auto; opacity: 0; transition: opacity 0.15s; flex: none; }
    .photo:hover .info .rate { opacity: 1; }
    @media (max-width: 1100px) { .bar app-rating, .bar .btn.sm { display: none; } }
    @media (max-width: 760px) { .bio { position: absolute; inset: 0 0 0 auto; z-index: 5; box-shadow: var(--shadow-2); } }
  `,
  host: { '(document:keydown)': 'onKey($event)', '(document:pointerdown)': 'onDocDown($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlbumViewer {
  readonly id = input.required<string>();
  /** `?photo=<index>`: the lightbox opens on that entry once the album is in (a list's photo grid lands here). */
  readonly photo = input<string>();

  protected readonly gw = inject(Gateway);
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  protected readonly catalog = inject(CatalogStore);
  private readonly ops = inject(OpsStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly menu = inject(ContextMenu);
  private readonly confirm = inject(Confirm);
  private readonly picker = inject(EntityPicker);
  private readonly injector = inject(Injector);
  private readonly lightboxCmp = viewChild(Lightbox);

  protected readonly roles = ROLE_ICONS;
  protected readonly sortLabels = SORT_LABELS;
  protected readonly scaleLabels = SCALE_LABELS;
  protected readonly sides = SIDE_PRESETS;
  protected readonly byteSteps = BYTE_PRESETS;

  protected readonly details = signal<AlbumDetails | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Focused entry index (keyboard target, outline); the multi-selection `sel` is keyed by entry name. */
  protected readonly selected = signal<number | null>(null);
  protected readonly sel = new Selection();
  /** Entry index shown in the lightbox (stable across re-sorts). */
  protected readonly lightbox = signal<number | null>(null);
  protected readonly desc = this.prefs.viewerSortDesc;
  protected readonly seed = signal(Date.now());
  protected readonly minBytes = signal(0);
  protected readonly filterOpen = signal(false);
  protected readonly bioOpen = signal(false);
  protected readonly bioText = signal<string | null>(null);
  protected readonly meta = signal<{ title: string; rows: MetaRow[] | null } | null>(null);
  protected readonly slideshow = signal(false);
  /** A click in the lightbox toggles fit ↔ 1:1 for this photo only; the preference stays. */
  protected readonly zoom = signal<ViewerScale | null>(null);

  protected readonly album = computed(() => this.details()?.album ?? null);
  protected readonly entries = computed(() => this.details()?.entries ?? []);
  protected readonly marks = computed(() => this.details()?.marks ?? {});
  protected readonly shown = computed(() => order(filter(this.entries(), this.prefs.viewerMinSide(), this.minBytes()), this.prefs.viewerSort(), this.desc(), this.marks(), this.seed()));
  protected readonly hidden = computed(() => this.entries().length - this.shown().length);
  protected readonly tiles = computed<Tile<Entry>[]>(() => this.shown().map(e => ({ id: String(e.index), aspect: e.width && e.height ? e.width / e.height : 1.5, data: e })));
  protected readonly names = computed(() => this.shown().map(e => e.name));
  protected readonly current = computed(() => { const i = this.lightbox(); return i === null ? null : this.entries()[i] ?? null; });
  protected readonly position = computed(() => this.shown().findIndex(e => e.index === this.lightbox()));
  protected readonly preloadUrl = computed(() => { const s = this.shown(), p = this.position(), a = this.album(); return a && p >= 0 && s.length > 1 ? this.gw.originalUrl(a, s[(p + 1) % s.length].index) : null; });
  protected readonly scale = computed(() => this.zoom() ?? this.prefs.viewerScale());
  protected readonly targets = computed(() => this.store.settings()?.settings.copyTargets ?? []);
  protected readonly links = computed(() => { const by: Partial<Record<EntityKind, { entity: number; name: string }[]>> = {}; for (const l of this.album()?.links ?? []) (by[l.role] ??= []).push(l); return by; });

  constructor() {
    effect(() => { const id = this.id(); untracked(() => this.load(id)); });
    effect(onCleanup => { // slideshow: one timer per shown photo, restarted by every step
      const i = this.lightbox(), secs = this.prefs.slideshowSeconds();
      if (!this.slideshow() || i === null) return;
      const t = setTimeout(() => untracked(() => this.step(1)), Math.max(1, secs) * 1000);
      onCleanup(() => clearTimeout(t));
    });
    this.gw.events.pipe(takeUntilDestroyed()).subscribe(e => {
      // a re-index can renumber entries, so the whole album is reloaded rather than patched; catalog changes rename links and lists
      if (e.type === 'albumChanged' && e.album.id === this.id() && e.album.revision !== this.album()?.revision) this.load(this.id());
      if (e.type === 'albumRemoved' && e.albumId === this.id()) this.close();
      if (e.type === 'catalogChanged') this.load(this.id());
    });
  }

  private async load(id: string) {
    const stale = this.details()?.album.id !== id;
    if (stale) { this.details.set(null); this.selected.set(null); this.lightbox.set(null); this.sel.clear(); this.bioOpen.set(false); }
    this.error.set(null);
    try {
      const d = await this.gw.album(id);
      if (this.id() !== id) return;
      if (d.album.revision !== this.album()?.revision) this.bioText.set(null);
      this.details.set(d);
      this.store.selectedId.set(id);
      const n = d.entries.length, names = new Set(d.entries.map(e => e.name));
      this.selected.update(i => i === null || i < n ? i : null);
      this.lightbox.update(i => i === null || i < n ? i : null);
      if ([...this.sel.ids()].some(x => !names.has(x))) this.sel.set([...this.sel.ids()].filter(x => names.has(x)));
      if (this.bioOpen()) void this.loadBio();
      const at = stale && this.photo() !== undefined ? +this.photo()! : NaN;
      if (d.entries[at]) this.openLightbox(at);
    } catch (e) { if (this.id() === id) this.error.set(e instanceof Error ? e.message : 'Album not found'); }
  }

  protected close() { this.router.navigate(['/']); }

  // ---- selection and targets ----

  protected focus(index: number) { this.selected.set(index); const e = this.entries()[index]; if (e) this.sel.set([e.name]); }

  protected pick(e: Entry, ev: MouseEvent) {
    this.selected.set(e.index);
    this.sel.click(e.name, this.names(), { ctrl: ev.ctrlKey || ev.metaKey, shift: ev.shiftKey });
  }

  /** The focused photo: the lightbox one, else the outlined tile. */
  private focused(): Entry | null { return this.current() ?? (this.selected() !== null ? this.entries()[this.selected()!] ?? null : null); }

  /** Names an action applies to: the lightbox photo; else the selection when `e` (or the focus) is in it; else `e` alone. */
  protected namesFor(e: Entry | null = this.focused()): string[] {
    if (this.current()) return [this.current()!.name];
    if (!e) return this.sel.size() ? this.names().filter(n => this.sel.has(n)) : [];
    return this.sel.has(e.name) && this.sel.size() > 1 ? this.names().filter(n => this.sel.has(n)) : [e.name];
  }

  // ---- header menus ----

  protected sortMenu(ev: MouseEvent) {
    const cur = this.prefs.viewerSort();
    const items: MenuEntry[] = VIEWER_SORTS.map(s => ({ label: SORT_LABELS[s], checked: s === cur, keys: s === cur ? (this.desc() ? '↓' : '↑') : undefined, run: () => this.sortBy(s) }));
    items.push({ sep: true }, { label: this.desc() ? 'Ascending' : 'Descending', icon: this.desc() ? 'sortAsc' : 'sortDesc', run: () => this.desc.update(d => !d) },
      { label: 'Reshuffle', icon: 'refresh', keys: 'R', disabled: cur !== 'random', run: () => this.reshuffle() });
    this.openAt(ev, items);
  }

  /** A new field starts in its default direction; picking the current one flips it. */
  protected sortBy(s: ViewerSort) {
    if (s === this.prefs.viewerSort()) this.desc.update(d => !d);
    else { this.prefs.viewerSort.set(s); this.desc.set(SORT_DESC_FIRST[s]); }
    if (s === 'random') this.seed.set(Date.now());
  }

  protected reshuffle() { this.prefs.viewerSort.set('random'); this.seed.set(Date.now()); }

  protected scaleMenu(ev: MouseEvent) {
    this.openAt(ev, VIEWER_SCALES.map(s => ({ label: SCALE_LABELS[s], checked: s === this.scale(), run: () => this.setScale(s) })));
  }

  protected setScale(s: ViewerScale) { this.prefs.viewerScale.set(s); this.zoom.set(null); }

  /** Where a menu opens for an event: under the clicked control, else at the pointer (read at once: `currentTarget` is gone after an await). */
  private pointOf(ev: MouseEvent): [number, number] {
    const r = ev.type === 'click' ? (ev.currentTarget as HTMLElement | null)?.getBoundingClientRect() : null;
    return r ? [r.left, r.bottom + 4] : [ev.clientX, ev.clientY];
  }

  private openAt(ev: MouseEvent, items: MenuEntry[]) { const [x, y] = this.pointOf(ev); this.menu.open(x, y, items); }

  protected onDocDown(ev: Event) { if (this.filterOpen() && !(ev.target as HTMLElement).closest('.anchor')) this.filterOpen.set(false); }

  // ---- photo actions ----

  protected photoMenu(e: Entry, ev: MouseEvent) {
    ev.preventDefault();
    if (!this.sel.has(e.name)) { this.selected.set(e.index); this.sel.set([e.name]); }
    const names = this.namesFor(e), many = names.length > 1, a = this.album()!;
    const items: MenuEntry[] = [
      ...(many ? [{ label: `${names.length} photos selected`, disabled: true }, { sep: true } as MenuEntry] : []),
      { label: 'View', icon: 'maximize', keys: 'Enter', run: () => this.openLightbox(e.index) },
      { label: 'Use as cover', icon: 'cover', keys: 'C', disabled: e.name === a.cover, run: () => this.setCover(e) },
      { label: 'Rate', icon: 'star', children: this.rateItems(names) },
      { label: 'Favorites', icon: 'heart', keys: 'F', children: () => this.favoriteItems(names) },
      { label: 'Copy to', icon: 'copy', children: this.copyItems(names) },
      { sep: true },
      { label: 'Show metadata', icon: 'info', keys: 'M', disabled: !this.metaOk(e), run: () => this.showMeta(e) },
      { label: 'Show in folder', icon: 'externalLink', run: () => this.store.reveal(a.id) },
      { label: 'Copy path', run: () => this.copyText(`${a.path}/${e.name}`, 'Path copied') },
    ];
    this.menu.open(ev.clientX, ev.clientY, items);
  }

  private rateItems(names: string[]): MenuEntry[] {
    const cur = names.length === 1 ? this.marks()[names[0]]?.rating ?? 0 : -1;
    return [{ label: 'Unrated', checked: cur === 0, run: () => this.rate(names, 0) }, ...Array.from({ length: 10 }, (_, i) => ({ label: `${i + 1}`, keys: `Ctrl+${(i + 1) % 10}`, checked: cur === i + 1, run: () => this.rate(names, i + 1) }))];
  }

  private async favoriteItems(names: string[]): Promise<MenuEntry[]> {
    const lists = await this.catalog.loadLists(), marks = this.marks();
    const inAll = (id: number) => names.every(n => marks[n]?.lists.includes(id));
    return [...lists.map(l => ({ label: l.name, checked: inAll(l.id), run: () => this.toggleList(names, l.id, !inAll(l.id)) })), ...(lists.length ? [{ sep: true } as MenuEntry] : []),
      { label: 'New list…', icon: 'plus', run: () => this.newList(names) }];
  }

  private copyItems(names: string[]): MenuEntry[] {
    const t = this.targets();
    return [...t.map((path, i) => ({ label: lastSegment(path) || path, keys: `${(i + 1) % 10}`, run: () => this.copyTo(names, i) })), ...(t.length ? [{ sep: true } as MenuEntry] : []),
      { label: 'Edit targets…', icon: 'edit', run: () => this.editTargets() }];
  }

  protected favoritesMenu(ev: MouseEvent, e: Entry | null = this.focused()) {
    const names = this.namesFor(e), [x, y] = this.pointOf(ev);
    if (names.length) void this.favoriteItems(names).then(items => this.menu.open(x, y, items));
  }

  protected metaOk(e: Entry) { return !((e.flags & ENTRY_META_KNOWN) && !(e.flags & ENTRY_META)); }

  /** One `markPhoto` per name; the album's marks come back with each. */
  private async mark(names: string[], patch: (cur: PhotoMark) => { rating?: number; lists?: number[] }) {
    const a = this.album();
    if (!a || !names.length) return;
    try {
      for (const n of names) {
        const marks = await this.gw.markPhoto(a.id, n, patch(this.marks()[n] ?? { rating: 0, lists: [] }));
        if (this.album()?.id === a.id) this.details.update(d => d && { ...d, marks });
      }
    } catch (e) { this.toasts.error(e, 'Mark failed: '); }
  }

  protected rate(names: string[], rating: number) { void this.mark(names, () => ({ rating })); }

  private toggleList(names: string[], id: number, on: boolean) {
    void this.mark(names, cur => ({ lists: on ? [...cur.lists, id] : cur.lists.filter(x => x !== id) }))
      .then(() => this.toasts.show(`${names.length === 1 ? names[0] : names.length + ' photos'} ${on ? 'added to' : 'removed from'} ${this.catalog.lists().find(l => l.id === id)?.name ?? 'the list'}`, 'success'));
  }

  private async newList(names: string[]) {
    const l = await this.picker.pick({ kind: 'LIST', title: 'Add to favorites list', allowCreate: true });
    if (l) this.toggleList(names, l.id, true);
  }

  /** Copy to target `i` (0-based): preflight → confirm (conflict policy) → run; the result arrives as `opResult` (toast from `OpsStore`). */
  protected async copyTo(names: string[], i: number) {
    const a = this.album(), target = this.targets()[i];
    if (!a || !names.length) return;
    if (!target) return this.toasts.show(`No copy target ${(i + 1) % 10}: set it under Settings → Operations`);
    const op: Op = { kind: 'COPY_PHOTOS', albumIds: [a.id], names, target, conflict: 'RENAME' };
    const pre = await this.ops.preflight(op);
    if (!pre) return;
    const r = await this.confirm.ask({ title: `Copy ${names.length === 1 ? names[0] : names.length + ' photos'} to ${target}`, items: names.length > 1 ? names : undefined, summary: pre, okLabel: 'Copy',
      options: [{ key: 'conflict', label: 'A different file has the name', kind: 'select', choices: ['Rename the copy', 'Skip it'] }] });
    if (r) await this.ops.run({ ...op, conflict: r['conflict'] === 'Skip it' ? 'SKIP' : 'RENAME' });
  }

  /** Asks the app to open Settings → Operations (`thumbnailer:settings`); until a listener exists the toast points there. */
  private editTargets() {
    if (document.dispatchEvent(new CustomEvent('thumbnailer:settings', { detail: { page: 'operations' }, cancelable: true }))) this.toasts.show('Copy targets are set under Settings → Operations');
  }

  protected async showMeta(e: Entry) {
    const a = this.album();
    if (!a) return;
    this.meta.set({ title: e.name, rows: null });
    try { const rows = await this.gw.photoMeta(a.id, e.index); if (this.meta()?.title === e.name) this.meta.set({ title: e.name, rows }); }
    catch (err) { this.meta.set(null); this.toasts.error(err, 'Metadata: '); }
  }

  protected toggleBio() {
    this.bioOpen.update(v => !v);
    if (this.bioOpen() && !this.bioText()) void this.loadBio();
  }

  private async loadBio() {
    const a = this.album();
    if (!a?.bio) return;
    try { const t = await this.gw.albumText(a.id, a.bio); if (this.album()?.id === a.id) this.bioText.set(t); }
    catch (e) { this.bioOpen.set(false); this.toasts.error(e, 'Bio: '); }
  }

  protected async copyText(text: string, done: string) {
    try { await navigator.clipboard.writeText(text); this.toasts.show(done); } catch (e) { this.toasts.error(e, 'Copy failed: '); }
  }

  protected async setCover(e: Entry) {
    const a = this.album();
    if (a && await this.store.patch(a.id, { cover: e.name })) this.toasts.show(`Cover set to ${e.name}`, 'success');
  }

  // ---- lightbox, slideshow, full screen ----

  protected openLightbox(index: number) { this.zoom.set(null); this.lightbox.set(index); this.selected.set(index); }

  protected closeLightbox() { this.lightbox.set(null); this.slideshow.set(false); this.zoom.set(null); }

  /** Moves within the viewer order and wraps; the outline follows. */
  protected step(delta: number) {
    const s = this.shown(), p = this.position();
    if (!s.length) return;
    const i = s[((p < 0 ? 0 : p) + delta + s.length) % s.length].index;
    this.zoom.set(null); this.lightbox.set(i); this.selected.set(i);
  }

  /** Opens the lightbox on the focused (else first) photo when it is closed; false when there is nothing to show. */
  private ensureLightbox(): boolean {
    if (this.lightbox() !== null) return true;
    const f = this.focused() ?? this.shown()[0];
    if (!f) return false;
    this.openLightbox(f.index);
    return true;
  }

  protected toggleSlideshow() { if (this.ensureLightbox()) this.slideshow.update(v => !v); }

  protected fullscreen() {
    if (!this.ensureLightbox()) return;
    const lb = this.lightboxCmp();
    lb ? lb.toggleFullscreen() : afterNextRender(() => this.lightboxCmp()?.toggleFullscreen(), { injector: this.injector });
  }

  protected onKey(e: KeyboardEvent) {
    if (e.altKey || (e.target as HTMLElement)?.closest('input, textarea, select') || document.querySelector('dialog[open]')) return;
    const ctrl = e.ctrlKey || e.metaKey, open = this.lightbox() !== null, n = slot(e.key);
    if (ctrl) {
      if (n >= 0) this.rate(this.namesFor(), n + 1);
      else if (e.key === 'a' || e.key === 'A') this.sel.selectAll(this.names());
      else return;
    } else switch (e.key) {
      case 'Escape':
        if (open) this.closeLightbox(); else if (this.filterOpen() || this.bioOpen()) { this.filterOpen.set(false); this.bioOpen.set(false); } else if (this.sel.size() > 1) this.sel.clear(); else this.close();
        break;
      case 'ArrowRight': if (open) this.step(1); else return; break;
      case 'ArrowLeft': if (open) this.step(-1); else return; break;
      case ' ': this.toggleSlideshow(); break;
      case 'F11': this.fullscreen(); break;
      case 's': case 'S': this.setScale(nextScale(this.scale())); break;
      case 'r': case 'R': this.reshuffle(); break;
      case 'f': case 'F': { const f = this.focused(); if (!f) return; this.favoritesMenu(this.anchorFor(f)); break; }
      case 'm': case 'M': { const f = this.focused(); if (!f || !this.metaOk(f)) return; void this.showMeta(f); break; }
      case 'b': case 'B': if (!this.album()?.bio) return; this.toggleBio(); break;
      case 'c': case 'C': { const f = this.focused(); if (!f) return; void this.setCover(f); break; }
      default: if (n >= 0 && this.namesFor().length) void this.copyTo(this.namesFor(), n); else return;
    }
    e.preventDefault();
    e.stopPropagation();
  }

  /** A synthetic anchor for a key-opened menu: the focused tile, else the lightbox caption area. */
  private anchorFor(f: Entry): MouseEvent {
    const r = this.current() ? null : document.querySelector(`[data-id="${f.index}"]`)?.getBoundingClientRect();
    return new MouseEvent('keyboard', { clientX: r ? r.left + 12 : innerWidth / 2 - 100, clientY: r ? r.top + 12 : innerHeight - 160 });
  }
}
