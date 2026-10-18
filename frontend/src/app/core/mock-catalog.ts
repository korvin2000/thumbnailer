import { Subject } from 'rxjs';
import {
  Album, AlbumPatch, AlbumTarget, Comparison, Entity, EntityCard, EntityKind, EntityLinkIn, EntityList, EntityQuery, EntitySave, GatewayEvent, Link, StudioRow,
} from './model';
import { delay, GENRES, MODELS, PHOTOGRAPHERS, STUDIOS, TAGS, targets } from './mock-gateway';
import type { MockPhotos } from './mock-photos';

const LISTS = ['English models', 'Best of 2024'];
/** The album text each role resolves from (favorites lists are no text). */
const FIELDS = { MODEL: 'model', STUDIO: 'studio', PHOTOGRAPHER: 'photographer', GENRE: 'genres', TAG: 'tags' } as const;
type Role = keyof typeof FIELDS;
const ROLES = Object.keys(FIELDS) as Role[];
const LIMIT = 500;
/** `AlbumMeta.tag`: trimmed, leading `#`s dropped, whitespace → `-`, lower case. */
export const tag = (s: string) => s.trim().replace(/^#+/, '').replace(/\s+/g, '-').toLowerCase();
/** The alias key of a name (`Catalog.norm`): trimmed, lower case, whitespace collapsed (tags as `tag`), ≤ 256 chars. */
export const norm = (kind: EntityKind, s: string) => (kind === 'TAG' ? tag(s) : s.trim().toLowerCase().replace(/\s+/g, ' ')).slice(0, 256);

/** An entity with what only its card shows. */
interface Row extends Entity { aliases: string[]; links: EntityLinkIn[] }

/** Catalog emulation: entities, album text resolved into `links` (unknown names become `auto` entities), counts,
 *  favorites lists, assign/unassign through the albums' text, and copies by stamp. */
export class MockCatalog {
  private readonly rows = new Map<number, Row>();
  /** Favorites list → album ids. */
  private readonly members = new Map<number, Set<string>>();
  /** Kinds that got auto entities since the last `sync`. */
  private readonly created = new Set<EntityKind>();
  private seq = 0;

  /** `save` is the mock's album save (revision, re-resolve, `albumChanged`); `photos` counts a list's photos and makes them follow a merge or delete. */
  constructor(private readonly albums: Map<string, Album>, private readonly events: Subject<GatewayEvent>,
              private readonly save: (albumId: string, patch: AlbumPatch) => Album, private readonly photos: () => Pick<MockPhotos, 'count' | 'remapList' | 'removeList'>) {}

  /** Entities from the mock's name lists (the last three models left `auto`), two favorites lists, every album resolved; returns the list ids. */
  seed(): number[] {
    const add = (kind: EntityKind, names: (string | null)[], auto = (_: number) => false) => names.filter(n => n).forEach((n, i) => this.create(kind, n!, auto(i)));
    add('MODEL', MODELS, i => i >= MODELS.length - 3);
    add('STUDIO', STUDIOS); add('PHOTOGRAPHER', PHOTOGRAPHERS); add('GENRE', GENRES); add('TAG', TAGS);
    this.find('MODEL', 'Nika')!.aliases.push('Nikka');
    const [english, best] = LISTS.map(n => this.create('LIST', n, false).id);
    for (const a of this.albums.values()) {
      if (a.model === 'Sofia' || a.model === 'Vera') this.members.get(english)!.add(a.id);
      if (a.shotDate?.startsWith('2024') && a.rating >= 7) this.members.get(best)!.add(a.id);
    }
    this.sync(undefined, true);
    return [english, best];
  }

  /** Re-resolves the given albums (all when omitted), then every album's `copies` and every entity's `albums`; announces new auto entities unless quiet. */
  sync(ids?: Iterable<string>, quiet = false) {
    for (const id of ids ?? [...this.albums.keys()]) { const a = this.albums.get(id); if (a) this.albums.set(id, { ...a, links: this.resolve(a) }); }
    const stamps = new Map<string, number>();
    for (const a of this.albums.values()) stamps.set(a.stamp, (stamps.get(a.stamp) ?? 0) + 1);
    for (const a of this.albums.values()) if (a.copies !== stamps.get(a.stamp)) this.albums.set(a.id, { ...a, copies: stamps.get(a.stamp)! });
    for (const r of this.rows.values()) r.albums = 0;
    for (const a of this.albums.values()) if (!a.hidden) for (const l of a.links) { const r = this.rows.get(l.entity); if (r) r.albums++; }
    if (this.created.size && !quiet) this.changed([...this.created]);
    this.created.clear();
  }

  async entities(kind: EntityKind, o: EntityQuery = {}): Promise<EntityList> {
    await delay(40);
    const t = o.text ? norm(kind, o.text) : '', scope = o.under != null ? this.albumsOf(o.under) : null, offset = o.offset ?? 0;
    const limit = o.limit! > 0 ? Math.min(o.limit!, LIMIT) : 100;
    const all = [...this.rows.values()].filter(r => r.kind === kind && (!t || [r.name, ...r.aliases].some(n => norm(kind, n).startsWith(t)))
      && (!scope || scope.some(a => a.links.some(l => l.entity === r.id))))
      .sort((x, y) => (o.byCount ? y.albums - x.albums : 0) || x.name.localeCompare(y.name));
    return { items: all.slice(offset, offset + limit).map(r => this.view(r)), total: all.length };
  }

  async entity(id: number): Promise<EntityCard> { await delay(60); return this.card(id); }

  /** As `Catalog.save`: absent/null `aliases`/`links` keep the current set; the server's refusals and texts; always `catalogChanged`. */
  async saveEntity(e: EntitySave): Promise<EntityCard> {
    await delay(60);
    if (!e.name?.trim()) throw new Error('A name is required');
    if (!(e.rating >= 0 && e.rating <= 10)) throw new Error('Rating must be 0..10');
    const old = e.id != null ? this.row(e.id) : null, k = (s: string) => norm(e.kind, s);
    if (old && old.kind !== e.kind) throw new Error('The kind of an entity cannot change');
    const name = e.kind === 'TAG' ? tag(e.name) : e.name.trim();
    // a renamed entity keeps its old name as an alias, else album texts would re-create it
    const aliases = [...(e.aliases ?? old?.aliases ?? []), ...(old && k(old.name) !== k(name) ? [old.name] : [])]
      .map(x => x?.trim()).filter((x, i, all) => x && k(x) !== k(name) && all.findIndex(y => y && k(y) === k(x)) === i);
    for (const n of [name, ...aliases]) { const o = this.find(e.kind, n); if (o && o !== old) throw new Error(`"${n}" is ${o.name}; merge instead?`); }
    for (const l of e.links ?? []) if (!this.rows.has(l.to) || l.to === old?.id) throw new Error(`Cannot link to ${l.to}`);
    const r = old ?? this.create(e.kind, name, false);
    Object.assign(r, { name, rating: e.rating, aliases });
    if (e.profile != null) r.profile = e.profile; else delete r.profile;
    if (e.links != null) r.links = e.links.map(l => ({ ...l }));
    this.sync();
    this.changed([r.kind]);
    return this.card(r.id);
  }

  /** Unassigns each entity from its albums (text edits, `albumChanged` each), then drops it; a list's favorites go with it. */
  async deleteEntities(ids: number[]): Promise<void> {
    await delay(60);
    const kinds = new Set(ids.map(id => this.row(id).kind));
    for (const id of new Set(ids)) {
      if (this.row(id).kind === 'LIST') this.photos().removeList(id); else this.unassign(this.albumsOf(id, true).map(a => a.id), id);
      this.rows.delete(id); this.members.delete(id);
      for (const r of this.rows.values()) r.links = r.links.filter(l => l.to !== id);
    }
    this.sync();
    this.changed([...kinds]);
  }

  /** A's names become B's aliases, its links, members and favorites move to B; album texts stay and now resolve to B. */
  async mergeEntities(from: number[], into: number): Promise<EntityCard> {
    await delay(80);
    const b = this.row(into), merged = from.filter(id => id !== into).map(id => this.row(id));
    if (merged.some(a => a.kind !== b.kind)) throw new Error('Only entities of one kind can be merged');
    for (const a of merged) {
      b.aliases = [...b.aliases, a.name, ...a.aliases].filter((n, i, all) => norm(b.kind, n) !== norm(b.kind, b.name) && all.findIndex(x => norm(b.kind, x) === norm(b.kind, n)) === i);
      b.rating = Math.max(a.rating, b.rating); b.auto &&= a.auto; b.profile ??= a.profile;
      b.links = [...b.links, ...a.links];
      this.members.get(a.id)?.forEach(m => this.members.get(into)?.add(m));
      if (a.kind === 'LIST') this.photos().remapList(a.id, into);
      this.rows.delete(a.id); this.members.delete(a.id);
      for (const r of this.rows.values()) r.links = r.links.map(l => l.to === a.id ? { ...l, to: into } : l).filter((l, i, all) => l.to !== r.id && all.findIndex(x => x.to === l.to) === i);
    }
    this.sync();
    this.changed([b.kind]);
    return this.card(into);
  }

  async confirmEntities(ids: number[]): Promise<void> {
    await delay(40);
    for (const id of ids) this.row(id).auto = false;
    this.changed([...new Set(ids.map(id => this.row(id).kind))]);
  }

  /** The model's credit at a studio: a curated link + alias, then her albums there say the alias. */
  async creditAs(modelId: number, studioId: number, alias: string, roles?: string): Promise<EntityCard> {
    await delay(80);
    const m = this.row(modelId), owner = this.find('MODEL', alias);
    if (owner && owner.id !== modelId) throw new Error(`"${alias}" is ${owner.name}; merge instead?`);
    if (!owner) m.aliases.push(alias.trim());
    m.links = [...m.links.filter(l => l.to !== studioId), { to: studioId, alias: alias.trim(), ...(roles ? { roles } : {}) }];
    for (const a of this.albumsOf(modelId, true).filter(a => a.links.some(l => l.entity === studioId)))
      this.save(a.id, { model: this.names(a, 'MODEL').map(x => this.find('MODEL', x)?.id === modelId ? alias.trim() : x).join(', ') });
    this.changed(['MODEL']);
    return this.card(modelId);
  }

  async bulkUpdate(t: AlbumTarget, patch: AlbumPatch): Promise<number> {
    await delay(60);
    const albums = targets(this.albums, t);
    albums.forEach(a => this.save(a.id, patch));
    return albums.length;
  }

  /** Roles 1–5: the entity's name joins the albums' text; LIST: membership only (no album save, one `catalogChanged` when any changed). Resolves with the albums changed. */
  async link(t: AlbumTarget, entityId: number): Promise<number> { await delay(60); return this.relink(targets(this.albums, t), entityId, true); }

  async unlink(t: AlbumTarget, entityId: number): Promise<number> { await delay(60); return this.relink(targets(this.albums, t), entityId, false); }

  async copies(albumId: string): Promise<Album[]> {
    await delay(40);
    const a = this.albums.get(albumId);
    if (!a) throw new Error('Album not found');
    return [...this.albums.values()].filter(x => x.stamp === a.stamp).sort((x, y) => x.path.localeCompare(y.path));
  }

  async compareCopies(a: string, b: string): Promise<Comparison> {
    await delay(300);
    const x = this.albums.get(a), y = this.albums.get(b);
    if (!x || !y) throw new Error('Album not found');
    return x.stamp === y.stamp ? { identical: true, differing: [] } : { identical: false, differing: ['001.jpg'] };
  }

  // ---- internals ----

  isList(id: number): boolean { return this.rows.get(id)?.kind === 'LIST'; }

  private create(kind: EntityKind, name: string, auto: boolean): Row {
    const r: Row = { id: ++this.seq, kind, name, rating: 0, auto, albums: 0, aliases: [], links: [] };
    if (kind === 'LIST') this.members.set(r.id, new Set());
    this.rows.set(r.id, r);
    return r;
  }

  private row(id: number): Row {
    const r = this.rows.get(id);
    if (!r) throw new Error(`No entity ${id}`);
    return r;
  }

  /** The entity of the kind that has this name or alias (normalized). */
  private find(kind: EntityKind, name: string): Row | undefined {
    const n = norm(kind, name);
    return [...this.rows.values()].find(r => r.kind === kind && (norm(kind, r.name) === n || r.aliases.some(x => norm(kind, x) === n)));
  }

  private view(r: Row): Entity {
    const { aliases, links, ...e } = r;
    return r.kind === 'LIST' ? { ...e, photos: this.photos().count(r.id) } : e;
  }

  private albumsOf(id: number, withHidden = false): Album[] {
    return [...this.albums.values()].filter(a => (withHidden || !a.hidden) && a.links.some(l => l.entity === id));
  }

  /** An album's names for a role: list fields as they are; a text field is one name when it is known whole, else split on `,` / `;`. */
  private names(a: Album, role: Role): string[] {
    const v = a[FIELDS[role]];
    if (Array.isArray(v)) return v;
    if (!v?.trim()) return [];
    return this.find(role, v) ? [v.trim()] : v.split(/[,;]/).map(s => s.trim()).filter(Boolean);
  }

  /** The album's links: its text resolved per role (a miss creates an auto entity; tags are never auto), then its favorites lists. */
  private resolve(a: Album): Link[] {
    const out: Link[] = [];
    for (const role of ROLES) for (const name of this.names(a, role)) {
      const r = this.find(role, name) ?? (this.created.add(role), this.create(role, name, role !== 'TAG'));
      if (!out.some(l => l.entity === r.id)) out.push(norm(role, name) === norm(role, r.name) ? { entity: r.id, role, name: r.name } : { entity: r.id, role, name: r.name, alias: name });
    }
    for (const [id, set] of this.members) if (set.has(a.id)) out.push({ entity: id, role: 'LIST', name: this.rows.get(id)!.name });
    return out;
  }

  /** Links (`on`) or unlinks the albums not yet in that state, each once; returns how many changed. */
  private relink(albums: Album[], id: number, on: boolean): number {
    const r = this.row(id), linked = (albumId: string) => this.albums.get(albumId)!.links.some(l => l.entity === id);
    const todo = [...new Set(albums.map(a => a.id))].filter(x => linked(x) !== on);
    if (r.kind !== 'LIST') { if (on) this.assign(todo, id); else this.unassign(todo, id); return todo.filter(x => linked(x) === on).length; }
    const set = this.members.get(id)!;
    todo.forEach(x => on ? set.add(x) : set.delete(x));
    if (todo.length) { this.sync(todo); this.changed(['LIST']); }
    return todo.length;
  }

  /** Adds the entity's name to the albums' text of its role. */
  private assign(ids: string[], id: number) {
    const r = this.row(id), role = r.kind as Role, field = FIELDS[role];
    for (const a of ids.map(x => this.albums.get(x)!)) { const names = [...this.names(a, role), r.name]; this.save(a.id, { [field]: Array.isArray(a[field]) ? names : names.join(', ') } as AlbumPatch); }
  }

  /** Drops every token that resolves to the entity from the albums' text; nothing left = the explicit empty "". */
  private unassign(ids: string[], id: number) {
    const role = this.row(id).kind as Role, field = FIELDS[role];
    for (const a of ids.map(x => this.albums.get(x)!)) {
      const names = this.names(a, role), keep = names.filter(x => this.find(role, x)?.id !== id);
      if (keep.length < names.length) this.save(a.id, { [field]: Array.isArray(a[field]) ? keep : keep.join(', ') } as AlbumPatch);
    }
  }

  private changed(kinds: EntityKind[]) { this.events.next({ type: 'catalogChanged', kinds }); }

  /** `aliases` are the other names, normalized as the server's alias table stores them. */
  private card(id: number): EntityCard {
    const r = this.row(id), albums = this.albumsOf(id);
    const linked = (kind: EntityKind) => [...new Set(albums.flatMap(a => a.links.filter(l => l.role === kind && l.entity !== id).map(l => l.entity)))].map(x => this.view(this.row(x)));
    const studios = new Map<number, StudioRow>();
    for (const a of albums) for (const l of a.links) if (l.role === 'STUDIO' && l.entity !== id) {
      const s = studios.get(l.entity) ?? { entity: this.view(this.row(l.entity)), aliases: [], genres: [], albums: 0 }, alias = a.links.find(x => x.entity === id)?.alias;
      if (alias && !s.aliases.includes(alias)) s.aliases.push(alias);
      s.genres = [...new Set([...s.genres, ...a.genres])];
      s.albums++;
      studios.set(l.entity, s);
    }
    return {
      entity: this.view(r), aliases: [...new Set(r.aliases.map(n => norm(r.kind, n)))], links: r.links.filter(l => this.rows.has(l.to)).map(l => ({ ...l, to: this.view(this.row(l.to)) })),
      derived: { albums: albums.length, models: linked('MODEL'), studios: [...studios.values()], photographers: linked('PHOTOGRAPHER'), genres: linked('GENRE'), tags: linked('TAG').map(e => e.name) },
    };
  }
}
