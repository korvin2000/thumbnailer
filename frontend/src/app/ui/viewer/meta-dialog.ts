import { afterNextRender, ChangeDetectionStrategy, Component, computed, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { MetaRow } from '../../core/model';
import { Toasts } from '../../core/toasts';
import { Icon } from '../shared/icon';

/** Metadata of one photo as a filterable directory · tag · value table; mounted while open (`rows` null = loading), "Copy all" puts the table on the clipboard. */
@Component({
  selector: 'app-meta-dialog',
  imports: [Icon],
  template: `
    <dialog #dlg (cancel)="$event.preventDefault(); close.emit()" (click)="$event.target === dlg && close.emit()">
      <header>
        <app-icon name="info" [size]="16" /><h2 [title]="title()">{{ title() }}</h2>
        <input class="input sm" placeholder="Filter…" [value]="text()" (input)="text.set($any($event.target).value)" spellcheck="false" />
        <button class="btn sm" [disabled]="!rows()?.length" (click)="copyAll()" title="Copy the whole table"><app-icon name="copy" [size]="13" /> Copy all</button>
        <button class="btn icon-btn sm ghost" (click)="close.emit()" title="Close (Esc)"><app-icon name="x" /></button>
      </header>
      <div class="body">
        @if (rows(); as all) {
          @if (!all.length) { <p class="state">No metadata in this file.</p> }
          @else if (!shown().length) { <p class="state">Nothing matches “{{ text() }}”.</p> }
          @else {
            <table>
              @for (r of shown(); track $index) {
                <tr [class.dir]="r.first"><td class="d">{{ r.first ? r.dir : '' }}</td><td class="t">{{ r.tag }}</td><td class="v">{{ r.value }}</td></tr>
              }
            </table>
          }
        } @else { <p class="state"><span class="spinner"></span> Reading…</p> }
      </div>
      <footer>{{ rows()?.length ?? 0 }} tags @if (text() && rows()?.length) { · {{ shown().length }} shown }</footer>
    </dialog>
  `,
  styles: `
    dialog { width: min(760px, 94vw); height: min(560px, 86vh); padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); overflow: hidden; }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.45); backdrop-filter: blur(2px); }
    dialog[open] { display: flex; flex-direction: column; animation: fade-up 0.18s var(--ease); }
    header { display: flex; align-items: center; gap: 10px; flex: none; height: 46px; padding: 0 10px 0 16px; border-bottom: 1px solid var(--border); }
    header app-icon { color: var(--accent); }
    h2 { flex: 1; min-width: 60px; font-size: 13.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    header .input { width: 180px; }
    .body { flex: 1; overflow: auto; padding: 4px 8px 8px; }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    td { padding: 3px 8px; vertical-align: top; border-bottom: 1px solid color-mix(in oklch, var(--border) 50%, transparent); }
    tr.dir td { border-top: 1px solid var(--border); padding-top: 6px; }
    .d { width: 150px; color: var(--text-3); white-space: nowrap; font-weight: 600; }
    .t { width: 200px; color: var(--text-2); white-space: nowrap; }
    .v { font-family: var(--mono); word-break: break-word; }
    .state { display: flex; align-items: center; gap: 8px; padding: 24px 12px; color: var(--text-3); justify-content: center; }
    footer { flex: none; padding: 6px 16px; border-top: 1px solid var(--border); color: var(--text-3); font-size: 11.5px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetaDialog {
  readonly title = input.required<string>();
  readonly rows = input.required<MetaRow[] | null>();
  readonly close = output<void>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  private readonly toasts = inject(Toasts);

  protected readonly text = signal('');
  /** Rows matching the filter in any column; `first` marks the first row of each directory. */
  protected readonly shown = computed(() => {
    const q = this.text().trim().toLowerCase(), rows = (this.rows() ?? []).filter(r => !q || `${r.dir} ${r.tag} ${r.value}`.toLowerCase().includes(q));
    return rows.map((r, i) => ({ ...r, first: !i || rows[i - 1].dir !== r.dir }));
  });

  constructor() { afterNextRender(() => this.dialog().nativeElement.showModal()); }

  protected async copyAll() {
    const text = (this.rows() ?? []).map(r => `${r.dir}\t${r.tag}\t${r.value}`).join('\n');
    try { await navigator.clipboard.writeText(text); this.toasts.show('Metadata copied'); } catch (e) { this.toasts.error(e, 'Copy failed: '); }
  }
}
