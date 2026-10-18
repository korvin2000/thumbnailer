import { ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, input, output, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { CatalogStore } from '../core/catalog-store';
import { ageFrom, cmToFtIn, Field, FIELDS, kgToLb, parseChips, parseProfile, withField } from '../core/entity-fields';
import { Gateway } from '../core/gateway';
import { Album, Derived, Entity, EntityCard, EntityKind, EntityLinkIn, EntitySave } from '../core/model';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Actions } from './actions';
import { KIND_ICON, NOUN } from './kinds';
import { Confirm } from './shared/confirm';
import { ContextMenu, MenuEntry } from './shared/context-menu';
import { EntityPicker } from './shared/entity-picker';
import { Icon } from './shared/icon';
import { Rating } from './shared/rating';

const KIND: Record<EntityKind, string> = { MODEL: 'Model', STUDIO: 'Studio', PHOTOGRAPHER: 'Photographer', GENRE: 'Genre', TAG: 'Hashtag', LIST: 'Favorites list' };

/** A relationship section (catalog_plan.md §7.3): curated links of `kind` (editable) beside the derived list (read-only, "from albums"). */
interface Section { kind: EntityKind; label: string; curated?: string; derived: (d: Derived) => Entity[] | undefined }
const genres: Section = { kind: 'GENRE', label: 'Genres', derived: d => d.genres };
const models = (curated?: string): Section => ({ kind: 'MODEL', label: 'Models', curated, derived: d => d.models });
const studios: Section = { kind: 'STUDIO', label: 'Studios', derived: d => d.studios?.map(s => s.entity) };
const photographers: Section = { kind: 'PHOTOGRAPHER', label: 'Photographers', derived: d => d.photographers };
const SECTIONS: Record<EntityKind, Section[]> = {
  MODEL: [genres, photographers], STUDIO: [genres, models('roster'), photographers], PHOTOGRAPHER: [studios, models(), genres], GENRE: [models(), studios], LIST: [], TAG: [],
};

/** One line of a model's studios table: the derived facts from her albums there and the curated link (alias = credited as, roles). */
interface StudioLine { entity: Entity; alias: string; roles: string; albums: number; curated: boolean; derivedAliases: string[]; genres: string[] }

/** One catalog entity in the details panel, loaded through `CatalogStore.openCard`: profile fields from `FIELDS`, aliases, relationship sections; every control saves on change. */
@Component({
  selector: 'app-entity-card',
  imports: [Icon, Rating],
  template: `
    @if (catalog.card(); as c) {
      @let e = c.entity;
      <div class="hero" [class.bare]="!cover()">
        @if (cover(); as a) { <img [src]="gw.coverUrl(a)" [alt]="a.name" /> }
        <div class="scrim">
          <span class="kind"><app-icon [name]="icon[e.kind]" [size]="12" /> {{ kindName[e.kind] }}@if (e.auto) { <span class="auto">· unconfirmed</span> }</span>
          <span class="state">@if (saving()) { <span class="spinner"></span> Saving } @else if (saved()) { <app-icon name="check" [size]="12" /> Saved }</span>
        </div>
      </div>

      <div class="namerow">
        <input class="input title" [value]="e.name" (change)="rename($any($event.target).value)" title="Name — a renamed entity keeps the old name as an alias" />
        @if (e.auto) { <button class="btn sm" title="Mark as confirmed" (click)="confirmIt()"><app-icon name="check" [size]="13" /> Confirm</button> }
        <button class="btn icon-btn sm ghost" title="More: merge, delete" (click)="more($event, e)"><app-icon name="more" [size]="16" /></button>
      </div>

      @if (e.kind !== 'TAG') {
        <div class="chips">
          @for (a of c.aliases; track a) { <span class="chip"><span>{{ a }}</span><button title="Remove alias" (click)="removeAlias(a)"><app-icon name="x" [size]="11" /></button></span> }
          <input class="input sm chip-input" placeholder="Add alias ⏎" (keydown.enter)="addAlias($any($event.target)); $event.preventDefault()" />
        </div>
      }

      @if (e.kind === 'MODEL') { <section><h3>Rating</h3><app-rating [value]="e.rating" (valueChange)="rate($event)" /></section> }

      @let albums = c.derived?.albums ?? e.albums;
      <div class="counts">
        <span><strong>{{ albums }}</strong> {{ albums === 1 ? 'album' : 'albums' }}@if (e.kind === 'LIST') { · <strong>{{ e.photos ?? 0 }}</strong> photos }</span>
        <button class="btn sm" (click)="showAlbums()" [disabled]="!albums"><app-icon name="images" [size]="13" /> Show albums</button>
        @if (e.kind === 'LIST') { <button class="btn sm" (click)="showPhotos()" [disabled]="!e.photos"><app-icon name="image" [size]="13" /> Show photos</button> }
      </div>

      @if (fields().length) {
        <section class="fields">
          @for (f of fields(); track f.key) {
            @let v = profile()[f.key];
            @switch (f.type) {
              @case ('textarea') { <label class="col"><span>{{ f.label }}</span><textarea class="input" rows="3" [value]="v ?? ''" (change)="setField(f, $any($event.target).value)"></textarea></label> }
              @case ('number') {
                <label><span>{{ f.label }}</span><span class="unit"><input class="input sm" type="number" min="0" step="1" [value]="v ?? ''" (change)="setField(f, $any($event.target).value)" /><em>{{ f.unit }}</em><span class="hint">{{ f.unit === 'cm' ? ftIn(v) : lb(v) }}</span></span></label>
              }
              @case ('year') {
                <label><span>{{ f.label }}</span><span class="unit"><input class="input sm" [value]="v ?? ''" [placeholder]="f.hint ?? ''" (change)="setField(f, $any($event.target).value)" />@if (age(v); as n) { <span class="hint">age {{ n }}</span> }</span></label>
              }
              @case ('chips') {
                <label><span>{{ f.label }}</span><input class="input sm" [value]="chipsText(v)" placeholder="comma-separated" (change)="setField(f, $any($event.target).value)" /></label>
              }
              @default { <label><span>{{ f.label }}</span><input class="input sm" [value]="v ?? ''" [placeholder]="f.hint ?? ''" (change)="setField(f, $any($event.target).value)" /></label> }
            }
          }
        </section>
      }

      @if (e.kind === 'MODEL') {
        <section>
          <h3><app-icon name="building" [size]="13" /> Studios</h3>
          @if (studioLines().length) {
            <table>
              <thead><tr><th>Studio</th><th>Credited as</th><th>Roles</th><th class="r">Albums</th><th></th></tr></thead>
              <tbody>
                @for (s of studioLines(); track s.entity.id) {
                  <tr [class.derived]="!s.curated">
                    <td><button class="link" (click)="open.emit(s.entity.id)" [title]="s.genres.length ? 'Genres there: ' + s.genres.join(', ') : 'Open ' + s.entity.name">{{ s.entity.name }}</button></td>
                    <td>
                      @if (editing() === s.entity.id) {
                        <input #alias class="input sm alias" [value]="s.alias || s.derivedAliases[0] || ''" placeholder="credited name" (keydown.enter)="credit(s, alias.value); $event.preventDefault()" (keydown.escape)="editing.set(null); $event.stopPropagation()" (blur)="editing.set(null)" />
                      } @else {
                        <button class="cell" [title]="s.curated ? 'Change the credited name' : 'Credit this model under another name at ' + s.entity.name" (click)="edit(s)">{{ s.alias || s.derivedAliases.join(', ') || '—' }}</button>
                      }
                    </td>
                    <td><input class="input sm roles" [value]="s.roles" placeholder="roles" [title]="s.curated ? 'Roles there (comma-separated genres)' : 'Set roles to curate this link'" (change)="setRoles(s, $any($event.target).value)" /></td>
                    <td class="r">{{ s.albums || '—' }}</td>
                    <td>@if (s.curated) { <button class="x" title="Remove the curated link" (click)="removeLink(s.entity.id)"><app-icon name="x" [size]="11" /></button> }</td>
                  </tr>
                }
              </tbody>
            </table>
            <p class="legend"><span class="dim">dimmed rows</span> come from albums; the others are curated.</p>
          }
          <button class="btn sm" (click)="addLink('STUDIO')"><app-icon name="plus" [size]="13" /> Add studio…</button>
        </section>
      }

      @for (s of sections(); track s.kind) {
        @let curated = linksOf(c, s.kind);
        @let derived = derivedOf(c, s);
        <section>
          <h3><app-icon [name]="icon[s.kind]" [size]="13" /> {{ s.label }}</h3>
          <div class="chips">
            @for (l of curated; track l.to.id) {
              <span class="chip active" [title]="(s.curated ?? 'curated') + (l.roles ? ' · ' + l.roles : '')"><button class="t" (click)="open.emit(l.to.id)">{{ l.to.name }}</button>@if (s.curated) { <em>{{ s.curated }}</em> }<button title="Remove" (click)="removeLink(l.to.id)"><app-icon name="x" [size]="11" /></button></span>
            }
            @for (d of derived; track d.id) { <button class="chip derived" title="From albums" (click)="open.emit(d.id)">{{ d.name }}<span class="n">{{ d.albums }}</span></button> }
            <button class="btn sm ghost add" (click)="addLink(s.kind)"><app-icon name="plus" [size]="13" /> Add {{ noun[s.kind] }}…</button>
          </div>
        </section>
      }

      @if (e.kind === 'MODEL') {
        @let tags = linksOf(c, 'TAG');
        <section>
          <h3><app-icon name="hash" [size]="13" /> Hashtags</h3>
          <div class="chips">
            @for (l of tags; track l.to.id) { <span class="chip active"><button class="t" (click)="store.toggleTag(l.to.name)" title="Filter by this tag">#{{ l.to.name }}</button><button title="Remove" (click)="removeLink(l.to.id)"><app-icon name="x" [size]="11" /></button></span> }
            @for (t of derivedTags(c); track t) { <button class="chip derived" title="From albums" (click)="store.toggleTag(t)">#{{ t }}</button> }
            <input class="input sm chip-input" placeholder="Add tag ⏎" (keydown.enter)="addTag($any($event.target)); $event.preventDefault()" />
          </div>
        </section>
      }
    } @else { <div class="loading"><span class="spinner"></span></div> }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 10px; }
    .hero { position: relative; aspect-ratio: 16 / 7; border-radius: var(--radius); overflow: hidden; background: var(--surface-2); }
    .hero.bare { aspect-ratio: auto; height: 34px; }
    .hero img { width: 100%; height: 100%; object-fit: cover; }
    .scrim { position: absolute; inset: auto 0 0 0; display: flex; align-items: center; gap: 8px; padding: 18px 10px 7px; background: linear-gradient(to top, oklch(10% 0.01 260 / 0.75), transparent); color: #fff; font-size: 11px; font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; }
    .hero.bare .scrim { padding: 0 10px; background: none; color: var(--text-3); }
    .kind { flex: 1; display: inline-flex; align-items: center; gap: 5px; }
    .auto { opacity: 0.8; text-transform: none; letter-spacing: 0; font-weight: 500; }
    .state { display: inline-flex; align-items: center; gap: 4px; text-transform: none; letter-spacing: 0; font-weight: 500; opacity: 0.85; }
    .namerow { display: flex; gap: 6px; align-items: center; }
    .title { font-weight: 600; font-size: 14px; }
    .chips { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; }
    .chip .t { font: inherit; color: inherit; }
    .chip em { font-style: normal; font-size: 10px; opacity: 0.7; text-transform: uppercase; letter-spacing: 0.04em; }
    .chip.derived { color: var(--text-3); border-style: dashed; border-color: var(--border); background: transparent; cursor: pointer; }
    .chip.derived:hover { color: var(--text); }
    .chip-input { width: 104px; height: 22px; }
    .add { height: 22px; padding: 0 6px; font-size: 11.5px; }
    .counts { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: var(--radius); background: var(--surface); color: var(--text-2); }
    .counts > span { flex: 1; }
    .counts strong { color: var(--text); font-variant-numeric: tabular-nums; }
    section h3 { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--text-3); }
    .fields { display: flex; flex-direction: column; gap: 6px; }
    .fields label { display: grid; grid-template-columns: 96px 1fr; align-items: center; gap: 8px; font-size: 12px; color: var(--text-2); }
    .fields label.col { grid-template-columns: 1fr; gap: 4px; }
    .unit { display: flex; align-items: center; gap: 6px; min-width: 0; }
    .unit .input { width: 90px; flex: none; }
    .unit em { font-style: normal; color: var(--text-3); font-size: 11.5px; }
    .hint { color: var(--text-3); font-size: 11.5px; white-space: nowrap; }
    table { width: 100%; border-collapse: collapse; font-size: 12px; margin-bottom: 6px; }
    th { padding: 2px 4px 4px; text-align: left; font-size: 10.5px; font-weight: 600; letter-spacing: 0.04em; text-transform: uppercase; color: var(--text-3); }
    td { padding: 2px 4px; border-top: 1px solid var(--border); vertical-align: middle; }
    .r { text-align: right; font-variant-numeric: tabular-nums; }
    tr.derived td { color: var(--text-3); }
    .link { font: inherit; color: var(--text); text-align: left; }
    .link:hover { color: var(--accent-strong); }
    tr.derived .link { color: var(--text-2); }
    .cell { font: inherit; color: inherit; text-align: left; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border-bottom: 1px dashed transparent; }
    .cell:hover { border-bottom-color: var(--text-3); }
    td .input { height: 22px; font-size: 11.5px; padding: 0 5px; }
    .roles { width: 80px; background: transparent; border-color: transparent; }
    .roles:hover, .roles:focus { background: var(--bg-elev); border-color: var(--border); }
    .x { display: inline-flex; color: var(--text-3); } .x:hover { color: var(--danger); }
    .legend { margin: 0 0 6px; font-size: 11px; color: var(--text-3); } .legend .dim { opacity: 0.7; }
    .loading { display: flex; justify-content: center; padding: 24px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EntityCardView {
  readonly entityId = input.required<number>();
  /** Another entity clicked inside the card (a studio of the model, a derived chip): the panel navigates there. */
  readonly open = output<number>();

  protected readonly catalog = inject(CatalogStore);
  protected readonly store = inject(AlbumStore);
  protected readonly gw = inject(Gateway);
  private readonly router = inject(Router);
  private readonly toasts = inject(Toasts);
  private readonly picker = inject(EntityPicker);
  private readonly confirm = inject(Confirm);
  private readonly actions = inject(Actions);
  private readonly menu = inject(ContextMenu);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  protected readonly kindName = KIND;
  protected readonly icon = KIND_ICON;
  protected readonly noun = NOUN;

  protected readonly cover = signal<Album | null>(null);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  /** Studio id whose "credited as" cell is an input. */
  protected readonly editing = signal<number | null>(null);
  protected readonly fields = computed<Field[]>(() => FIELDS[this.catalog.card()?.entity.kind ?? 'TAG']);
  protected readonly sections = computed<Section[]>(() => SECTIONS[this.catalog.card()?.entity.kind ?? 'TAG']);
  protected readonly profile = computed(() => parseProfile(this.catalog.card()?.entity.profile));
  protected readonly studioLines = computed<StudioLine[]>(() => {
    const c = this.catalog.card(), lines = new Map<number, StudioLine>();
    if (!c) return [];
    for (const s of c.derived?.studios ?? []) lines.set(s.entity.id, { entity: s.entity, alias: '', roles: '', albums: s.albums, curated: false, derivedAliases: s.aliases, genres: s.genres });
    for (const l of c.links) if (l.to.kind === 'STUDIO') {
      const line = lines.get(l.to.id) ?? { entity: l.to, alias: '', roles: '', albums: 0, curated: true, derivedAliases: [], genres: [] };
      lines.set(l.to.id, { ...line, curated: true, alias: l.alias ?? '', roles: l.roles ?? '' });
    }
    return [...lines.values()].sort((a, b) => b.albums - a.albums || a.entity.name.localeCompare(b.entity.name));
  });
  private savedTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    // the card is usually already open in the store (the panel renders this for `cardId`); another id asked here opens it
    effect(() => { const id = this.entityId(); untracked(() => { if (this.catalog.cardId() !== id) this.catalog.openCard(id); this.cover.set(null); void this.loadCover(id); }); });
    inject(DestroyRef).onDestroy(() => this.catalog.closeCard());
  }

  /** The newest album linked to the entity, for the header. */
  private async loadCover(id: number) {
    try { const r = await this.gw.search({ entities: [id], sort: 'NEWEST', offset: 0, limit: 1 }); if (this.entityId() === id) this.cover.set(r.albums[0] ?? null); }
    catch { /* a header without a cover */ }
  }

  // ---- values ----

  protected age(v: unknown) { return ageFrom(v); }
  protected ftIn(v: unknown) { return cmToFtIn(v); }
  protected lb(v: unknown) { return kgToLb(v); }
  protected chipsText(v: unknown) { return Array.isArray(v) ? v.join(', ') : typeof v === 'string' ? v : ''; }
  protected linksOf(c: EntityCard, kind: EntityKind) { return c.links.filter(l => l.to.kind === kind); }
  protected derivedOf(c: EntityCard, s: Section) { const curated = new Set(this.linksOf(c, s.kind).map(l => l.to.id)); return (c.derived ? s.derived(c.derived) ?? [] : []).filter(d => !curated.has(d.id)); }
  protected derivedTags(c: EntityCard) { const own = new Set(this.linksOf(c, 'TAG').map(l => l.to.name)); return (c.derived?.tags ?? []).filter(t => !own.has(t)); }
  private links(c: EntityCard): EntityLinkIn[] { return c.links.map(l => ({ to: l.to.id, ...(l.alias ? { alias: l.alias } : {}), ...(l.roles ? { roles: l.roles } : {}) })); }

  // ---- edits (each one queued save, built from the card as it is when sent; null `aliases`/`links` keep the current set) ----

  protected rename(name: string) { const n = name.trim(); if (n) void this.save(c => n !== c.entity.name ? { name: n } : null); }
  protected rate(rating: number) { void this.save(() => ({ rating })); }
  protected removeAlias(a: string) { void this.save(c => ({ aliases: c.aliases.filter(x => x !== a) })); }
  protected addAlias(input: HTMLInputElement) {
    const a = input.value.trim();
    input.value = '';
    if (a) void this.save(c => c.aliases.includes(a.toLowerCase()) ? null : { aliases: [...c.aliases, a] });
  }
  protected setField(f: Field, raw: string) {
    const value = f.type === 'number' ? (raw === '' ? '' : Number(raw)) : f.type === 'chips' ? parseChips(raw) : raw.trim();
    void this.save(c => ({ profile: withField(parseProfile(c.entity.profile), f.key, value) }));
  }
  protected removeLink(id: number) { void this.save(c => ({ links: this.links(c).filter(l => l.to !== id) })); }
  protected setRoles(s: StudioLine, roles: string) {
    const r = roles.trim();
    if (r === s.roles) return;
    void this.save(c => ({ links: [...this.links(c).filter(l => l.to !== s.entity.id), { to: s.entity.id, ...(s.alias ? { alias: s.alias } : {}), ...(r ? { roles: r } : {}) }] }));
  }

  protected edit(s: StudioLine) { this.editing.set(s.entity.id); setTimeout(() => this.host.querySelector<HTMLInputElement>('input.alias')?.focus()); }

  /** "Credited as Y at studio X": the curated link plus the rewrite of her albums there (`creditAs`); an empty name just closes the cell. */
  protected credit(s: StudioLine, alias: string) {
    const c = this.catalog.card(), a = alias.trim();
    this.editing.set(null);
    if (c && a && a !== s.alias) void this.attempt(this.catalog.tryCreditAs(c.entity.id, s.entity.id, a, s.roles || undefined));
  }

  protected async addLink(kind: EntityKind) {
    const c = this.catalog.card();
    if (!c) return;
    const e = await this.picker.pick({ kind, title: `Add ${NOUN[kind]} to ${c.entity.name}`, allowCreate: true });
    if (e && e.id !== c.entity.id) void this.save(x => x.links.some(l => l.to.id === e.id) ? null : { links: [...this.links(x), { to: e.id }] });
  }

  /** A hashtag is a TAG entity: found by name, else created, then linked. */
  protected async addTag(input: HTMLInputElement) {
    const name = input.value.trim().replace(/^#+/, ''), c = this.catalog.card();
    input.value = '';
    if (!name || !c) return;
    try {
      const found = (await this.gw.entities('TAG', { text: name, limit: 20 })).items.find(t => t.name === name.toLowerCase().replace(/\s+/g, '-'));
      const id = found?.id ?? (await this.catalog.trySave({ kind: 'TAG', name, rating: 0, profile: null, aliases: [], links: [] })).entity.id;
      void this.save(x => x.links.some(l => l.to.id === id) ? null : { links: [...this.links(x), { to: id }] });
    } catch (e) { this.toasts.error(e, 'Tag failed: '); }
  }

  /** ⋯: the entity-level flows of the tree's context menu (`Actions`); a merge or delete closes or replaces the card through the store. */
  protected more(ev: MouseEvent, e: Entity) {
    const r = (ev.currentTarget as HTMLElement).getBoundingClientRect();
    this.menu.open(r.right, r.bottom + 4, [
      { label: 'Show albums', icon: 'images', disabled: !e.albums, run: () => this.showAlbums() },
      ...(e.kind === 'MODEL' ? [{ label: 'Credited as…', icon: 'building', run: () => void this.actions.creditAs(e) } as MenuEntry] : []),
      { sep: true },
      { label: 'Merge into…', icon: 'layers', run: () => void this.actions.mergeInto(e.kind, [e.id]) },
      { label: `Delete ${NOUN[e.kind]}…`, icon: 'trash', danger: true, run: () => void this.actions.deleteEntities(e.kind, [e.id]) },
    ]);
  }

  protected async confirmIt() { const c = this.catalog.card(); if (c && await this.catalog.confirm([c.entity.id])) this.flash(); }

  protected showAlbums() { const c = this.catalog.card(); if (c) { this.store.setFilter('missing', null); this.store.setFilter('entities', [c.entity.id]); } }
  protected showPhotos() { const c = this.catalog.card(); if (c) void this.router.navigate(['/list', c.entity.id]); }

  /** One `saveEntity` through `CatalogStore.trySaveCard`: queued behind the card's earlier saves, `patch` over the card as it is when sent. */
  protected save(patch: (c: EntityCard) => Partial<EntitySave> | null) { return this.attempt(this.catalog.trySaveCard(patch)); }

  /** Runs one mutation with the saving state; a name collision becomes a merge offer, any other failure a toast (null = nothing was sent). */
  private async attempt(p: Promise<unknown>) {
    this.saving.set(true);
    try { if (await p !== null) this.flash(); }
    catch (e) { const taken = CatalogStore.takenBy(e); if (taken) await this.offerMerge(taken); else this.toasts.error(e, 'Save failed: '); }
    finally { this.saving.set(false); }
  }

  /** `"<name>" is <owner>; merge instead?` → merge this entity into the owner (looked up by name, else chosen in the picker). */
  private async offerMerge({ name, owner }: { name: string; owner: string }) {
    const c = this.catalog.card();
    if (!c) return;
    const ok = await this.confirm.ask({ title: 'Name already in use', message: `"${name}" belongs to ${owner}. Merge ${c.entity.name} into ${owner}? Its albums, aliases and links move there.`, okLabel: `Merge into ${owner}` });
    if (!ok) return;
    let target: Entity | null = null;
    try { target = (await this.gw.entities(c.entity.kind, { text: owner, limit: 20 })).items.find(e => e.name.toLowerCase() === owner.toLowerCase()) ?? null; } catch { /* the picker below */ }
    target ??= await this.picker.pick({ kind: c.entity.kind, title: `Merge ${c.entity.name} into…` });
    if (target && target.id !== c.entity.id && await this.catalog.merge([c.entity.id], target.id)) { this.toasts.show(`Merged into ${target.name}`, 'success'); this.open.emit(target.id); }
  }

  private flash() { this.saved.set(true); clearTimeout(this.savedTimer); this.savedTimer = setTimeout(() => this.saved.set(false), 2000); }
}
