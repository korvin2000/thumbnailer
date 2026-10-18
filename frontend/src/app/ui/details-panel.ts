import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CatalogStore } from '../core/catalog-store';
import { joinNames, parseNames } from '../core/entity-fields';
import { BytesPipe, MegapixelsPipe } from '../core/format';
import { Gateway } from '../core/gateway';
import { Album, AlbumPatch, EntityKind } from '../core/model';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { EntityCardView } from './entity-card';
import { Icon } from './shared/icon';
import { Rating } from './shared/rating';

type TextField = 'name' | 'model' | 'studio' | 'photographer' | 'description';
/** The credit inputs: each edits the raw text (a name list), its chips show the entities that text resolved to. */
const CREDITS = [
  { role: 'MODEL', field: 'model', label: 'Model', icon: 'user', facet: 'models' },
  { role: 'STUDIO', field: 'studio', label: 'Studio', icon: 'building', facet: 'studios' },
  { role: 'PHOTOGRAPHER', field: 'photographer', label: 'Photographer', icon: 'camera', facet: 'photographers' },
  { role: 'GENRE', field: 'genres', label: 'Genres', icon: 'sparkles', facet: 'genres' },
] as const;
type Credit = typeof CREDITS[number];
/** Datalist rows for one credit input: the typed head (names before the last separator) joined with each server suggestion. */
interface Suggest { role: EntityKind; options: string[] }

/** Floating panel with everything about one album; every field saves itself on change. In entity mode (a chip clicked) it shows the catalog card instead, 400 px wide. */
@Component({
  selector: 'app-details-panel',
  imports: [Icon, Rating, RouterLink, BytesPipe, MegapixelsPipe, DatePipe, DecimalPipe, EntityCardView],
  host: { '[class.entity]': "mode() === 'entity'" },
  template: `
    <header>
      @if (mode() === 'entity') {
        <button class="btn icon-btn sm ghost" title="Back to the album" (click)="catalog.closeCard()"><app-icon name="arrowLeft" /></button><span class="label">Catalog</span>
      } @else { <span class="label">{{ hovering() ? 'Hovering' : 'Selected' }}</span> }
      <span class="state">
        @if (saving()) { <span class="spinner"></span> Saving }
        @else if (saved()) { <app-icon name="check" [size]="13" /> Saved }
      </span>
      <button class="btn icon-btn sm ghost" title="Close (I)" (click)="close.emit()"><app-icon name="x" /></button>
    </header>

    @if (catalog.cardId(); as id) {
      <div class="body"><app-entity-card [entityId]="id" (open)="showEntity($event)" /></div>
    } @else if (album(); as a) {
      <div class="body">
        <a class="cover" [class.portrait]="a.coverPortrait" [routerLink]="['/album', a.id]" title="Open album">
          <img [src]="gw.coverUrl(a)" [alt]="a.name" />
          <span class="open"><app-icon name="maximize" [size]="14" /> Open album</span>
        </a>

        <input class="input title" [value]="a.name" (change)="save('name', $any($event.target).value)" title="Album name" />

        <div class="path" [title]="a.path"><app-icon [name]="a.kind === 'DIRECTORY' ? 'folder' : 'archive'" [size]="13" /><span>{{ a.path }}</span></div>
        <div class="actions">
          <button class="btn sm" (click)="store.reveal(a.id)"><app-icon name="externalLink" [size]="13" /> Show in folder</button>
          <button class="btn sm" (click)="copy(a.path)"><app-icon name="copy" [size]="13" /> Copy path</button>
        </div>

        <dl class="stats">
          <div><dt>Photos</dt><dd>{{ a.imageCount | number }}</dd></div>
          <div><dt>Size</dt><dd>{{ a.bytes | bytes }}</dd></div>
          <div><dt>Quality</dt><dd>{{ a.avgPixels | mp }}</dd></div>
          <div><dt>Largest</dt><dd>{{ a.maxWidth }}×{{ a.maxHeight }}</dd></div>
          <div><dt>Shot</dt><dd>{{ a.shotDate ? (a.shotDate | date: 'mediumDate') : '—' }}</dd></div>
          <div><dt>Modified</dt><dd>{{ a.modifiedAt | date: 'mediumDate' }}</dd></div>
          <div><dt>Indexed</dt><dd>{{ a.indexedAt | date: 'short' }}</dd></div>
          <div><dt>Cover</dt><dd class="cover-name"><span>{{ a.cover || '—' }}</span>@if (a.coverPinned) { <app-icon name="pin" [size]="11" /> }</dd></div>
        </dl>
        @if (a.hidden || a.copies > 1) {
          <div class="flags">
            @if (a.hidden) { <span class="flag"><app-icon name="eyeOff" [size]="12" /> Hidden</span> }
            @if (a.copies > 1) { <button class="flag link" title="Show every album with the same photo names and sizes" (click)="catalog.copiesOf.set(a.id)"><app-icon name="layers" [size]="12" /> {{ a.copies }} copies <span class="dim">(same names and sizes)</span></button> }
          </div>
        }

        <section>
          <h3>Rating</h3>
          <app-rating [value]="a.rating" (valueChange)="patch({ rating: $event })" />
        </section>

        <section class="fields">
          @for (c of credits; track c.role) {
            <label><span><app-icon [name]="c.icon" [size]="13" /> {{ c.label }}</span>
              <input class="input sm" [value]="text(a, c)" [attr.list]="'dl-' + c.role" [placeholder]="'names, comma-separated'" (input)="suggest(c, $any($event.target).value)" (change)="saveCredit(a, c, $any($event.target).value)" /></label>
            <datalist [id]="'dl-' + c.role">@for (o of options(c); track o) { <option [value]="o"></option> }</datalist>
            @let linked = links(a, c.role);
            @if (linked.length) {
              <div class="links">
                @for (l of linked; track l.entity) {
                  <span class="chip"><button class="t" [title]="(l.alias ? 'Written as ' + l.alias + ' — ' : '') + 'Open ' + l.name" (click)="showEntity(l.entity)">{{ l.name }}</button><button [title]="'Unassign ' + l.name" (click)="unlink(a, l.entity)"><app-icon name="x" [size]="11" /></button></span>
                }
              </div>
            }
          }
          <label><span><app-icon name="calendar" [size]="13" /> Shot date</span>
            <span class="date"><input class="input sm" type="date" [value]="a.shotDate?.slice(0, 10) ?? ''" title="Overrides the indexed date" (change)="patch({ date: $any($event.target).value })" /><button class="btn icon-btn sm ghost" title="Back to the indexed date" (click)="patch({ date: '' })"><app-icon name="refresh" [size]="12" /></button></span></label>
          <label class="col"><span>Description</span><textarea class="input" rows="3" [value]="a.description ?? ''" (change)="save('description', $any($event.target).value)"></textarea></label>
          <datalist id="dl-tags">@for (x of store.facets()?.tags; track x.value) { <option [value]="x.value"></option> }</datalist>
        </section>

        <section>
          <h3><app-icon name="hash" [size]="13" /> Tags</h3>
          <div class="tags">
            @for (t of tags(); track t) {
              <span class="chip active"><button class="t" (click)="store.toggleTag(t)" title="Filter by this tag">#{{ t }}</button><button (click)="setTags(tags().filter(x => x !== t))" title="Remove tag"><app-icon name="x" [size]="11" /></button></span>
            }
            <input class="input sm tag-input" placeholder="Add tag ⏎" list="dl-tags" (keydown.enter)="addTag($any($event.target)); $event.preventDefault()" />
          </div>
        </section>
      </div>
    } @else {
      <div class="empty"><app-icon name="info" [size]="26" /><p>Select or hover an album to see its details.</p></div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; border-radius: var(--radius-l); overflow: hidden; animation: fade-up 0.2s var(--ease); }
    :host(.entity) { width: 400px; }
    @media (max-width: 760px) { :host(.entity) { width: auto; } }
    header { display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 8px 0 14px; border-bottom: 1px solid var(--border); }
    .state { flex: 1; display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: var(--text-3); }
    .body { flex: 1; overflow: auto; padding: 12px 14px 18px; display: flex; flex-direction: column; gap: 10px; }
    .cover { position: relative; display: block; aspect-ratio: 16 / 9; border-radius: var(--radius); overflow: hidden; background: var(--surface-2); }
    .cover.portrait { aspect-ratio: 16 / 9; }
    .cover img { width: 100%; height: 100%; object-fit: contain; background: oklch(10% 0.01 260); }
    .cover .open { position: absolute; right: 8px; bottom: 8px; display: inline-flex; align-items: center; gap: 5px; padding: 4px 8px; border-radius: 6px; background: oklch(10% 0.01 260 / 0.6); color: #fff; font-size: 11px; font-weight: 600; opacity: 0; transition: opacity 0.15s; }
    .cover:hover .open { opacity: 1; }
    .title { font-weight: 600; font-size: 13.5px; }
    .path { display: flex; align-items: center; gap: 6px; color: var(--text-2); font-family: var(--mono); font-size: 11.5px; }
    .path span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
    .actions { display: flex; gap: 6px; }
    .stats { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin: 2px 0; padding: 10px 12px; border-radius: var(--radius); background: var(--surface); }
    .stats div { display: flex; justify-content: space-between; gap: 8px; min-width: 0; }
    dt { color: var(--text-3); font-size: 11.5px; }
    dd { margin: 0; font-weight: 500; font-variant-numeric: tabular-nums; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .cover-name { display: inline-flex; align-items: center; gap: 4px; color: var(--accent); }
    .flags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: -4px; }
    .flag { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--text-2); font-size: 11.5px; }
    .flag.link:hover { color: var(--accent-strong); background: var(--accent-soft); }
    .flag .dim { color: var(--text-3); }
    section h3 { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--text-3); }
    .fields { display: flex; flex-direction: column; gap: 6px; }
    .fields label { display: grid; grid-template-columns: 108px 1fr; align-items: center; gap: 8px; font-size: 12px; color: var(--text-2); }
    .fields label.col { grid-template-columns: 1fr; gap: 4px; }
    .fields label > span { display: inline-flex; align-items: center; gap: 6px; }
    .date { display: flex; gap: 4px; }
    .date .input { flex: 1; min-width: 0; padding: 0 6px; }
    .links { display: flex; flex-wrap: wrap; gap: 4px; margin: -2px 0 2px 116px; }
    .links .t, .tags .t { font: inherit; color: inherit; }
    .tags { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; }
    .tag-input { width: 110px; }
    .empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; color: var(--text-3); text-align: center; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailsPanel {
  readonly album = input<Album | null>(null);
  readonly hovering = input(false);
  readonly close = output<void>();

  protected readonly gw = inject(Gateway);
  protected readonly store = inject(AlbumStore);
  protected readonly catalog = inject(CatalogStore);
  private readonly toasts = inject(Toasts);
  protected readonly credits = CREDITS;
  /** The panel shows the album, or the open catalog card (`CatalogStore.cardId`: a chip here, a tree row's "Open card", the viewer's links); the back arrow closes the card. */
  readonly mode = computed(() => this.catalog.cardId() !== null ? 'entity' : 'album');
  protected readonly saving = signal(false);
  protected readonly saved = linkedSignal<Album | null, boolean>({ source: this.album, computation: () => false });
  /** Local tag list so quick successive edits build on each other instead of on the last acknowledged album. */
  protected readonly tags = linkedSignal(() => this.album()?.tags ?? []);
  /** Server suggestions for the credit input being typed in (one role at a time); the facets fill the lists otherwise. */
  private readonly suggestion = signal<Suggest | null>(null);
  private suggestTimer?: ReturnType<typeof setTimeout>;
  private suggestGen = 0;

  protected save(field: TextField, value: string) {
    const a = this.album();
    if (a && (a[field] ?? '') !== value.trim()) this.patch({ [field]: value.trim() });
  }

  showEntity(id: number) { this.catalog.openCard(id); }

  protected links(a: Album, role: EntityKind) { return (a.links ?? []).filter(l => l.role === role); }

  /** The raw text of a credit input: the album's field, a genre list joined in name-list syntax. */
  protected text(a: Album, c: Credit): string { return c.field === 'genres' ? joinNames(a.genres ?? []) : a[c.field] ?? ''; }

  protected saveCredit(a: Album, c: Credit, value: string) {
    this.suggestion.set(null);
    if (c.field === 'genres') { const genres = parseNames(value); if (genres.join('\n') !== (a.genres ?? []).join('\n')) this.patch({ genres }); }
    else this.save(c.field, value);
  }

  protected options(c: Credit): string[] {
    const s = this.suggestion();
    return s?.role === c.role ? s.options : (this.store.facets()?.[c.facet] ?? []).map(x => x.value);
  }

  /** Prefix search of the name being typed (after the last separator); each hit is offered as the whole list with that name completed. */
  protected suggest(c: Credit, value: string) {
    clearTimeout(this.suggestTimer);
    const m = /^(.*[,;]\s*)?([^,;]*)$/.exec(value)!, head = m[1] ?? '', token = m[2].trim(), gen = ++this.suggestGen;
    if (!token) { this.suggestion.set(null); return; }
    this.suggestTimer = setTimeout(async () => {
      try { const r = await this.catalog.entities(c.role, { text: token, limit: 12 }); if (gen === this.suggestGen) this.suggestion.set({ role: c.role, options: r.items.map(e => head + e.name) }); }
      catch { /* the facet list stays */ }
    }, 150);
  }

  protected async unlink(a: Album, entityId: number) {
    this.saving.set(true);
    const n = await this.catalog.unlink({ albumIds: [a.id] }, entityId);
    this.saving.set(false);
    if (n !== null) this.flash();
  }

  protected setTags(tags: string[]) { this.tags.set(tags); this.patch({ tags }); }

  protected addTag(input: HTMLInputElement) {
    const tag = input.value.trim().replace(/^#/, '').toLowerCase();
    input.value = '';
    if (tag && !this.tags().includes(tag)) this.setTags([...this.tags(), tag]);
  }

  protected async patch(patch: AlbumPatch) {
    const a = this.album();
    if (!a) return;
    this.saving.set(true);
    const ok = await this.store.patch(a.id, patch);
    this.saving.set(false);
    if (ok) this.flash();
  }

  private flash() { this.saved.set(true); setTimeout(() => this.saved.set(false), 2000); }

  protected async copy(text: string) {
    try { await navigator.clipboard.writeText(text); this.toasts.show('Path copied'); }
    catch { this.toasts.show(text, 'info', 6000); }
  }
}
