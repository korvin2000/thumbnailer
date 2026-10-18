import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, untracked, viewChild } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { CatalogStore } from './core/catalog-store';
import { BasenamePipe } from './core/format';
import { Tile } from './core/layout';
import { Album, OpProgress, OpResult } from './core/model';
import { OpsStore } from './core/ops-store';
import { Prefs, SidebarTab } from './core/prefs';
import { AlbumStore } from './core/store';
import { Toasts } from './core/toasts';
import { Actions } from './ui/actions';
import { AlbumCard } from './ui/album-card';
import { CopiesPopover } from './ui/copies-popover';
import { DetailsPanel } from './ui/details-panel';
import { LocationBar } from './ui/location-bar';
import { opNoun, opVerb } from './ui/op-confirm';
import { FolderPickerDialog } from './ui/settings/folder-picker';
import { SettingsDialog } from './ui/settings/settings-dialog';
import { SettingsLink, SettingsPage } from './ui/settings/settings-link';
import { Confirm, ConfirmDialog } from './ui/shared/confirm';
import { ContextMenuView } from './ui/shared/context-menu';
import { EntityPickerDialog } from './ui/shared/entity-picker';
import { Icon } from './ui/shared/icon';
import { Sidebar } from './ui/sidebar';
import { TileGrid } from './ui/tile-grid';
import { Topbar } from './ui/topbar';

/** Routes that overlay the library (the viewer, a list's photos): the library keys stay out of their way. */
const OVERLAY = /^\/(album|list)(\/|$)/;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Topbar, Sidebar, LocationBar, TileGrid, AlbumCard, DetailsPanel, SettingsDialog, FolderPickerDialog, ContextMenuView, ConfirmDialog, EntityPickerDialog, CopiesPopover, Icon, BasenamePipe, DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  host: { '(document:keydown)': 'onKey($event)' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly store = inject(AlbumStore);
  protected readonly catalog = inject(CatalogStore);
  protected readonly ops = inject(OpsStore);
  protected readonly actions = inject(Actions);
  protected readonly prefs = inject(Prefs);
  protected readonly toasts = inject(Toasts);
  protected readonly settingsLink = inject(SettingsLink);
  private readonly confirm = inject(Confirm);
  private readonly router = inject(Router);
  private readonly topbar = viewChild.required(Topbar);

  protected readonly tiles = computed<Tile<Album>[]>(() => this.store.albums().map(a => ({ id: a.id, aspect: a.coverPortrait ? 9 / 16 : 16 / 9, data: a })));
  protected readonly hoveringDetails = computed(() => this.prefs.followHover() && !!this.store.hovered());
  protected readonly detailsAlbum = computed(() => (this.hoveringDetails() ? this.store.hovered() : null) ?? this.store.selected());
  protected readonly noRoots = computed(() => this.store.settings() !== null && this.store.roots().length === 0);
  /** A folder is selected and nothing else narrows the result: the folder itself is empty. */
  protected readonly emptyFolder = computed(() => !!this.store.folder() && !this.store.refining() && !this.store.text());

  constructor() {
    // the viewer's "Edit targets…" (and anything else) asks for a settings page through a document event
    const onSettings = (e: Event) => { e.preventDefault(); this.openSettings((e as CustomEvent<{ page?: SettingsPage }>).detail?.page); };
    document.addEventListener('thumbnailer:settings', onSettings);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('thumbnailer:settings', onSettings));
    // a result with skipped or failed items is a dialog (a clean one is OpsStore's toast)
    effect(() => { const r = this.ops.issues(); if (r) untracked(() => void this.showResult(r)); });
  }

  protected open(id: string) { this.router.navigate(['/album', id]); }
  protected openSettings(page?: SettingsPage) { this.settingsLink.open(page); }
  protected showInfo(id: string) { this.store.selectedId.set(id); this.prefs.detailsOpen.set(true); }
  protected toggleTab(tab: SidebarTab) { this.prefs.sidebar.update(v => v === tab ? 'none' : tab); }
  protected verb(p: OpProgress) { return opVerb(this.ops.opOf(p)); }

  /** Delete… of a copy the popover proved identical: the ordinary delete flow, the popover closed first (the dialog takes its place). */
  protected deleteCopy(a: Album) { this.catalog.copiesOf.set(null); void this.actions.delete({ albumIds: [a.id], albums: [a], complete: true, label: a.name }); }

  private async showResult(r: OpResult) {
    this.ops.issues.set(null);
    const k = this.ops.kindOf(r.id), what = k ? opNoun(k) : 'Operation';
    await this.confirm.ask({
      title: `${what}: ${r.done} done, ${r.skipped.length} skipped, ${r.failed.length} failed`, showAll: true, okLabel: 'Close', cancelLabel: null,
      items: [...r.skipped.map(s => `${s.item} — skipped: ${s.reason}`), ...r.failed.map(f => `${f.item} — failed: ${f.reason}`)],
    });
  }

  protected onKey(e: KeyboardEvent) {
    // any open modal owns the keyboard, even when its focus fell back to <body> (a focused row it removed): the target alone would not tell
    if (e.altKey || (e.target as HTMLElement)?.closest('input, textarea, select') || document.querySelector('dialog[open]')) return;
    const overlay = OVERLAY.test(this.router.url);
    if (e.ctrlKey || e.metaKey) {
      if (overlay || e.shiftKey || e.key.toLowerCase() !== 'a') return;
      void this.actions.selectAll();
      e.preventDefault();
      return;
    }
    switch (e.key) {
      case '/': if (overlay) return; this.topbar().focusSearch(); break;
      case 'e': case 'E': if (overlay) return; this.toggleTab('folders'); break;
      case 'f': case 'F': if (overlay) return; this.toggleTab('refine'); break;
      case 'i': case 'I': this.prefs.detailsOpen.update(v => !v); break;
      case 't': case 'T': this.prefs.toggleTheme(); break;
      case '[': this.prefs.setColumns(this.prefs.columns() - 1); break;
      case ']': this.prefs.setColumns(this.prefs.columns() + 1); break;
      case 'h': case 'H': if (overlay) return; this.actions.toggleHidden(); break;
      case 'Delete': if (overlay) return; this.actions.deleteSelection(); break;
      case 'Escape': {
        if (overlay) return;
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
