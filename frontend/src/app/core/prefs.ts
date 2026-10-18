import { effect, Injectable, signal, untracked, WritableSignal } from '@angular/core';
import { LAYOUT_MODES, LayoutMode } from './layout';

export type Theme = 'light' | 'dark';
const THEMES: Theme[] = ['light', 'dark'];
const systemTheme = (): Theme => matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
export type SidebarTab = 'folders' | 'refine' | 'none';
const SIDEBARS: SidebarTab[] = ['folders', 'refine', 'none'];
const TABS: SidebarTab[] = ['folders', 'refine'];
/** What the Browse tab groups the library by. */
export type GroupBy = 'folders' | 'studio' | 'model' | 'photographer' | 'genre' | 'list';
export const GROUP_BYS: GroupBy[] = ['folders', 'studio', 'model', 'photographer', 'genre', 'list'];
/** Lightbox scale: fill the viewport, downscale only, 1:1, fit width, fit height. */
export type ViewerScale = 'fit' | 'fitLarge' | 'actual' | 'width' | 'height';
export const VIEWER_SCALES: ViewerScale[] = ['fit', 'fitLarge', 'actual', 'width', 'height'];
export type ViewerSort = 'name' | 'size' | 'date' | 'dimensions' | 'rating' | 'random';
export const VIEWER_SORTS: ViewerSort[] = ['name', 'size', 'date', 'dimensions', 'rating', 'random'];

/** User preferences that live in this browser only; each one is a signal persisted under `thumbnailer.<key>`. */
@Injectable({ providedIn: 'root' })
export class Prefs {
  /** Light or dark; a first run (or an old `system` value) takes the OS theme, after that the choice sticks. */
  readonly theme = this.persisted<Theme>('theme', systemTheme());
  readonly columns = this.persisted('columns', 5);
  readonly layoutMode = this.persisted<LayoutMode>('layout', 'bricks');
  readonly showTags = this.persisted('showTags', true);
  readonly followHover = this.persisted('followHover', true);
  /** Which sidebar tab is shown; `none` = sidebar closed. */
  readonly sidebar = this.persisted<SidebarTab>('sidebar', 'folders');
  /** The tab shown last (`folders` = Browse): what the topbar's sidebar button reopens. */
  readonly lastTab = this.persisted<SidebarTab>('lastTab', 'folders');
  readonly detailsOpen = this.persisted('detailsOpen', false);
  readonly groupBy = this.persisted<GroupBy>('groupBy', 'folders');
  readonly viewerScale = this.persisted<ViewerScale>('viewerScale', 'fitLarge');
  /** Photos per row in the album viewer (1–12), its own value beside the library's `columns`. */
  readonly viewerColumns = this.persisted('viewerColumns', 3);
  readonly viewerSort = this.persisted<ViewerSort>('viewerSort', 'name');
  /** Direction of `viewerSort` (true = descending); a new field resets it to its default (`SORT_DESC_FIRST`). */
  readonly viewerSortDesc = this.persisted('viewerSortDesc', false);
  /** Viewer filter: minimum longer side in px (0 = all). */
  readonly viewerMinSide = this.persisted('viewerMinSide', 0);
  readonly slideshowSeconds = this.persisted('slideshowSeconds', 5);

  constructor() {
    if (!THEMES.includes(this.theme())) this.theme.set(systemTheme());
    effect(() => { document.documentElement.dataset['theme'] = this.theme(); });
    if (!LAYOUT_MODES.includes(this.layoutMode())) this.layoutMode.set('bricks');
    if (!SIDEBARS.includes(this.sidebar())) this.sidebar.set('folders');
    if (!TABS.includes(this.lastTab())) this.lastTab.set('folders');
    effect(() => { const tab = this.sidebar(); if (tab !== 'none') untracked(() => this.lastTab.set(tab)); });
    if (!GROUP_BYS.includes(this.groupBy())) this.groupBy.set('folders');
    if (!VIEWER_SCALES.includes(this.viewerScale())) this.viewerScale.set('fitLarge');
    if (!VIEWER_SORTS.includes(this.viewerSort())) this.viewerSort.set('name');
  }

  toggleTheme() { this.theme.update(t => t === 'dark' ? 'light' : 'dark'); }
  toggleLayout() { this.layoutMode.update(m => m === 'bricks' ? 'grid' : 'bricks'); }
  /** Closes the sidebar, or reopens it on the tab shown last. */
  toggleSidebar() { this.sidebar.update(v => v === 'none' ? this.lastTab() : 'none'); }
  setColumns(n: number) { this.columns.set(Math.max(1, Math.min(12, Math.round(n)))); }
  setViewerColumns(n: number) { this.viewerColumns.set(Math.max(1, Math.min(12, Math.round(n)))); }

  private persisted<T>(key: string, fallback: T): WritableSignal<T> {
    const k = `thumbnailer.${key}`;
    let initial = fallback;
    try { const raw = localStorage.getItem(k); if (raw !== null) initial = JSON.parse(raw); } catch { /* unreadable storage: keep default */ }
    const s = signal<T>(initial);
    effect(() => { try { localStorage.setItem(k, JSON.stringify(s())); } catch { /* storage unavailable */ } });
    return s;
  }
}
