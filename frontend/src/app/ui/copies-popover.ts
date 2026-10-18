import { DatePipe } from '@angular/common';
import { afterRenderEffect, ChangeDetectionStrategy, Component, computed, ElementRef, inject, output, signal, untracked, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { CatalogStore } from '../core/catalog-store';
import { BasenamePipe, BytesPipe } from '../core/format';
import { Gateway } from '../core/gateway';
import { Album, Comparison } from '../core/model';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Icon } from './shared/icon';

/** The copies of one album (`CatalogStore.copiesOf`, set by a tile's ×N badge or the details panel): a modal list with Open · Show in folder · Hide · Compare per copy. Delete… only after Compare said identical, and it waits for the file operations (F4: `deleteRequested`). */
@Component({
  selector: 'app-copies-popover',
  imports: [Icon, BytesPipe, BasenamePipe, DatePipe],
  template: `
    <dialog #dlg tabindex="-1" (cancel)="$event.preventDefault(); close()" (keydown.escape)="close(); $event.stopPropagation()" (click)="$event.target === dlg && close()">
      @if (catalog.copiesOf(); as id) {
        <header>
          <app-icon name="layers" [size]="16" /><h2>Copies of {{ source()?.name ?? '…' }}</h2>
          <button class="btn icon-btn sm ghost" (click)="close()" title="Close"><app-icon name="x" /></button>
        </header>
        <p class="hint">Same photo names and sizes at different paths. Compare reads both copies byte for byte.</p>
        <ul>
          @for (a of rows(); track a.id) {
            @let r = result()[a.id];
            <li [class.this]="a.id === id">
              <div class="top">
                <span class="path" [title]="a.path">{{ a.path }}</span>
                @if (a.id === id) { <span class="tag">this one</span> }
                @if (a.hidden) { <span class="tag"><app-icon name="eyeOff" [size]="11" /> hidden</span> }
              </div>
              <div class="meta">
                <span [title]="a.root">{{ a.root | basename }}</span> · {{ a.bytes | bytes }} · {{ a.imageCount }} photos · indexed {{ a.indexedAt | date: 'mediumDate' }}
                @if (r) { · <span [class.ok]="r.identical" [class.warn]="!r.identical" [title]="r.differing.join(', ')">{{ r.identical ? 'identical' : 'differ in ' + r.differing.length + (r.differing.length === 1 ? ' file' : ' files') }}</span> }
                @else if (a.id !== id) { · <span class="unverified">not compared</span> }
              </div>
              <div class="actions">
                <button class="btn sm" (click)="open(a)"><app-icon name="maximize" [size]="12" /> Open</button>
                <button class="btn sm" (click)="store.reveal(a.id)"><app-icon name="externalLink" [size]="12" /> Show in folder</button>
                @if (!a.hidden) { <button class="btn sm" (click)="hide(a)"><app-icon name="eyeOff" [size]="12" /> Hide</button> }
                @if (a.id !== id) {
                  <button class="btn sm" [disabled]="comparing() === a.id" (click)="compare(a)">@if (comparing() === a.id) { <span class="spinner"></span> } @else { <app-icon name="check" [size]="12" /> } Compare</button>
                  <button class="btn sm danger" [disabled]="!r?.identical" title="Compare first: only a copy proven identical can be deleted here" (click)="remove(a)"><app-icon name="trash" [size]="12" /> Delete…</button>
                }
              </div>
            </li>
          }
          @if (loading()) { <li class="state"><span class="spinner"></span> Loading…</li> }
          @else if (!rows().length) { <li class="state">No copies found</li> }
        </ul>
      }
    </dialog>
  `,
  styles: `
    dialog { outline: none; width: min(560px, 92vw); max-height: 80vh; padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); overflow: hidden; }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.45); backdrop-filter: blur(2px); }
    dialog[open] { display: flex; flex-direction: column; animation: fade-up 0.18s var(--ease); }
    header { display: flex; align-items: center; gap: 10px; flex: none; height: 46px; padding: 0 10px 0 16px; border-bottom: 1px solid var(--border); }
    header app-icon { color: var(--accent); }
    h2 { flex: 1; font-size: 13.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .hint { flex: none; padding: 10px 16px 4px; color: var(--text-3); font-size: 12px; }
    ul { flex: 1; min-height: 0; margin: 0; padding: 6px 10px 12px; list-style: none; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
    li { padding: 8px 10px; border-radius: var(--radius); border: 1px solid var(--border); background: var(--surface); }
    li.this { border-color: color-mix(in oklch, var(--accent) 50%, transparent); }
    .top { display: flex; align-items: center; gap: 6px; }
    .path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; font-family: var(--mono); font-size: 11.5px; }
    .tag { display: inline-flex; align-items: center; gap: 3px; flex: none; padding: 0 6px; border-radius: 999px; background: var(--accent-soft); color: var(--accent-strong); font-size: 10.5px; font-weight: 600; line-height: 16px; }
    .meta { margin: 3px 0 6px; color: var(--text-2); font-size: 11.5px; }
    .ok { color: var(--success); font-weight: 600; }
    .warn { color: var(--danger); font-weight: 600; }
    .unverified { color: var(--text-3); }
    .actions { display: flex; flex-wrap: wrap; gap: 4px; }
    .actions .btn { height: 24px; padding: 0 7px; font-size: 11.5px; }
    .danger:not(:disabled):hover { color: var(--danger); border-color: var(--danger); }
    .state { display: flex; align-items: center; gap: 8px; border: 0; background: none; color: var(--text-3); font-size: 12px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CopiesPopover {
  /** Delete… of a copy proven identical; the file operation itself lands with F4. */
  readonly deleteRequested = output<Album>();

  protected readonly catalog = inject(CatalogStore);
  protected readonly store = inject(AlbumStore);
  private readonly gw = inject(Gateway);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  protected readonly rows = signal<Album[]>([]);
  protected readonly loading = signal(false);
  protected readonly comparing = signal<string | null>(null);
  /** Compare results by the copy's id, for this opening only. */
  protected readonly result = signal<Record<string, Comparison>>({});
  protected readonly source = computed(() => { const id = this.catalog.copiesOf(); return this.rows().find(a => a.id === id) ?? this.store.byId().get(id ?? '') ?? null; });
  private gen = 0;

  constructor() {
    afterRenderEffect(() => {
      const id = this.catalog.copiesOf(), dlg = this.dialog().nativeElement;
      untracked(() => {
        if (!id) { this.gen++; if (dlg.open) dlg.close(); return; }
        this.rows.set([]); this.result.set({});
        void this.load(id);
        if (!dlg.open) dlg.showModal();
        dlg.focus(); // keyboard focus stays in the dialog (a clicked Hide button disappears), so Esc reaches it and not the grid
      });
    });
  }

  private async load(id: string) {
    const gen = ++this.gen;
    this.loading.set(true);
    try { const list = await this.gw.copies(id); if (gen === this.gen) this.rows.set(list); }
    catch (e) { if (gen === this.gen) { this.toasts.error(e, 'Copies failed: '); this.close(); } }
    finally { if (gen === this.gen) this.loading.set(false); }
  }

  protected close() { this.catalog.copiesOf.set(null); }

  protected open(a: Album) { this.close(); void this.router.navigate(['/album', a.id]); }

  protected async hide(a: Album) {
    if (await this.store.patch(a.id, { hidden: true })) { this.rows.update(l => l.map(x => x.id === a.id ? { ...x, hidden: true } : x)); this.toasts.show(`Hidden: ${a.name}`); this.dialog().nativeElement.focus(); }
  }

  protected async compare(a: Album) {
    const id = this.catalog.copiesOf();
    if (!id) return;
    this.comparing.set(a.id);
    try { const r = await this.gw.compareCopies(id, a.id); this.result.update(m => ({ ...m, [a.id]: r })); }
    catch (e) { this.toasts.error(e, 'Compare failed: '); }
    finally { this.comparing.set(null); }
  }

  protected remove(a: Album) {
    this.deleteRequested.emit(a);
    this.toasts.show('Deleting a copy arrives with the file operations');
  }
}
