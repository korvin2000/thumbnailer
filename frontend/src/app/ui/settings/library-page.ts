import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FolderPicker } from '../../core/folder-picker';
import { BytesPipe } from '../../core/format';
import { AlbumStore } from '../../core/store';
import { Icon } from '../shared/icon';

const R = 60, C = 2 * Math.PI * R, GAP = 2;
interface Segment { key: 'covers' | 'meta' | 'over'; label: string; bytes: number; pct: number; dash: string; offset: number }

/** Library page: known libraries (switch/forget/add), composition donut + stats, optimize. */
@Component({
  selector: 'app-library-page',
  imports: [Icon, BytesPipe, DatePipe, DecimalPipe],
  template: `
    <h3>Library</h3>
    <p class="hint">An index of scanned albums: metadata database, stored covers and settings.json in one folder. Keep several and switch between them.</p>

    <section class="card">
      <h4>Libraries</h4>
      @if (store.library(); as lib) {
        @if (lib.pinned) { <p class="hint">Fixed by <code>--thumbnailer.library-dir</code>; switching is disabled.</p> }
        @for (dir of lib.known; track dir) {
          <div class="row" [class.active]="dir === lib.dir">
            <app-icon name="drive" [size]="15" />
            <span class="path mono" [title]="dir">{{ dir }}</span>
            @if (dir === lib.dir) {
              <span class="badge">ACTIVE</span>
            } @else {
              <button class="btn sm" [disabled]="lib.pinned" (click)="store.switchLibrary(dir)">Switch</button>
              <button class="btn sm icon-btn ghost" [disabled]="lib.pinned" title="Forget this library (files are kept)" (click)="store.forgetLibrary(dir)"><app-icon name="trash" [size]="14" /></button>
            }
          </div>
        }
        <button class="btn sm add" [disabled]="lib.pinned" (click)="add()"><app-icon name="plus" [size]="14" /> Add library…</button>
      } @else {
        <div class="row skeleton"><span class="spinner"></span><span class="hint">Loading…</span></div>
      }
    </section>

    <section class="card">
      <h4>Contents</h4>
      @if (store.library()?.stats; as s) {
        <div class="contents">
          <svg class="donut" viewBox="0 0 148 148" role="img" [attr.aria-label]="'Library files: ' + (total() | bytes)">
            <circle cx="74" cy="74" [attr.r]="r" class="track" />
            @for (seg of segments(); track seg.key) {
              @if (seg.bytes > 0) {
                <circle cx="74" cy="74" [attr.r]="r" [class]="seg.key" [attr.stroke-dasharray]="seg.dash" [attr.stroke-dashoffset]="seg.offset"><title>{{ seg.label }}: {{ seg.bytes | bytes }} ({{ seg.pct | number: '1.0-0' }}%)</title></circle>
              }
            }
            <text x="74" y="70" class="big">{{ total() | bytes }}</text>
            <text x="74" y="88" class="small">on disk</text>
          </svg>
          <div class="side">
            <ul class="legend">
              @for (seg of segments(); track seg.key) {
                <li><i [class]="seg.key"></i><span>{{ seg.label }}</span><b>{{ seg.bytes | bytes }}</b><em>{{ seg.pct | number: '1.0-0' }}%</em></li>
              }
            </ul>
            <dl class="stats">
              <dt>Albums</dt><dd>{{ s.albums | number }}</dd>
              <dt>Photos</dt><dd>{{ s.images | number }}</dd>
              <dt>Photos on disk</dt><dd>{{ s.imageBytes | bytes }}</dd>
              <dt>Covers stored</dt><dd>{{ s.coversCount | number }}</dd>
              <dt>Last update</dt><dd>{{ s.lastIndexedAt ? (s.lastIndexedAt | date: 'medium') : 'never' }}</dd>
              <dt>Database files</dt><dd>{{ s.metadataFile | bytes }}</dd>
              <dt>Cover store</dt><dd>{{ s.coversFile | bytes }}</dd>
            </dl>
          </div>
        </div>
        <div class="actions">
          <button class="btn sm" [disabled]="optimizing() || store.scan()?.running" (click)="optimize()">
            @if (optimizing()) { <span class="spinner"></span> } @else { <app-icon name="sparkles" [size]="14" /> }
            Optimize
          </button>
          <span class="hint">Compacts the cover store and the database; the app restarts for a few seconds. Space freed by rescans and edits is returned to disk.</span>
        </div>
      } @else {
        <div class="contents"><div class="donut ph"></div><div class="side"><div class="ph line"></div><div class="ph line"></div><div class="ph line short"></div></div></div>
      }
    </section>
  `,
  styles: `
    :host { display: block; --c-covers: oklch(66% 0.14 75); --c-meta: oklch(62% 0.13 245); --c-over: oklch(47% 0.012 260); }
    :host-context([data-theme='light']) { --c-covers: oklch(64% 0.16 62); --c-meta: oklch(52% 0.14 245); --c-over: oklch(62% 0.012 260); }
    @media (prefers-color-scheme: light) { :host-context(:root:not([data-theme='dark'])) { --c-covers: oklch(64% 0.16 62); --c-meta: oklch(52% 0.14 245); --c-over: oklch(62% 0.012 260); } }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
    .row { display: flex; align-items: center; gap: 8px; min-height: 34px; padding: 3px 8px; border-radius: var(--radius-s); }
    .row:hover { background: var(--surface-2); }
    .row app-icon { color: var(--text-3); }
    .row.active app-icon { color: var(--accent); }
    .path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .badge { padding: 2px 7px; border-radius: 4px; background: var(--accent-soft); color: var(--accent-strong); font-size: 10px; font-weight: 700; letter-spacing: 0.06em; }
    .add { margin-top: 6px; }
    .skeleton { color: var(--text-3); }
    .contents { display: flex; gap: 22px; align-items: center; margin-top: 8px; }
    .donut { width: 148px; height: 148px; flex: none; transform: rotate(-90deg); }
    .donut circle { fill: none; stroke-width: 14; }
    .donut .track { stroke: var(--surface-2); }
    .donut text { transform: rotate(90deg); transform-origin: 74px 74px; text-anchor: middle; fill: var(--text); font: 600 15px var(--font); }
    .donut .small { fill: var(--text-3); font: 500 10.5px var(--font); }
    .covers { stroke: var(--c-covers); background: var(--c-covers); }
    .meta { stroke: var(--c-meta); background: var(--c-meta); }
    .over { stroke: var(--c-over); background: var(--c-over); }
    .side { flex: 1; min-width: 0; }
    .legend { list-style: none; margin: 0 0 12px; padding: 0; display: grid; gap: 4px; }
    .legend li { display: grid; grid-template-columns: 10px 1fr auto 42px; align-items: center; gap: 8px; font-size: 12.5px; }
    .legend i { width: 10px; height: 10px; border-radius: 50%; }
    .legend b { font-weight: 500; font-family: var(--mono); font-size: 12px; }
    .legend em { font-style: normal; text-align: right; color: var(--text-3); font-size: 11.5px; }
    .stats { display: grid; grid-template-columns: repeat(3, auto 1fr); gap: 4px 10px; margin: 0; font-size: 12px; }
    .stats dt { color: var(--text-3); white-space: nowrap; }
    .stats dd { margin: 0; font-weight: 500; white-space: nowrap; }
    .actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border); }
    .ph { border-radius: 50%; background: var(--surface-2); animation: pulse 1.2s ease-in-out infinite; }
    .ph.line { height: 12px; border-radius: 4px; margin-bottom: 8px; width: 60%; }
    .ph.line.short { width: 40%; }
    @keyframes pulse { 50% { opacity: 0.5; } }
    @media (max-width: 720px) { .contents { flex-direction: column; } .stats { grid-template-columns: auto 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LibraryPage {
  protected readonly store = inject(AlbumStore);
  private readonly picker = inject(FolderPicker);
  protected readonly r = R;
  protected readonly optimizing = signal(false);

  protected readonly total = computed(() => { const s = this.store.library()?.stats; return s ? s.coversFile + s.metadataFile : 0; });
  /** Covers / metadata = live bytes; overhead = what the files hold beyond that (reclaimed by Optimize). Each arc leaves a 2px surface gap. */
  protected readonly segments = computed<Segment[]>(() => {
    const s = this.store.library()?.stats;
    if (!s) return [];
    const total = Math.max(1, this.total());
    const raw = [
      { key: 'covers' as const, label: 'Covers', bytes: s.coversBytes },
      { key: 'meta' as const, label: 'Metadata', bytes: s.metadataBytes },
      { key: 'over' as const, label: 'Overhead', bytes: Math.max(0, s.coversFile - s.coversBytes) + Math.max(0, s.metadataFile - s.metadataBytes) },
    ];
    let start = 0;
    return raw.map(x => {
      const len = C * x.bytes / total, dash = Math.max(0, len - GAP);
      const seg = { ...x, pct: 100 * x.bytes / total, dash: `${dash} ${C - dash}`, offset: -(start + GAP / 2) };
      start += len;
      return seg;
    });
  });

  /** Loads on show and again after a reconnect (a library switch restarts the server). */
  constructor() { effect(() => { if (this.store.connected()) untracked(() => this.store.loadLibrary()); }); }

  protected async add() {
    const path = await this.picker.pick({ title: 'Choose a folder for the library', startAt: this.store.library()?.home, allowNew: true });
    if (path) this.store.switchLibrary(path);
  }

  protected async optimize() {
    this.optimizing.set(true);
    try { await this.store.optimize(); } finally { this.optimizing.set(false); }
  }
}
