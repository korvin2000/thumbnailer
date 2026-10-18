import { afterRenderEffect, ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, untracked, viewChild } from '@angular/core';
import { FolderPicker, PickRequest } from '../../core/folder-picker';
import { Gateway } from '../../core/gateway';
import { Listing } from '../../core/model';
import { Icon } from '../shared/icon';

/** Modal folder browser over `Gateway.browse`; shows while `FolderPicker.open()` is set and settles the pending `pick()`. */
@Component({
  selector: 'app-folder-picker',
  imports: [Icon],
  template: `
    <dialog #dlg tabindex="-1" (cancel)="$event.preventDefault(); picker.cancel()" (close)="picker.cancel()" (keydown.escape)="picker.cancel()">
      @if (picker.open(); as req) {
        <header>
          <app-icon name="folderOpen" [size]="16" /><h2>{{ req.title }}</h2>
          <button class="btn icon-btn sm ghost" (click)="picker.cancel()" title="Cancel"><app-icon name="x" /></button>
        </header>
        <div class="bar">
          <button class="btn icon-btn sm" [disabled]="!listing()?.parent" (click)="go(listing()!.parent)" title="Up one level"><app-icon name="arrowUp" [size]="14" /></button>
          <nav class="crumbs">
            @for (c of crumbs(); track c.path) {
              @if (!$first) { <span class="sep">›</span> }
              <button (click)="go(c.path)" [title]="c.path">{{ c.name }}</button>
            }
          </nav>
          <form (submit)="go(pathBox.value, true); $event.preventDefault()">
            <input #pathBox class="input sm mono" [value]="listing()?.path ?? ''" placeholder="Path" spellcheck="false" />
            <button class="btn sm" type="submit">Go</button>
          </form>
        </div>
        <div class="body">
          <aside class="places">
            <span class="label">Places</span>
            <button [class.active]="current() === home()" (click)="go(home())"><app-icon name="home" [size]="14" /> Home</button>
            @for (d of listing()?.drives ?? []; track d) {
              <button [class.active]="current().toLowerCase().startsWith(d.toLowerCase()) && current() !== home()" (click)="go(d)"><app-icon name="drive" [size]="14" /> {{ d }}</button>
            }
          </aside>
          <section class="list">
            <div class="filter"><app-icon name="search" [size]="14" /><input #filterBox class="input sm" placeholder="Filter this folder…" [value]="filter()" (input)="filter.set($any($event.target).value)" (keydown.enter)="enterFirst()" /></div>
            @if (listing()?.error; as err) { <div class="error"><app-icon name="alert" [size]="14" /> {{ err }}</div> }
            <ul>
              @if (loading()) { <li class="state"><span class="spinner"></span> Loading…</li> }
              @else {
                @for (f of folders(); track f.path) {
                  <li tabindex="0" (click)="go(f.path)" (keydown.enter)="go(f.path)"><app-icon name="folder" [size]="15" />{{ f.name }}</li>
                } @empty { <li class="state">{{ filter() ? 'Nothing matches' : 'No subfolders' }}</li> }
              }
            </ul>
          </section>
        </div>
        <footer>
          <span class="chosen mono" [title]="chosen()">&lrm;{{ chosen() }}&lrm;</span>
          @if (willCreate()) { <em>will be created</em> }
          <button class="btn sm" (click)="picker.cancel()">Cancel</button>
          <button class="btn sm primary" [disabled]="!chosen() || loading()" (click)="picker.choose(chosen())">Use this folder</button>
        </footer>
      }
    </dialog>
  `,
  styles: `
    dialog { width: min(760px, 92vw); height: min(540px, 86vh); padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); overflow: hidden; }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.45); backdrop-filter: blur(2px); }
    dialog[open] { display: flex; flex-direction: column; animation: fade-up 0.18s var(--ease); }
    header { display: flex; align-items: center; gap: 10px; flex: none; height: 46px; padding: 0 10px 0 16px; border-bottom: 1px solid var(--border); }
    header app-icon { color: var(--accent); }
    h2 { flex: 1; font-size: 13.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .bar { display: flex; align-items: center; gap: 8px; flex: none; padding: 8px 12px; border-bottom: 1px solid var(--border); }
    .crumbs { display: flex; align-items: center; gap: 2px; flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; font-size: 12.5px; }
    .crumbs button { padding: 2px 5px; border-radius: 4px; color: var(--text-2); &:hover { background: var(--surface); color: var(--text); } }
    .crumbs .sep { color: var(--text-3); }
    .bar form { display: flex; gap: 6px; flex: 1; min-width: 0; }
    .body { display: flex; flex: 1; min-height: 0; }
    .places { display: flex; flex-direction: column; gap: 2px; flex: none; width: 150px; padding: 10px 8px; background: var(--bg); border-right: 1px solid var(--border); overflow: auto; }
    .places .label { padding: 0 8px 4px; }
    .places button { display: flex; align-items: center; gap: 8px; padding: 6px 9px; border: 1px solid transparent; border-radius: var(--radius-s); color: var(--text-2); font-size: 12.5px; text-align: left; }
    .places button app-icon { color: var(--text-3); }
    .places button:hover { background: var(--surface); color: var(--text); }
    .places button.active { background: var(--surface); border-color: var(--border-strong); color: var(--text); box-shadow: var(--shadow-1); }
    .places button.active app-icon { color: var(--accent); }
    .list { display: flex; flex-direction: column; flex: 1; min-width: 0; }
    .filter { display: flex; align-items: center; gap: 8px; flex: none; padding: 8px 12px; border-bottom: 1px solid var(--border); color: var(--text-3); }
    .error { display: flex; align-items: center; gap: 6px; margin: 8px 12px 0; padding: 6px 10px; border-radius: var(--radius-s); background: color-mix(in oklch, var(--danger) 12%, transparent); color: var(--danger); font-size: 12px; }
    ul { flex: 1; margin: 0; padding: 6px 8px; list-style: none; overflow: auto; }
    li { display: flex; align-items: center; gap: 9px; padding: 6px 10px; border-radius: var(--radius-s); font-size: 13px; cursor: pointer; user-select: none; }
    li app-icon { color: var(--accent); }
    li:hover, li:focus-visible { background: var(--surface); outline: none; }
    li.state { color: var(--text-3); cursor: default; gap: 8px; }
    li.state:hover { background: none; }
    footer { display: flex; align-items: center; gap: 8px; flex: none; padding: 10px 12px 10px 16px; border-top: 1px solid var(--border); }
    .chosen { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; color: var(--text-2); }
    footer em { font-style: normal; font-size: 11px; color: var(--accent-strong); white-space: nowrap; }
    @media (max-width: 600px) { .places { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FolderPickerDialog {
  protected readonly picker = inject(FolderPicker);
  private readonly gw = inject(Gateway);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  private readonly filterBox = viewChild<ElementRef<HTMLInputElement>>('filterBox');

  protected readonly listing = signal<Listing | null>(null);
  protected readonly loading = signal(false);
  protected readonly filter = signal('');
  /** The path the footer offers: the folder shown, or a typed one that does not exist yet (allowNew). */
  protected readonly chosen = signal('');
  protected readonly willCreate = computed(() => !!this.chosen() && this.chosen() !== this.listing()?.path);
  protected readonly current = computed(() => this.listing()?.path ?? '');
  protected readonly home = signal('');
  /** Bumped per navigation and on dismissal; a listing that arrives for an older one is dropped. */
  private gen = 0;
  protected readonly folders = computed(() => { const q = this.filter().trim().toLowerCase(); return (this.listing()?.folders ?? []).filter(f => !q || f.name.toLowerCase().includes(q)); });
  protected readonly crumbs = computed(() => {
    const p = this.current();
    if (!p) return [];
    const drive = p.match(/^[A-Za-z]:\//)?.[0], rest = p.slice(drive ? 3 : 1).split('/').filter(Boolean);
    const out = [{ name: drive ?? '/', path: drive ?? '/' }];
    for (const s of rest) out.push({ name: s, path: (out[out.length - 1].path.replace(/\/$/, '')) + '/' + s });
    return out;
  });

  constructor() {
    afterRenderEffect(() => {
      const req = this.picker.open(), dlg = this.dialog().nativeElement;
      untracked(() => { if (req) return void this.show(req); this.gen++; this.loading.set(false); if (dlg.open) dlg.close(); });
    });
  }

  private async show(req: PickRequest) {
    this.listing.set(null); this.filter.set(''); this.chosen.set('');
    const dlg = this.dialog().nativeElement;
    if (!dlg.open) dlg.showModal();
    if (!this.home()) this.home.set((await this.gw.browse(null)).path);
    if (this.picker.open() !== req) return; // dismissed (or replaced) while home loaded
    await this.go(req.startAt ?? this.home());
    this.filterBox()?.nativeElement.focus();
  }

  /** Navigates; a typed path that does not exist stays the chosen one in `allowNew` mode, while the list shows its nearest reachable ancestor. */
  protected async go(path: string | null, typed = false) {
    const want = path?.trim().replace(/\\/g, '/') ?? null;
    const gen = ++this.gen, stale = () => gen !== this.gen, dlg = this.dialog().nativeElement;
    // the folder rows go while loading: a focused one would drop the focus to <body>, where library keys could reach the grid behind the modal
    if (dlg.querySelector('ul')?.contains(document.activeElement)) dlg.focus();
    this.loading.set(true);
    try {
      const l = await this.gw.browse(want);
      if (stale()) return;
      this.listing.set(l); this.filter.set('');
      const keepTyped = typed && !!want && !!l.error && !!this.picker.open()?.allowNew && /^([A-Za-z]:)?\//.test(want);
      this.chosen.set(keepTyped ? want.replace(/\/+$/, '') || want : l.path);
    } catch (e) {
      if (stale()) return;
      this.listing.update(l => ({ ...(l ?? { path: '', parent: null, drives: [], folders: [] }), error: e instanceof Error ? e.message : String(e) }));
    } finally { if (!stale()) this.loading.set(false); }
  }

  protected enterFirst() { const f = this.folders()[0]; if (f) this.go(f.path); }
}
