import { effect, Injectable, signal, WritableSignal } from '@angular/core';
import { LAYOUT_MODES, LayoutMode } from './layout';

export type Theme = 'light' | 'dark';
const THEMES: Theme[] = ['light', 'dark'];
const systemTheme = (): Theme => matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
export type SidebarTab = 'folders' | 'refine' | 'none';
const SIDEBARS: SidebarTab[] = ['folders', 'refine', 'none'];
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
  readonly detailsOpen = this.persisted('detailsOpen', false);
  readonly groupBy = this.persisted<GroupBy>('groupBy', 'folders');
  readonly viewerScale = this.persisted<ViewerScale>('viewerScale', 'fitLarge');
  readonly viewerSort = this.persisted<ViewerSort>('viewerSort', 'name');
  /** Viewer filter: minimum longer side in px (0 = all). */
  readonly viewerMinSide = this.persisted('viewerMinSide', 0);
  readonly slideshowSeconds = this.persisted('slideshowSeconds', 5);

  constructor() {
    if (!THEMES.includes(this.theme())) this.theme.set(systemTheme());
    effect(() => { document.documentElement.dataset['theme'] = this.theme(); });
    if (!LAYOUT_MODES.includes(this.layoutMode())) this.layoutMode.set('bricks');
    if (!SIDEBARS.includes(this.sidebar())) this.sidebar.set('folders');
    if (!GROUP_BYS.includes(this.groupBy())) this.groupBy.set('folders');
    if (!VIEWER_SCALES.includes(this.viewerScale())) this.viewerScale.set('fitLarge');
    if (!VIEWER_SORTS.includes(this.viewerSort())) this.viewerSort.set('name');
  }

  toggleTheme() { this.theme.update(t => t === 'dark' ? 'light' : 'dark'); }
  setColumns(n: number) { this.columns.set(Math.max(1, Math.min(12, Math.round(n)))); }

  private persisted<T>(key: string, fallback: T): WritableSignal<T> {
    const k = `thumbnailer.${key}`;
    let initial = fallback;
    try { const raw = localStorage.getItem(k); if (raw !== null) initial = JSON.parse(raw); } catch { /* unreadable storage: keep default */ }
    const s = signal<T>(initial);
    effect(() => { try { localStorage.setItem(k, JSON.stringify(s())); } catch { /* storage unavailable */ } });
    return s;
  }
}
