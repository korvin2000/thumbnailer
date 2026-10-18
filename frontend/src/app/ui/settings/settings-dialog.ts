import { afterRenderEffect, ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, signal, untracked, viewChild } from '@angular/core';
import { DEFAULT_SETTINGS, Settings } from '../../core/model';
import { AlbumStore } from '../../core/store';
import { Toasts } from '../../core/toasts';
import { Icon, IconName } from '../shared/icon';
import { AboutPage } from './about-page';
import { AppearancePage } from './appearance-page';
import { FoldersPage } from './folders-page';
import { KeyboardPage } from './keyboard-page';
import { LibraryPage } from './library-page';
import { OperationsPage } from './operations-page';
import { SettingsLink, SettingsPage as Page } from './settings-link';
import { ThumbnailsPage } from './thumbnails-page';

const PAGES: { id: Page; label: string; icon: IconName }[] = [
  { id: 'library', label: 'Library', icon: 'database' }, { id: 'folders', label: 'Folders', icon: 'folder' }, { id: 'thumbnails', label: 'Thumbnails', icon: 'image' },
  { id: 'operations', label: 'Operations', icon: 'archive' },
  { id: 'appearance', label: 'Appearance', icon: 'sun' }, { id: 'keyboard', label: 'Keyboard', icon: 'keyboard' }, { id: 'about', label: 'About', icon: 'info' },
];

/** Native <dialog> with a page rail, loaded lazily on the first `SettingsLink.open(page)`. One draft `Settings` is shared by Folders, Thumbnails and Operations and saved from the footer; Appearance applies instantly. */
@Component({
  selector: 'app-settings-dialog',
  imports: [Icon, LibraryPage, FoldersPage, ThumbnailsPage, OperationsPage, AppearancePage, KeyboardPage, AboutPage],
  template: `
    <dialog #dlg (cancel)="close()">
      <header>
        <h2><app-icon name="sliders" [size]="16" /> Settings</h2>
        <span class="file mono" [title]="file()">{{ file() }}</span>
        <button class="btn icon-btn sm ghost" (click)="close()" title="Close"><app-icon name="x" /></button>
      </header>
      <div class="body">
        <nav class="rail" role="tablist" aria-label="Settings pages" (keydown)="railKey($event)">
          @for (p of pages; track p.id) {
            <button role="tab" [class.active]="page() === p.id" [attr.aria-selected]="page() === p.id" [tabindex]="page() === p.id ? 0 : -1" [title]="p.label" (click)="page.set(p.id)">
              <app-icon [name]="p.icon" [size]="16" /><span>{{ p.label }}</span>
            </button>
          }
        </nav>
        <div class="content">
          <div class="scroll">
            @switch (page()) {
              @case ('library') { <app-library-page /> }
              @case ('folders') { <app-folders-page [(draft)]="draft" /> }
              @case ('thumbnails') { <app-thumbnails-page [(draft)]="draft" /> }
              @case ('operations') { <app-operations-page [(draft)]="draft" /> }
              @case ('appearance') { <app-appearance-page /> }
              @case ('keyboard') { <app-keyboard-page /> }
              @case ('about') { <app-about-page /> }
            }
          </div>
          @if (dirty()) {
            <footer class="unsaved">
              <app-icon name="alert" [size]="14" /><span>{{ blocked() ? 'Stop the scan before changing folders' : 'Unsaved changes' }}</span>
              <button class="btn sm" (click)="revert()">Revert</button>
              <button class="btn sm primary" [disabled]="blocked()" (click)="save()"><app-icon name="check" [size]="13" /> Save</button>
            </footer>
          }
        </div>
      </div>
      <div class="toasts">
        @for (t of toasts.list(); track t.id) { <div class="toast" [class]="t.kind" (click)="toasts.dismiss(t.id)">{{ t.text }}</div> }
      </div>
    </dialog>
  `,
  styles: `
    dialog { width: min(960px, calc(100vw - 32px)); height: min(680px, calc(100vh - 48px)); padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); overflow: hidden; }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.55); backdrop-filter: blur(3px); }
    dialog[open] { display: flex; flex-direction: column; animation: fade-up 0.18s var(--ease); }
    header { display: flex; align-items: center; gap: 12px; flex: none; height: 48px; padding: 0 10px 0 18px; border-bottom: 1px solid var(--border); }
    h2 { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; white-space: nowrap; }
    .file { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-3); font-size: 11.5px; }
    .body { display: flex; flex: 1; min-height: 0; }
    .rail { display: flex; flex-direction: column; gap: 2px; flex: none; width: 190px; padding: 10px; background: var(--bg); border-right: 1px solid var(--border); overflow: auto; }
    .rail button { display: flex; align-items: center; gap: 9px; padding: 7px 10px; border: 1px solid transparent; border-radius: var(--radius-s); color: var(--text-2); font-size: 12.5px; font-weight: 500; text-align: left; white-space: nowrap; transition: background 0.15s, color 0.15s, border-color 0.15s; }
    .rail button app-icon { color: var(--text-3); transition: color 0.15s; }
    .rail button:hover { background: var(--surface); color: var(--text); }
    .rail button.active { background: var(--surface); border-color: var(--accent); color: var(--text); box-shadow: var(--shadow-1); }
    .rail button.active app-icon { color: var(--accent); }
    .content { display: flex; flex-direction: column; flex: 1; min-width: 0; }
    .scroll { flex: 1; overflow: auto; padding: 18px 24px 24px; }
    .unsaved { display: flex; align-items: center; gap: 8px; flex: none; padding: 8px 16px 8px 20px; border-top: 1px solid var(--border); background: var(--bg); font-size: 12.5px; color: var(--text-2); animation: fade-up 0.15s var(--ease); }
    .unsaved > app-icon { color: var(--accent); }
    .unsaved span { flex: 1; }
    .toasts { position: absolute; left: 50%; bottom: 14px; z-index: 5; display: flex; flex-direction: column; gap: 6px; transform: translateX(-50%); pointer-events: none; }
    .toast { padding: 8px 14px; border-radius: var(--radius); background: var(--glass); backdrop-filter: blur(12px); border: 1px solid var(--border); box-shadow: var(--shadow-2); font-size: 12.5px; pointer-events: auto; cursor: pointer; animation: fade-up 0.2s var(--ease); white-space: nowrap; }
    .toast.success { border-color: color-mix(in oklch, var(--success) 50%, transparent); }
    .toast.error { border-color: color-mix(in oklch, var(--danger) 60%, transparent); color: var(--danger); white-space: normal; max-width: 70vw; }
    @media (max-width: 720px) {
      .rail { width: 48px; padding: 8px 6px; }
      .rail button { justify-content: center; padding: 7px 0; }
      .rail button span { display: none; }
      .scroll { padding: 14px 14px 18px; }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsDialog {
  protected readonly store = inject(AlbumStore);
  protected readonly toasts = inject(Toasts);
  private readonly link = inject(SettingsLink);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  protected readonly pages = PAGES;
  protected readonly page = signal<Page>('library');
  protected readonly draft = signal<Settings>(DEFAULT_SETTINGS);
  protected readonly saved = computed(() => this.store.settings()?.settings ?? DEFAULT_SETTINGS);
  protected readonly dirty = computed(() => JSON.stringify(this.draft()) !== JSON.stringify(this.saved()));
  /** The server refuses folder changes (roots, path map) while a scan runs. */
  protected readonly blocked = computed(() => { const d = this.draft(), s = this.saved(); return !!this.store.scan()?.running && (JSON.stringify(d.roots) !== JSON.stringify(s.roots) || JSON.stringify(d.pathMap) !== JSON.stringify(s.pathMap)); });
  protected readonly file = computed(() => { const d = this.store.settings()?.dataDir; return d ? `${d}/settings.json` : '…'; });

  private seeded = false;
  /** The library (`dataDir`) the draft was seeded from: a draft never outlives its library. */
  private seededDir: string | undefined;
  /** Last known library and its saved settings, to tell whether a draft discarded by a switch held edits. */
  private lib: { dir: string; json: string } | null = null;

  constructor() {
    effect(() => {
      const info = this.store.settings();
      untracked(() => {
        if (!info) return;
        const prev = this.lib && this.lib.dir === this.seededDir ? this.lib.json : null;
        this.lib = { dir: info.dataDir, json: JSON.stringify(info.settings) };
        if (!this.seeded || info.dataDir === this.seededDir) return;
        const wasDirty = prev !== null && JSON.stringify(this.draft()) !== prev;
        this.reseed();
        if (wasDirty) this.toasts.show('Unsaved settings of the previous library were discarded');
      });
    });
    // a page asked for through `SettingsLink` (the brand button, the viewer, the `thumbnailer:settings` event), also the one that loaded this dialog
    afterRenderEffect(() => { const p = this.link.request(); if (p) untracked(() => { this.link.request.set(null); this.open(p); }); });
  }

  /** Unsaved edits survive a close; the draft is only re-seeded when it holds nothing of its own. */
  open(page?: Page) {
    if (page) this.page.set(page);
    if (!this.seeded || !this.dirty()) { this.reseed(); this.seeded = true; }
    void this.store.loadLibrary(); // the pages live in the DOM while closed, so their first load may predate the scan
    this.dialog().nativeElement.showModal();
  }

  close() { this.dialog().nativeElement.close(); }

  protected revert() { this.draft.set(structuredClone(this.saved())); }

  private reseed() {
    this.seededDir = this.store.settings()?.dataDir;
    this.draft.set(structuredClone(this.saved()));
  }

  /** Edits made while the save is in flight survive it: the draft is reset only if it is still the one submitted. */
  protected async save() {
    const sent = this.draft(), json = JSON.stringify(sent);
    if (await this.store.saveSettings(sent) && JSON.stringify(this.draft()) === json) this.draft.set(structuredClone(this.saved()));
  }

  protected railKey(e: KeyboardEvent) {
    const step = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const i = (PAGES.findIndex(p => p.id === this.page()) + step + PAGES.length) % PAGES.length;
    this.page.set(PAGES[i].id);
    (e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button')[i]?.focus();
  }
}
