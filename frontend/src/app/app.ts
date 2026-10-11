import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { BasenamePipe } from './core/format';
import { Tile } from './core/layout';
import { Album } from './core/model';
import { Prefs } from './core/prefs';
import { AlbumStore } from './core/store';
import { Toasts } from './core/toasts';
import { AlbumCard } from './ui/album-card';
import { DetailsPanel } from './ui/details-panel';
import { FiltersPanel } from './ui/filters-panel';
import { SettingsDialog } from './ui/settings-dialog';
import { Icon } from './ui/shared/icon';
import { TileGrid } from './ui/tile-grid';
import { Topbar } from './ui/topbar';

const THEMES = ['system', 'light', 'dark'] as const;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Topbar, FiltersPanel, TileGrid, AlbumCard, DetailsPanel, SettingsDialog, Icon, BasenamePipe, DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  host: { '(document:keydown)': 'onKey($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  protected readonly toasts = inject(Toasts);
  private readonly router = inject(Router);
  private readonly topbar = viewChild.required(Topbar);
  private readonly settings = viewChild.required(SettingsDialog);

  protected readonly tiles = computed<Tile<Album>[]>(() => this.store.albums().map(a => ({ id: a.id, aspect: a.coverPortrait ? 9 / 16 : 16 / 9, data: a })));
  protected readonly hoveringDetails = computed(() => this.prefs.followHover() && !!this.store.hovered());
  protected readonly detailsAlbum = computed(() => (this.hoveringDetails() ? this.store.hovered() : null) ?? this.store.selected());
  protected readonly noRoots = computed(() => this.store.settings() !== null && this.store.roots().length === 0);

  protected open(id: string) { this.router.navigate(['/album', id]); }
  protected openSettings() { this.settings().open(); }
  protected showInfo(id: string) { this.store.selectedId.set(id); this.prefs.detailsOpen.set(true); }

  protected onKey(e: KeyboardEvent) {
    if (e.ctrlKey || e.metaKey || e.altKey || (e.target as HTMLElement)?.closest('input, textarea, select, dialog[open]')) return;
    const viewer = this.router.url.startsWith('/album');
    switch (e.key) {
      case '/': if (viewer) return; this.topbar().focusSearch(); break;
      case 'f': case 'F': if (viewer) return; this.prefs.filtersOpen.update(v => !v); break;
      case 'i': case 'I': this.prefs.detailsOpen.update(v => !v); break;
      case 't': case 'T': this.prefs.theme.update(t => THEMES[(THEMES.indexOf(t) + 1) % THEMES.length]); break;
      case '[': this.prefs.setColumns(this.prefs.columns() - 1); break;
      case ']': this.prefs.setColumns(this.prefs.columns() + 1); break;
      case 'Escape': if (viewer) return; this.prefs.filtersOpen() ? this.prefs.filtersOpen.set(false) : this.store.selectedId.set(null); break;
      default: return;
    }
    e.preventDefault();
  }
}
