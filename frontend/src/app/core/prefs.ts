import { effect, Injectable, signal, WritableSignal } from '@angular/core';
import { LAYOUT_MODES, LayoutMode } from './layout';

export type Theme = 'system' | 'light' | 'dark';
export type SidebarTab = 'folders' | 'refine' | 'none';
const SIDEBARS: SidebarTab[] = ['folders', 'refine', 'none'];

/** User preferences that live in this browser only; each one is a signal persisted under `thumbnailer.<key>`. */
@Injectable({ providedIn: 'root' })
export class Prefs {
  readonly theme = this.persisted<Theme>('theme', 'system');
  readonly columns = this.persisted('columns', 5);
  readonly layoutMode = this.persisted<LayoutMode>('layout', 'bricks');
  readonly showTags = this.persisted('showTags', true);
  readonly followHover = this.persisted('followHover', true);
  /** Which sidebar tab is shown; `none` = sidebar closed. */
  readonly sidebar = this.persisted<SidebarTab>('sidebar', 'folders');
  readonly detailsOpen = this.persisted('detailsOpen', false);

  constructor() {
    effect(() => { document.documentElement.dataset['theme'] = this.theme() === 'system' ? '' : this.theme(); });
    if (!LAYOUT_MODES.includes(this.layoutMode())) this.layoutMode.set('bricks');
    if (!SIDEBARS.includes(this.sidebar())) this.sidebar.set('folders');
  }

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
