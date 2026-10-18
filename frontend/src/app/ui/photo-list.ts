import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { CatalogStore } from '../core/catalog-store';
import { Gateway } from '../core/gateway';
import { Tile } from '../core/layout';
import { PhotoListItem } from '../core/model';
import { Prefs } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { ContextMenu, MenuEntry } from './shared/context-menu';
import { Icon } from './shared/icon';
import { TileGrid, TileMenuEvent } from './tile-grid';

const PAGE = 200;
const key = (p: PhotoListItem) => `${p.albumId}/${p.name}`;

/** Route `/list/:id`: the photos of a favorites list across albums (`photos {listId, offset, limit}`), paged; a photo opens the viewer on it. */
@Component({
  selector: 'app-photo-list',
  imports: [Icon, TileGrid],
  template: `
    <header class="bar">
      <button class="btn icon-btn ghost" (click)="close()" title="Back to library (Esc)"><app-icon name="arrowLeft" /></button>
      <div class="title">
        <h1><app-icon name="heart" [size]="14" /> {{ list()?.name ?? 'Favorites' }}</h1>
        <div class="meta"><span>{{ total() }} photos</span>@if (list(); as l) { <span>{{ l.albums }} albums</span> }@if (error(); as err) { <span class="err">{{ err }}</span> }</div>
      </div>
      <button class="btn sm" [disabled]="!list()" (click)="openCard()" title="The list's card"><app-icon name="info" [size]="14" /> Card</button>
      <button class="btn sm" (click)="showAlbums()" title="The albums in this list"><app-icon name="images" [size]="14" /> Albums</button>
    </header>
    <app-tile-grid class="grid" [tiles]="tiles()" mode="grid" [columns]="prefs.columns()" [gap]="8" [padding]="10" [selectedId]="focus()" [hasMore]="items().length < total()" [empty]="emptyState"
                   (select)="focus.set($event)" (open)="openKey($event)" (reachedEnd)="loadMore()" (contextMenu)="onMenu($event)">
      <ng-template let-p>
        <div class="photo" [class.gone]="p.index < 0" [class.selected]="key(p) === focus()" (click)="focus.set(key(p))" (dblclick)="view(p)">
          @if (p.index >= 0) { <img [src]="gw.thumbUrl(p.album, p.index)" [alt]="p.name" loading="lazy" decoding="async" draggable="false" /> }
          @else { <div class="missing"><app-icon name="alert" [size]="22" /><span>no longer in the album</span></div> }
          @if (p.rating) { <span class="badge">★ {{ p.rating }}</span> }
          <div class="info"><span class="name">{{ p.name }}</span><span class="album" [title]="p.album.name">{{ p.album.name }}</span></div>
        </div>
      </ng-template>
    </app-tile-grid>
    <ng-template #emptyState>@if (!loading()) { <div class="empty"><app-icon name="heart" [size]="28" /><p>No photos in this list yet: mark them in the viewer (F).</p></div> }</ng-template>
  `,
  styles: `
    :host { position: fixed; inset: 0; z-index: 30; display: flex; flex-direction: column; background: var(--bg); animation: fade-up 0.18s var(--ease); }
    .bar { display: flex; align-items: center; gap: 8px; min-height: var(--topbar-h); padding: 6px 12px; background: var(--bg-elev); border-bottom: 1px solid var(--border); }
    .title { flex: 1; min-width: 0; }
    h1 { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    h1 app-icon { color: var(--danger); }
    .meta { display: flex; gap: 12px; font-size: 11.5px; color: var(--text-2); }
    .err { color: var(--danger); }
    .grid { flex: 1; min-height: 0; }
    .empty { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--text-3); }
    .photo { position: relative; width: 100%; height: 100%; overflow: hidden; border-radius: 6px; background: var(--surface-2); cursor: pointer; outline: 2px solid transparent; transition: outline-color 0.15s; }
    .photo.selected { outline-color: var(--accent); }
    .photo img { width: 100%; height: 100%; object-fit: cover; }
    .missing { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; height: 100%; color: var(--text-3); font-size: 11px; }
    .badge { position: absolute; top: 6px; left: 6px; height: 18px; padding: 0 7px; border-radius: 5px; background: oklch(10% 0.01 260 / 0.6); color: #fff; font-size: 10.5px; font-weight: 700; line-height: 18px; backdrop-filter: blur(6px); }
    .info { position: absolute; left: 0; right: 0; bottom: 0; display: flex; flex-direction: column; padding: 14px 8px 6px; background: var(--scrim); color: #fff; font-size: 11px; line-height: 1.35; pointer-events: none; }
    .info .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .info .album { opacity: 0.75; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  `,
  host: { '(document:keydown)': 'onKey($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhotoListView {
  readonly id = input.required<string>();

  protected readonly gw = inject(Gateway);
  protected readonly prefs = inject(Prefs);
  private readonly store = inject(AlbumStore);
  private readonly catalog = inject(CatalogStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly menu = inject(ContextMenu);
  protected readonly key = key;

  protected readonly items = signal<PhotoListItem[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly focus = signal<string | null>(null);
  protected readonly listId = computed(() => +this.id());
  protected readonly list = computed(() => this.catalog.entityOf(this.listId()) ?? null);
  protected readonly tiles = computed<Tile<PhotoListItem>[]>(() => this.items().map(p => ({ id: key(p), aspect: 1, data: p })));
  private gen = 0;
  private reloadTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    effect(() => { const id = this.listId(); untracked(() => { void this.catalog.ensureKnown([id]); void this.load(0); }); });
    this.gw.events.pipe(takeUntilDestroyed()).subscribe(e => {
      if (e.type === 'catalogChanged' && e.kinds.includes('LIST')) void this.load(this.items().length); // renamed, merged, deleted, or marks changed elsewhere
      // a changed album may have been re-indexed (indices renumbered) or hidden (out of the list): the server recomputes both, so its photos are reloaded, not patched
      if (e.type === 'albumChanged' && this.items().some(p => p.albumId === e.album.id)) { clearTimeout(this.reloadTimer); this.reloadTimer = setTimeout(() => void this.load(this.items().length), 600); }
      if (e.type === 'albumRemoved') { const gone = this.items().filter(p => p.albumId === e.albumId).length; if (gone) { this.items.update(l => l.filter(p => p.albumId !== e.albumId)); this.total.update(t => Math.max(0, t - gone)); } }
    });
  }

  /** Reloads from the start up to `keep` items (a refresh), or the first page. */
  private async load(keep: number) {
    const gen = ++this.gen, id = this.listId();
    this.loading.set(true);
    try {
      const r = await this.gw.photos(id, 0, Math.max(PAGE, Math.min(500, keep)));
      if (gen !== this.gen) return;
      this.items.set(r.items); this.total.set(r.total); this.error.set(null);
    } catch (e) { if (gen === this.gen) this.error.set(e instanceof Error ? e.message : String(e)); }
    finally { if (gen === this.gen) this.loading.set(false); }
  }

  protected async loadMore() {
    if (this.loading() || this.items().length >= this.total()) return;
    const gen = this.gen;
    this.loading.set(true);
    try { const r = await this.gw.photos(this.listId(), this.items().length, PAGE); if (gen === this.gen) { this.items.update(l => [...l, ...r.items]); this.total.set(r.total); } }
    catch (e) { this.toasts.error(e, 'Loading failed: '); }
    finally { if (gen === this.gen) this.loading.set(false); }
  }

  protected close() { void this.router.navigate(['/']); }
  protected openCard() { this.catalog.openCard(this.listId()); this.prefs.detailsOpen.set(true); this.close(); }
  protected showAlbums() { this.store.setFilter('missing', null); this.store.setFilter('entities', [this.listId()]); this.close(); }
  protected openKey(k: string) { const p = this.items().find(x => key(x) === k); if (p) this.view(p); }

  /** The viewer on that photo (`?photo=`); a marked file the album no longer lists opens the album alone. */
  protected view(p: PhotoListItem) { void this.router.navigate(['/album', p.albumId], p.index >= 0 ? { queryParams: { photo: p.index } } : {}); }

  protected onMenu(e: TileMenuEvent) {
    const p = this.items().find(x => key(x) === e.id);
    if (!p) return;
    const rate = (rating: number) => this.mark(p, { rating });
    const items: MenuEntry[] = [
      { label: 'View', icon: 'maximize', keys: 'Enter', disabled: p.index < 0, run: () => this.view(p) },
      { label: 'Open album', icon: 'images', run: () => void this.router.navigate(['/album', p.albumId]) },
      { label: 'Rate', icon: 'star', children: [{ label: 'Unrated', checked: p.rating === 0, run: () => rate(0) }, ...Array.from({ length: 10 }, (_, i) => ({ label: `${i + 1}`, checked: p.rating === i + 1, run: () => rate(i + 1) }))] },
      { sep: true },
      { label: `Remove from ${this.list()?.name ?? 'the list'}`, icon: 'heart', danger: true, run: () => this.remove(p) },
      { label: 'Show in folder', icon: 'externalLink', run: () => this.store.reveal(p.albumId) },
    ];
    this.menu.open(e.x, e.y, items);
  }

  /** True when the mark was saved (a failure is a toast, and the item stays as it was). */
  private async mark(p: PhotoListItem, m: { rating?: number; lists?: number[] }): Promise<boolean> {
    try { await this.gw.markPhoto(p.albumId, p.name, m); if (m.rating !== undefined) this.items.update(l => l.map(x => x === p ? { ...x, rating: m.rating! } : x)); return true; }
    catch (e) { this.toasts.error(e, 'Mark failed: '); return false; }
  }

  /** Drops this list from the photo's marks (the other lists and the rating stay); the item leaves only once the server agreed. */
  private async remove(p: PhotoListItem) {
    let lists: number[];
    try { lists = (await this.gw.album(p.albumId)).marks[p.name]?.lists ?? []; } catch (e) { return this.toasts.error(e, 'Remove failed: '); }
    if (!await this.mark(p, { lists: lists.filter(x => x !== this.listId()) })) return;
    this.items.update(l => l.filter(x => x !== p)); this.total.update(t => Math.max(0, t - 1));
    this.catalog.onChanged(['LIST']);
  }

  protected onKey(e: KeyboardEvent) {
    if (e.altKey || e.ctrlKey || e.metaKey || (e.target as HTMLElement)?.closest('input, textarea, select') || document.querySelector('dialog[open]')) return;
    if (e.key === 'Escape') this.close();
    else if (e.key === 'Enter' && this.focus()) this.openKey(this.focus()!);
    else return;
    e.preventDefault();
  }
}
