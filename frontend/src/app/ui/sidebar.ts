import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Prefs } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { FiltersPanel } from './filters-panel';
import { FolderTree } from './folder-tree';
import { Icon } from './shared/icon';

/** Left panel with two tabs: Folders (navigation tree) and Refine (search criteria); `Prefs.sidebar` picks the tab. */
@Component({
  selector: 'app-sidebar',
  imports: [Icon, FolderTree, FiltersPanel],
  template: `
    <header>
      <div class="segmented">
        <button [class.active]="prefs.sidebar() === 'folders'" (click)="prefs.sidebar.set('folders')" title="Folders (E)"><app-icon name="folder" [size]="14" />Folders</button>
        <button [class.active]="prefs.sidebar() === 'refine'" (click)="prefs.sidebar.set('refine')" title="Refine (F)">
          <app-icon name="filter" [size]="14" />Refine @if (store.refining()) { <span class="badge">{{ store.refining() }}</span> }
        </button>
      </div>
      <span class="grow"></span>
      @if (prefs.sidebar() === 'refine') { <button class="btn ghost sm" (click)="store.resetRefine()" [disabled]="!store.refining() && !store.text()">Reset</button> }
      <button class="btn icon-btn sm ghost" (click)="prefs.sidebar.set('none')" title="Close sidebar (Esc)"><app-icon name="x" /></button>
    </header>
    <div class="body">
      @if (prefs.sidebar() === 'refine') { <app-filters-panel /> } @else { <app-folder-tree /> }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; width: var(--filters-w); height: 100%; background: var(--bg-elev); border-right: 1px solid var(--border); }
    header { display: flex; flex: none; align-items: center; gap: 4px; height: 44px; padding: 0 8px 0 10px; border-bottom: 1px solid var(--border); }
    .grow { flex: 1; }
    .badge { min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: var(--accent); color: var(--on-accent); font-size: 10px; font-weight: 700; line-height: 16px; text-align: center; }
    .body { flex: 1; min-height: 0; overflow: auto; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sidebar {
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
}
