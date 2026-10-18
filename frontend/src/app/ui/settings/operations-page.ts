import { ChangeDetectionStrategy, Component, computed, inject, model } from '@angular/core';
import { FolderPicker } from '../../core/folder-picker';
import { PackFormat, Settings } from '../../core/model';
import { normPath } from '../../core/paths';
import { AlbumStore } from '../../core/store';
import { Toasts } from '../../core/toasts';
import { Icon } from '../shared/icon';

const MAX_TARGETS = 10;
const FORMATS: { id: PackFormat; label: string }[] = [{ id: 'AUTO', label: 'Auto' }, { id: 'ZIP', label: 'Zip' }, { id: 'SEVEN_ZIP', label: '7z' }];

/** Copy targets and the default pack format, edited in the shared draft. */
@Component({
  selector: 'app-operations-page',
  imports: [Icon],
  template: `
    <h3>Operations</h3>
    <p class="hint">Where "Copy to" sends photos, and how albums are packed.</p>

    <section class="card">
      <h4>Copy targets</h4>
      <p class="hint">Up to {{ max }} folders. In the viewer, keys 1–9 and 0 copy the selected photos to them.</p>
      @for (t of targets(); track t) {
        <div class="target">
          <kbd>{{ ($index + 1) % 10 }}</kbd>
          <span class="path mono" [title]="t">&lrm;{{ t }}&lrm;</span>
          <button class="btn icon-btn sm ghost" [disabled]="$first" (click)="move($index, -1)" title="Move up"><app-icon name="arrowUp" [size]="14" /></button>
          <button class="btn icon-btn sm ghost" [disabled]="$last" (click)="move($index, 1)" title="Move down"><app-icon name="arrowUp" [size]="14" class="flip" /></button>
          <button class="btn icon-btn sm ghost" (click)="setTargets(without($index))" title="Remove"><app-icon name="trash" [size]="14" /></button>
        </div>
      } @empty { <p class="hint muted">No copy targets yet.</p> }
      <button class="btn sm add" [disabled]="targets().length >= max" (click)="add()"><app-icon name="folderOpen" [size]="14" /> Add folder…</button>
    </section>

    <section class="card">
      <h4>Packing</h4>
      <div class="field"><span>Pack format</span>
        <div class="segmented">
          @for (f of formats; track f.id) { <button [class.active]="packFormat() === f.id" (click)="patch({ ops: { ...draft().ops, packFormat: f.id } })">{{ f.label }}</button> }
        </div></div>
      <p class="hint muted">Auto writes a zip without recompression: the photos stay byte-identical and open fast.</p>
    </section>
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
    .target { display: flex; align-items: center; gap: 8px; padding: 4px 6px 4px 10px; margin-bottom: 4px; border-radius: var(--radius-s); background: var(--bg-elev); border: 1px solid var(--border); }
    .path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
    .add { margin-top: 4px; }
    .flip { transform: rotate(180deg); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OperationsPage {
  readonly draft = model.required<Settings>();
  private readonly picker = inject(FolderPicker);
  private readonly store = inject(AlbumStore);
  private readonly toasts = inject(Toasts);
  protected readonly max = MAX_TARGETS;
  protected readonly formats = FORMATS;
  /** Settings from a server without these fields read as empty / Auto. */
  protected readonly targets = computed(() => this.draft().copyTargets ?? []);
  protected readonly packFormat = computed(() => this.draft().ops?.packFormat ?? 'AUTO');

  protected patch(p: Partial<Settings>) { this.draft.update(d => ({ ...d, ...p })); }
  protected setTargets(copyTargets: string[]) { this.patch({ copyTargets }); }
  protected without(i: number) { return this.targets().filter((_, j) => j !== i); }

  protected move(i: number, step: number) {
    const t = [...this.targets()];
    [t[i], t[i + step]] = [t[i + step], t[i]];
    this.setTargets(t);
  }

  protected async add() {
    const picked = await this.picker.pick({ title: 'Choose a copy target', startAt: this.store.library()?.home ?? null });
    if (!picked) return;
    const path = normPath(picked);
    if (this.targets().some(t => t.toLowerCase() === path.toLowerCase())) return this.toasts.show('Already a copy target');
    if (this.targets().length < MAX_TARGETS) this.setTargets([...this.targets(), path]);
  }
}
