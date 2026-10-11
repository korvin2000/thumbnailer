import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, viewChild } from '@angular/core';
import { RootStatus, Settings } from '../core/model';
import { Prefs } from '../core/prefs';
import { AlbumStore } from '../core/store';
import { Icon } from './shared/icon';

const EMPTY: Settings = { roots: [], exclusions: [], scanOnStart: true };

/** Native <dialog>: library roots and exclusions (saved explicitly), appearance (applies instantly), scan control. */
@Component({
  selector: 'app-settings-dialog',
  imports: [Icon, DatePipe],
  template: `
    <dialog #dlg (cancel)="close()">
      <header>
        <h2><app-icon name="sliders" [size]="16" /> Settings</h2>
        <button class="btn icon-btn sm ghost" (click)="close()" title="Close"><app-icon name="x" /></button>
      </header>
      <div class="body">
        <section>
          <h3>Library</h3>
          <p class="hint">Folders to index. A folder holding photos, or a zip of photos, is one album; nested folders are albums of their own. Exclusions are glob patterns with <code>*</code>, matched against the full path.</p>
          @for (r of draft().roots; track $index) {
            <div class="root">
              <div class="head">
                <app-icon name="folder" [size]="14" />
                <input class="input mono" [value]="r.path" (change)="setRoot($index, { path: $any($event.target).value })" />
                @if (status(r.path); as st) {
                  <span class="status" [class.missing]="!st.exists" [title]="st.lastScanAt ? 'Last scan ' + (st.lastScanAt | date: 'medium') : 'Never scanned'">
                    {{ st.exists ? st.albumCount + ' albums' : 'not reachable' }}
                  </span>
                }
                <button class="btn icon-btn sm ghost" (click)="removeRoot($index)" title="Remove folder"><app-icon name="trash" [size]="14" /></button>
              </div>
              <input class="input sm mono" placeholder="Exclusions for this folder, e.g. */raw/*, *_tmp*" [value]="r.exclusions.join(', ')" (change)="setRoot($index, { exclusions: split($any($event.target).value) })" />
            </div>
          }
          <form class="add" (submit)="addRoot(np.value); np.value = ''; $event.preventDefault()">
            <input #np class="input mono" placeholder="Add a folder, e.g. D:\\Photos or /mnt/archive" />
            <button class="btn" type="submit"><app-icon name="plus" [size]="14" /> Add</button>
          </form>
          <label class="field"><span>Global exclusions</span><input class="input mono" [value]="draft().exclusions.join(', ')" (change)="patchDraft({ exclusions: split($any($event.target).value) })" placeholder="*/.*, */Thumbs.db" /></label>
          <label class="check"><input type="checkbox" [checked]="draft().scanOnStart" (change)="patchDraft({ scanOnStart: $any($event.target).checked })" /> Scan on start</label>
          <div class="row">
            <button class="btn primary" [disabled]="!dirty()" (click)="save()"><app-icon name="check" [size]="14" /> Save library</button>
            @if (store.scan()?.running) {
              <button class="btn" (click)="store.cancelScan()">Cancel scan</button>
            } @else {
              <button class="btn" (click)="store.startScan()" [disabled]="dirty() || !draft().roots.length" title="Index new, changed, moved and removed albums"><app-icon name="scan" [size]="14" /> Scan now</button>
            }
            <span class="hint data">Data: <code>{{ store.settings()?.dataDir || '…' }}</code></span>
          </div>
          @if (store.scan(); as s) {
            <div class="progress" [class.running]="s.running">
              <div class="track"><div class="fill" [style.width.%]="s.discovered ? Math.min(100, 100 * s.indexed / s.discovered) : 0"></div></div>
              <span>{{ s.running ? 'Scanning ' + s.root : 'Last scan' }} · {{ s.indexed }} / {{ s.discovered }} · {{ s.changed }} changed · {{ s.removed }} removed @if (s.message) { · {{ s.message }} }</span>
            </div>
          }
        </section>

        <section>
          <h3>Appearance</h3>
          <div class="field"><span>Theme</span>
            <div class="segmented">
              <button [class.active]="prefs.theme() === 'system'" (click)="prefs.theme.set('system')"><app-icon name="monitor" [size]="13" />System</button>
              <button [class.active]="prefs.theme() === 'light'" (click)="prefs.theme.set('light')"><app-icon name="sun" [size]="13" />Light</button>
              <button [class.active]="prefs.theme() === 'dark'" (click)="prefs.theme.set('dark')"><app-icon name="moon" [size]="13" />Dark</button>
            </div></div>
          <div class="field"><span>Layout</span>
            <div class="segmented">
              <button [class.active]="prefs.layoutMode() === 'bricks'" (click)="prefs.layoutMode.set('bricks')"><app-icon name="bricks" [size]="13" />Bricks</button>
              <button [class.active]="prefs.layoutMode() === 'grid'" (click)="prefs.layoutMode.set('grid')"><app-icon name="grid" [size]="13" />Grid</button>
            </div></div>
          <label class="field"><span>Albums per row</span><span class="slider"><input type="range" min="1" max="12" [value]="prefs.columns()" (input)="prefs.setColumns(+$any($event.target).value)" /><b>{{ prefs.columns() }}</b></span></label>
          <label class="check"><input type="checkbox" [checked]="prefs.showTags()" (change)="prefs.showTags.set($any($event.target).checked)" /> Show tags on album cards</label>
          <label class="check"><input type="checkbox" [checked]="prefs.followHover()" (change)="prefs.followHover.set($any($event.target).checked)" /> Details panel follows the hovered album</label>
        </section>

        <section class="keys">
          <h3>Keyboard</h3>
          <div><kbd>/</kbd> search <kbd>F</kbd> refine <kbd>I</kbd> details <kbd>T</kbd> theme <kbd>[</kbd><kbd>]</kbd> per row <kbd>↑↓←→</kbd> move <kbd>Enter</kbd> open <kbd>C</kbd> use as cover <kbd>Esc</kbd> close</div>
        </section>
      </div>
    </dialog>
  `,
  styles: `
    dialog { width: min(720px, calc(100vw - 24px)); max-height: calc(100vh - 48px); padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.55); backdrop-filter: blur(3px); }
    dialog[open] { animation: fade-up 0.18s var(--ease); }
    header { display: flex; align-items: center; justify-content: space-between; height: 48px; padding: 0 10px 0 18px; border-bottom: 1px solid var(--border); }
    h2 { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; }
    .body { overflow: auto; max-height: calc(100vh - 100px); padding: 4px 18px 18px; }
    section { padding: 14px 0; border-bottom: 1px solid var(--border); }
    section:last-child { border-bottom: 0; }
    h3 { margin-bottom: 8px; font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--text-3); }
    .hint { color: var(--text-2); font-size: 12px; margin-bottom: 10px; }
    .root { display: flex; flex-direction: column; gap: 5px; padding: 8px 10px; margin-bottom: 6px; border-radius: var(--radius); background: var(--surface); }
    .head { display: flex; align-items: center; gap: 8px; }
    .status { font-size: 11px; color: var(--success); white-space: nowrap; }
    .status.missing { color: var(--danger); }
    .add { display: flex; gap: 6px; margin: 6px 0 10px; }
    .field { display: grid; grid-template-columns: 150px 1fr; align-items: center; gap: 10px; margin: 8px 0; font-size: 12.5px; color: var(--text-2); }
    .check { display: flex; align-items: center; gap: 8px; margin: 8px 0; font-size: 12.5px; cursor: pointer; }
    .row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
    .data { margin-left: auto; font-size: 11.5px; }
    .slider { display: inline-flex; align-items: center; gap: 10px; }
    .slider input { width: 200px; }
    .progress { margin-top: 10px; font-size: 11.5px; color: var(--text-2); }
    .track { height: 4px; border-radius: 2px; background: var(--border); margin-bottom: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--accent); transition: width 0.3s; }
    .running .fill { background-image: linear-gradient(90deg, transparent, oklch(100% 0 0 / 0.35), transparent); background-size: 200% 100%; animation: shimmer 1.2s linear infinite; }
    @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }
    .keys div { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; font-size: 12px; color: var(--text-2); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsDialog {
  protected readonly store = inject(AlbumStore);
  protected readonly prefs = inject(Prefs);
  protected readonly Math = Math;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  protected readonly draft = signal<Settings>(EMPTY);
  protected readonly dirty = computed(() => JSON.stringify(this.draft()) !== JSON.stringify(this.store.settings()?.settings ?? EMPTY));

  open() {
    this.draft.set(structuredClone(this.store.settings()?.settings ?? EMPTY));
    this.dialog().nativeElement.showModal();
  }

  close() { this.dialog().nativeElement.close(); }

  protected status(path: string): RootStatus | undefined { return this.store.settings()?.roots.find(r => r.path === path); }
  protected split(text: string): string[] { return text.split(/[,\n]/).map(s => s.trim()).filter(Boolean); }
  protected patchDraft(p: Partial<Settings>) { this.draft.update(d => ({ ...d, ...p })); }
  protected setRoot(i: number, p: Partial<Settings['roots'][number]>) { this.draft.update(d => ({ ...d, roots: d.roots.map((r, j) => j === i ? { ...r, ...p } : r) })); }
  protected removeRoot(i: number) { this.draft.update(d => ({ ...d, roots: d.roots.filter((_, j) => j !== i) })); }

  protected addRoot(raw: string) {
    const path = raw.trim().replace(/\\/g, '/').replace(/\/+$/, '');
    if (path && !this.draft().roots.some(r => r.path === path)) this.draft.update(d => ({ ...d, roots: [...d.roots, { path, exclusions: [] }] }));
  }

  protected async save() {
    if (await this.store.saveSettings(this.draft())) this.draft.set(structuredClone(this.store.settings()!.settings));
  }
}
