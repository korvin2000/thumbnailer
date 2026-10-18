import { afterRenderEffect, ChangeDetectionStrategy, Component, computed, ElementRef, inject, Injectable, signal, untracked, viewChild } from '@angular/core';
import { CatalogStore } from '../../core/catalog-store';
import { Gateway } from '../../core/gateway';
import { Entity, EntityKind } from '../../core/model';
import { AlbumStore } from '../../core/store';
import { Icon } from './icon';

export interface EntityPickRequest { kind: EntityKind; title?: string; allowCreate?: boolean }

/** Promise-style entity chooser (assign to, merge into, add to favorites, credited as); `app-entity-picker` shows while `open()` is set. */
@Injectable({ providedIn: 'root' })
export class EntityPicker {
  private readonly request = signal<EntityPickRequest | null>(null);
  readonly open = this.request.asReadonly();
  private settle: ((e: Entity | null) => void) | null = null;

  /** Resolves with the chosen (or created) entity, or null on cancel; a new request cancels a pending one. */
  pick(opts: EntityPickRequest): Promise<Entity | null> {
    this.settle?.(null);
    return new Promise(resolve => { this.settle = resolve; this.request.set(opts); });
  }

  choose(e: Entity) { this.finish(e); }
  cancel() { this.finish(null); }

  private finish(e: Entity | null) {
    const s = this.settle;
    this.settle = null;
    this.request.set(null);
    s?.(e);
  }
}

const NOUN: Record<EntityKind, string> = { MODEL: 'model', STUDIO: 'studio', PHOTOGRAPHER: 'photographer', GENRE: 'genre', TAG: 'tag', LIST: 'list' };
const norm = (s: string) => s.trim().toLowerCase().replace(/\s+/g, ' ');
/** The last picks of a kind, per library (`dataDir`: entity ids are that library's): localStorage snapshots, only ever shown as the server's current copy. */
const RECENT = 8, recentKey = (lib: string, kind: EntityKind) => `thumbnailer.recent.${lib}.${kind}`;
const readRecent = (lib: string, kind: EntityKind): Entity[] => { try { return JSON.parse(localStorage.getItem(recentKey(lib, kind)) ?? '[]'); } catch { return []; } };
const writeRecent = (lib: string, kind: EntityKind, list: Entity[]) => { try { localStorage.setItem(recentKey(lib, kind), JSON.stringify(list)); } catch { /* storage unavailable */ } };

/** Search box over `Gateway.entities` (debounced, newest answer wins), recent picks first while the box is empty (as the server knows them now), ↑ ↓ ⏎ Esc, "Create …" when allowed and nothing matches exactly. */
@Component({
  selector: 'app-entity-picker',
  imports: [Icon],
  template: `
    <dialog #dlg (cancel)="$event.preventDefault(); picker.cancel()">
      @if (picker.open(); as req) {
        <header>
          <app-icon name="search" [size]="16" /><h2>{{ req.title ?? 'Choose a ' + noun[req.kind] }}</h2>
          <button class="btn icon-btn sm ghost" (click)="picker.cancel()" title="Cancel"><app-icon name="x" /></button>
        </header>
        <div class="search"><input #box class="input" [placeholder]="'Search ' + noun[req.kind] + 's…'" [value]="text()" (input)="search($any($event.target).value)" (keydown)="key($event)" spellcheck="false" autocomplete="off" /></div>
        @if (error(); as err) { <div class="error"><app-icon name="alert" [size]="14" /> {{ err }}</div> }
        <ul role="listbox">
          @if (recentShown().length) {
            <li class="group">Recent</li>
            @for (e of recentShown(); track e.id) {
              <li role="option" [class.active]="active() === $index" [class.auto]="e.auto" (click)="pick(e)" (mouseenter)="active.set($index)">
                <span class="name">{{ e.name }}</span>@if (e.auto) { <span class="dot" title="Created from album text, not confirmed"></span> }<span class="n">{{ e.albums }}</span>
              </li>
            }
            <li class="group">All</li>
          }
          @for (e of rest(); track e.id; let i = $index) {
            @let at = i + recentShown().length;
            <li role="option" [class.active]="active() === at" [class.auto]="e.auto" (click)="pick(e)" (mouseenter)="active.set(at)">
              <span class="name">{{ e.name }}</span>@if (e.auto) { <span class="dot" title="Created from album text, not confirmed"></span> }<span class="n">{{ e.albums }}</span>
            </li>
          }
          @if (canCreate()) {
            <li role="option" class="create" [class.active]="active() === rows().length" (click)="create()" (mouseenter)="active.set(rows().length)"><app-icon name="plus" [size]="14" /> Create “{{ text().trim() }}”</li>
          }
          @if (loading()) { <li class="state"><span class="spinner"></span> Loading…</li> }
          @else if (!rows().length && !canCreate()) { <li class="state">Nothing found</li> }
        </ul>
        @if (total() > items().length) { <footer>{{ items().length }} of {{ total() }}: type to narrow</footer> }
      }
    </dialog>
  `,
  styles: `
    dialog { width: min(440px, 92vw); height: min(480px, 80vh); padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); overflow: hidden; }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.45); backdrop-filter: blur(2px); }
    dialog[open] { display: flex; flex-direction: column; animation: fade-up 0.18s var(--ease); }
    header { display: flex; align-items: center; gap: 10px; flex: none; height: 46px; padding: 0 10px 0 16px; border-bottom: 1px solid var(--border); }
    header app-icon { color: var(--accent); }
    h2 { flex: 1; font-size: 13.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .search { flex: none; padding: 10px 12px; }
    .error { display: flex; align-items: center; gap: 6px; margin: 0 12px 6px; padding: 6px 10px; border-radius: var(--radius-s); background: color-mix(in oklch, var(--danger) 12%, transparent); color: var(--danger); font-size: 12px; }
    ul { flex: 1; margin: 0; padding: 0 8px 8px; list-style: none; overflow: auto; }
    li { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: var(--radius-s); font-size: 13px; cursor: pointer; user-select: none; }
    li.active { background: var(--surface-2); }
    li.auto .name { color: var(--text-2); }
    li.group { padding: 8px 10px 2px; color: var(--text-3); font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; cursor: default; }
    .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); }
    .n { color: var(--text-3); font-size: 11.5px; font-variant-numeric: tabular-nums; }
    li.create { color: var(--accent-strong); }
    li.state { color: var(--text-3); cursor: default; }
    footer { flex: none; padding: 8px 16px; border-top: 1px solid var(--border); color: var(--text-3); font-size: 11.5px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EntityPickerDialog {
  protected readonly picker = inject(EntityPicker);
  private readonly gw = inject(Gateway);
  private readonly store = inject(AlbumStore);
  private readonly catalog = inject(CatalogStore);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  private readonly box = viewChild<ElementRef<HTMLInputElement>>('box');
  protected readonly noun = NOUN;

  protected readonly text = signal('');
  protected readonly items = signal<Entity[]>([]);
  protected readonly total = signal(0);
  protected readonly recent = signal<Entity[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Keyboard row over `rows()`; `rows().length` = the Create row. */
  protected readonly active = signal(0);
  /** The library the recents belong to ('' until the settings are in: no recents then). */
  private readonly lib = computed(() => this.store.settings()?.dataDir ?? '');
  /** Recent picks lead while the box is empty: the server's current copy of each (the search's, else `CatalogStore.known`, fetched on open), so a renamed one shows its new name and a deleted or merged-away one is gone. */
  protected readonly recentShown = computed(() => {
    const kind = this.picker.open()?.kind, known = this.catalog.known();
    return this.text().trim() || !kind ? [] : this.recent().map(r => this.items().find(e => e.id === r.id) ?? known.get(r.id)).filter((e): e is Entity => !!e && e.kind === kind);
  });
  protected readonly rest = computed(() => { const seen = new Set(this.recentShown().map(e => e.id)); return this.items().filter(e => !seen.has(e.id)); });
  protected readonly rows = computed(() => [...this.recentShown(), ...this.rest()]);
  protected readonly canCreate = computed(() => { const t = norm(this.text()); return !!this.picker.open()?.allowCreate && !!t && !this.items().some(e => norm(e.name) === t); });
  /** Bumped per search and on dismissal; an older answer is dropped. */
  private gen = 0;
  private timer?: ReturnType<typeof setTimeout>;

  constructor() {
    afterRenderEffect(() => {
      const req = this.picker.open(), dlg = this.dialog().nativeElement;
      untracked(() => {
        if (!req) { this.gen++; clearTimeout(this.timer); if (dlg.open) dlg.close(); return; }
        const recent = this.lib() ? readRecent(this.lib(), req.kind) : [];
        this.items.set([]); this.total.set(0); this.error.set(null); this.recent.set(recent); // a new request (even one replacing an open one) starts over
        void this.catalog.ensureKnown(recent.map(r => r.id), true);
        this.search('', 0);
        if (!dlg.open) dlg.showModal();
        this.box()?.nativeElement.focus();
      });
    });
  }

  protected search(text: string, wait = 150) {
    this.text.set(text);
    clearTimeout(this.timer);
    const gen = ++this.gen, kind = this.picker.open()?.kind;
    if (!kind) return;
    this.loading.set(true);
    this.timer = setTimeout(async () => {
      try {
        const r = await this.gw.entities(kind, { text: text.trim() || undefined, limit: 50 });
        if (gen !== this.gen) return;
        this.items.set(r.items); this.total.set(r.total); this.active.set(0); this.error.set(null);
      } catch (e) { if (gen === this.gen) this.error.set(e instanceof Error ? e.message : String(e)); }
      finally { if (gen === this.gen) this.loading.set(false); }
    }, wait);
  }

  protected key(e: KeyboardEvent) {
    const n = this.rows().length + (this.canCreate() ? 1 : 0);
    switch (e.key) {
      case 'ArrowDown': case 'ArrowUp': if (n) this.active.update(i => (i + (e.key === 'ArrowDown' ? 1 : -1) + n) % n); break;
      case 'Home': this.active.set(0); break;
      case 'End': this.active.set(Math.max(0, n - 1)); break;
      case 'Enter': { const it = this.rows()[this.active()]; if (it) this.pick(it); else if (this.canCreate()) void this.create(); break; }
      default: return;
    }
    e.preventDefault();
  }

  /** Chooses `e` (a server copy: a row shown) and remembers it as the kind's newest recent pick of this library. */
  protected pick(e: Entity) {
    if (this.lib()) writeRecent(this.lib(), e.kind, [e, ...this.recent().filter(r => r.id !== e.id)].slice(0, RECENT));
    this.picker.choose(e);
  }

  /** Creates the typed name as a confirmed entity and picks it; a name owned by another entity is shown here (toasts hide behind the dialog). */
  protected async create() {
    const req = this.picker.open(), name = this.text().trim();
    if (!req || !name) return;
    try { const c = await this.gw.saveEntity({ kind: req.kind, name, rating: 0, profile: null, aliases: [], links: [] }); if (this.picker.open() === req) this.pick(c.entity); }
    catch (e) { this.error.set(e instanceof Error ? e.message : String(e)); }
  }
}
