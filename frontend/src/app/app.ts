import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { CatalogStore } from './core/catalog-store';
import { BasenamePipe } from './core/format';
import { Tile } from './core/layout';
import { Album } from './core/model';
import { Prefs, SidebarTab } from './core/prefs';
import { SELECT_CAP } from './core/selection';
import { AlbumStore } from './core/store';
import { Toasts } from './core/toasts';
import { AlbumCard } from './ui/album-card';
import { DetailsPanel } from './ui/details-panel';
import { LocationBar } from './ui/location-bar';
import { SettingsDialog } from './ui/settings/settings-dialog';
import { ConfirmDialog } from './ui/shared/confirm';
import { ContextMenuView } from './ui/shared/context-menu';
import { EntityPickerDialog } from './ui/shared/entity-picker';
import { Icon } from './ui/shared/icon';
import { Sidebar } from './ui/sidebar';
import { TileGrid } from './ui/tile-grid';
import { Topbar } from './ui/topbar';


@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Topbar, Sidebar, LocationBar, TileGrid, AlbumCard, DetailsPanel, SettingsDialog, ContextMenuView, ConfirmDialog, EntityPickerDialog, Icon, BasenamePipe, DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  host: { '(document:keydown)': 'onKey($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly store = inject(AlbumStore);
  protected readonly catalog = inject(CatalogStore);
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

  /** Albums an action applies to: the selection, else the focused album. */
  private targets(): string[] { const s = this.store.selection, id = this.store.selectedId(); return s.size() ? [...s.ids()] : id ? [id] : []; }

  /** `Ctrl+A`: every loaded album, loading further pages until the cap or the end (each page joins the selection as it lands). */
  private async selectAll() {
    const s = this.store;
    for (let n = -1; n < s.albums().length;) {
      n = s.albums().length;
      s.selection.selectAll(s.albums().map(a => a.id));
      if (n >= SELECT_CAP || n >= s.total()) break;
      await s.loadMore();
    }
    if (s.selection.size() >= SELECT_CAP && s.total() > SELECT_CAP) this.toasts.show(`Selected the first ${SELECT_CAP} albums`);
  }

  /** `H`: hides the targets, or unhides them when every one is hidden already; the selection ends with them (hidden albums leave the list). */
  private async hide(ids: string[]) {
    const set = new Set(ids), albums = this.store.albums().filter(a => set.has(a.id)), unhide = albums.length > 0 && albums.every(a => a.hidden);
    const n = await this.catalog.bulkUpdate({ albumIds: ids }, { hidden: !unhide });
    if (n === null) return;
    this.store.selection.clear();
    this.toasts.show(`${n} album${n === 1 ? '' : 's'} ${unhide ? 'unhidden' : 'hidden'}`);
  }

  /** `Delete`: F4 wires preflight → `Confirm` → `runOp` here; until then the key reaches only this hook. */
  private deleteAlbums(ids: string[]) { void ids; }

  protected onKey(e: KeyboardEvent) {
    if (e.altKey || (e.target as HTMLElement)?.closest('input, textarea, select, dialog[open]')) return;
    const viewer = this.router.url.startsWith('/album');
    if (e.ctrlKey || e.metaKey) {
      if (viewer || e.shiftKey || e.key.toLowerCase() !== 'a') return;
      void this.selectAll();
      e.preventDefault();
      return;
    }
    switch (e.key) {
      case '/': if (viewer) return; this.topbar().focusSearch(); break;
      case 'e': case 'E': if (viewer) return; this.toggleTab('folders'); break;
      case 'f': case 'F': if (viewer) return; this.toggleTab('refine'); break;
      case 'i': case 'I': this.prefs.detailsOpen.update(v => !v); break;
      case 't': case 'T': this.prefs.toggleTheme(); break;
      case '[': this.prefs.setColumns(this.prefs.columns() - 1); break;
      case ']': this.prefs.setColumns(this.prefs.columns() + 1); break;
      case 'h': case 'H': if (viewer || !this.targets().length) return; void this.hide(this.targets()); break;
      case 'Delete': if (viewer || !this.targets().length) return; this.deleteAlbums(this.targets()); break;
      case 'Escape': {
        if (viewer) return;
        // a multi-selection goes first; then the sidebar while it overlays the grid; docked, Esc drops the focused album
        const s = this.store.selection, lone = s.size() === 0 || (s.size() === 1 && s.has(this.store.selectedId() ?? ''));
        s.clear();
        if (!lone) break;
        if (this.prefs.sidebar() !== 'none' && matchMedia('(max-width: 1100px)').matches) this.prefs.sidebar.set('none'); else this.store.selectedId.set(null);
        break;
      }
      default: return;
    }
    e.preventDefault();
  }
}
