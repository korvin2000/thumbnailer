import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { BasenamePipe } from './core/format';
import { Tile } from './core/layout';
import { Album } from './core/model';
import { Prefs, SidebarTab } from './core/prefs';
import { AlbumStore } from './core/store';
import { Toasts } from './core/toasts';
import { AlbumCard } from './ui/album-card';
import { DetailsPanel } from './ui/details-panel';
import { LocationBar } from './ui/location-bar';
import { SettingsDialog } from './ui/settings/settings-dialog';
import { Icon } from './ui/shared/icon';
import { Sidebar } from './ui/sidebar';
import { TileGrid } from './ui/tile-grid';
import { Topbar } from './ui/topbar';


@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Topbar, Sidebar, LocationBar, TileGrid, AlbumCard, DetailsPanel, SettingsDialog, Icon, BasenamePipe, DecimalPipe],
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
  /** A folder is selected and nothing else narrows the result: the folder itself is empty. */
  protected readonly emptyFolder = computed(() => !!this.store.folder() && !this.store.refining() && !this.store.text());

  protected open(id: string) { this.router.navigate(['/album', id]); }
  protected openSettings() { this.settings().open(); }
  protected showInfo(id: string) { this.store.selectedId.set(id); this.prefs.detailsOpen.set(true); }
  protected toggleTab(tab: SidebarTab) { this.prefs.sidebar.update(v => v === tab ? 'none' : tab); }

  protected onKey(e: KeyboardEvent) {
    if (e.ctrlKey || e.metaKey || e.altKey || (e.target as HTMLElement)?.closest('input, textarea, select, dialog[open]')) return;
    const viewer = this.router.url.startsWith('/album');
    switch (e.key) {
      case '/': if (viewer) return; this.topbar().focusSearch(); break;
      case 'e': case 'E': if (viewer) return; this.toggleTab('folders'); break;
      case 'f': case 'F': if (viewer) return; this.toggleTab('refine'); break;
      case 'i': case 'I': this.prefs.detailsOpen.update(v => !v); break;
      case 't': case 'T': this.prefs.toggleTheme(); break;
      case '[': this.prefs.setColumns(this.prefs.columns() - 1); break;
      case ']': this.prefs.setColumns(this.prefs.columns() + 1); break;
      case 'Escape':
        if (viewer) return;
        // the sidebar only closes on Esc while it overlays the grid; docked, Esc clears the selection
        if (this.prefs.sidebar() !== 'none' && matchMedia('(max-width: 1100px)').matches) this.prefs.sidebar.set('none'); else this.store.selectedId.set(null);
        break;
      default: return;
    }
    e.preventDefault();
  }
}
