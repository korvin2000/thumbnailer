import { inject, Injectable, signal } from '@angular/core';
import { Gateway } from './gateway';
import { AlbumPatch, AlbumTarget, Entity, EntityCard, EntityKind, EntityList, EntityQuery, EntitySave, ENTITY_KINDS } from './model';
import { Toasts } from './toasts';

const CACHE = 24;
/** The server's (and the mock's) refusal when a name or alias belongs to another entity of the kind. */
const TAKEN = /^"(.*)" is (.*); merge instead\?$/;
/** The server's (and the mock's) answer for an id that no longer exists (deleted, or merged away). */
const GONE = /^No entity \d+$/;

/** Catalog state beside `AlbumStore`: entity pages (cached until the next `catalogChanged`), favorites lists, the open entity card, and the catalog mutations with error toasts. */
@Injectable({ providedIn: 'root' })
export class CatalogStore {
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);

  /** Bumped on every `catalogChanged` (and reconnect); trees and cards reload on it. */
  readonly version = signal(0);
  /** Favorites lists (kind LIST), filled by the first `loadLists()` and kept fresh after that. */
  readonly lists = signal<Entity[]>([]);
  /** The card of `openCard(id)`; null while loading or closed. */
  readonly card = signal<EntityCard | null>(null);
  readonly cardId = signal<number | null>(null);
  /** The album whose copies the copies popover shows (set by the card's ×N badge); null = closed. */
  readonly copiesOf = signal<string | null>(null);
  /** Every entity seen in a page or card, by id: names for the scope chips without a round trip. */
  readonly known = signal(new Map<number, Entity>());

  private readonly cache = new Map<string, Promise<EntityList>>();
  private readonly pending = new Set<number>();
  /** The one lists request every caller shares (null = not asked yet, or dropped by a LIST change); `listsGen` tells a superseded answer from the current one. */
  private listsPending: Promise<Entity[]> | null = null;
  private listsGen = 0;
  private cardGen = 0;
  /** The open card's saves, one after the other (`trySaveCard`). */
  private saving: Promise<unknown> = Promise.resolve();

  /** `Gateway.entities` through a small cache; a failed page is not kept. */
  entities(kind: EntityKind, opts: EntityQuery = {}): Promise<EntityList> {
    const key = kind + JSON.stringify(opts);
    let page = this.cache.get(key);
    if (!page) {
      page = this.gw.entities(kind, opts).then(p => { this.remember(p.items); return p; });
      page.catch(() => this.cache.delete(key));
      this.cache.set(key, page);
      if (this.cache.size > CACHE) this.cache.delete(this.cache.keys().next().value!);
    }
    return page;
  }

  /** The favorites lists: one request shared by every caller; a LIST change starts a fresh one whose answer wins over an older one still in flight. */
  loadLists(): Promise<Entity[]> { return this.listsPending ??= this.fetchLists(); }

  private async fetchLists(): Promise<Entity[]> {
    const gen = ++this.listsGen;
    try {
      const items = (await this.gw.entities('LIST', { limit: 500 })).items;
      if (gen !== this.listsGen) return this.loadLists(); // superseded: the current request answers
      this.lists.set(items); this.remember(items);
    } catch (e) { if (gen === this.listsGen) { this.listsPending = null; this.toasts.error(e, 'Lists failed: '); } }
    return this.lists();
  }

  openCard(id: number) {
    this.cardId.set(id);
    this.card.set(null);
    void this.reloadCard();
  }

  closeCard() { this.cardGen++; this.cardId.set(null); this.card.set(null); }

  /** A known entity by id (from any page or card loaded so far); undefined until one named it. */
  entityOf(id: number): Entity | undefined { return this.known().get(id); }

  /** Fetches the entities `known` lacks (one card each, once), for chips that only hold an id; `refresh` refetches the known ones too (a catalog change may have renamed or deleted them). Resolves with the ids the server has no entity for. */
  async ensureKnown(ids: number[], refresh = false): Promise<number[]> {
    const gone: number[] = [];
    await Promise.all(ids.filter((id, i) => ids.indexOf(id) === i && (refresh || !this.known().has(id)) && !this.pending.has(id)).map(async id => {
      this.pending.add(id);
      try { this.remember([(await this.gw.entity(id)).entity]); }
      catch (e) { if (GONE.test(e instanceof Error ? e.message : '')) gone.push(id); }
      finally { this.pending.delete(id); }
    }));
    return gone;
  }

  /** Another library: its entity ids mean nothing here, so everything keyed by id goes (pages, names, lists, the open card). */
  reset() {
    this.cache.clear(); this.known.set(new Map()); this.lists.set([]); this.listsPending = null; this.listsGen++;
    this.closeCard(); this.version.update(v => v + 1);
  }

  /** The owner named by a `"<name>" is <owner>; merge instead?` refusal, or null for any other error. */
  static takenBy(e: unknown): { name: string; owner: string } | null {
    const m = TAKEN.exec(e instanceof Error ? e.message : String(e));
    return m ? { name: m[1], owner: m[2] } : null;
  }

  // ---- mutations (null / false = failed, the toast already said why); save/delete/merge/confirm invalidate at once, not only on the `catalogChanged` that follows ----

  /** Like `save`, but the failure is the caller's (no toast): the card turns a name collision into a merge offer. */
  trySave(e: EntitySave) { return this.gw.saveEntity(e).then(c => { this.onChanged([c.entity.kind]); return this.show(c); }); }
  save(e: EntitySave) { return this.run(this.trySave(e), 'Save failed: '); }
  /**
   * `trySave` of the open card with `patch` on top, queued behind the card's earlier saves and built from the card as it is when sent (the
   * earlier reply has updated it by then), so a second edit never resends values the first one changed. A null patch saves nothing (null).
   */
  trySaveCard(patch: (c: EntityCard) => Partial<EntitySave> | null): Promise<EntityCard | null> {
    const p = this.saving.then(() => {
      const c = this.card(), x = c && patch(c);
      return c && x ? this.trySave({ id: c.entity.id, kind: c.entity.kind, name: c.entity.name, rating: c.entity.rating, profile: c.entity.profile ?? null, ...x }) : null;
    });
    this.saving = p.catch(() => {});
    return p;
  }
  delete(ids: number[]) { return this.run(this.gw.deleteEntities(ids).then(() => { if (ids.includes(this.cardId()!)) this.closeCard(); this.onChanged(ENTITY_KINDS); return true; }), 'Delete failed: '); }
  merge(from: number[], into: number) { return this.run(this.gw.mergeEntities(from, into).then(c => { if (from.includes(this.cardId()!)) this.cardId.set(into); this.onChanged([c.entity.kind]); return this.show(c); }), 'Merge failed: '); }
  confirm(ids: number[]) { return this.run(this.gw.confirmEntities(ids).then(() => { this.onChanged(ENTITY_KINDS); return true; }), 'Confirm failed: '); }
  tryCreditAs(modelId: number, studioId: number, alias: string, roles?: string) { return this.gw.creditAs(modelId, studioId, alias, roles).then(c => { this.onChanged(['MODEL']); return this.show(c); }); }
  creditAs(modelId: number, studioId: number, alias: string, roles?: string) { return this.run(this.tryCreditAs(modelId, studioId, alias, roles), 'Credit failed: '); }
  /** Linking changes the entity's stored counts (a list's photos too), which no `catalogChanged` announces for an existing entity: its kind's pages are dropped here. */
  link(target: AlbumTarget, entityId: number) { return this.run(this.gw.link(target, entityId).then(n => this.linked(entityId, n)), 'Assign failed: '); }
  unlink(target: AlbumTarget, entityId: number) { return this.run(this.gw.unlink(target, entityId).then(n => this.linked(entityId, n)), 'Unassign failed: '); }
  /** Hiding or unhiding changes every stored count, which no `catalogChanged` announces: the pages are dropped here. */
  bulkUpdate(target: AlbumTarget, patch: AlbumPatch) { return this.run(this.gw.bulkUpdate(target, patch).then(n => { if (patch.hidden !== undefined) this.onChanged(ENTITY_KINDS); return n; }), 'Update failed: '); }

  /** Routed from `AlbumStore.onEvent` (and called by the mutations above): drops cached pages and reloads what is shown. */
  onChanged(kinds: EntityKind[]) {
    this.cache.clear();
    this.version.update(v => v + 1);
    if (kinds.includes('LIST') && this.listsPending) { this.listsPending = null; void this.loadLists(); }
    if (this.cardId() !== null) void this.reloadCard();
  }

  private linked(entityId: number, n: number) { const k = this.entityOf(entityId)?.kind; this.onChanged(k ? [k] : ENTITY_KINDS); return n; }

  private async reloadCard() {
    const id = this.cardId(), gen = ++this.cardGen;
    if (id === null) return;
    try { const c = await this.gw.entity(id); if (gen === this.cardGen) this.show(c, true); }
    catch (e) { if (gen === this.cardGen) this.toasts.error(e, 'Entity failed: '); }
  }

  /** A returned card refreshes the open one when it is the same entity; its entities join `known`. */
  private show(c: EntityCard, loaded = false) {
    this.remember([c.entity, ...c.links.map(l => l.to), ...(c.derived?.models ?? []), ...(c.derived?.studios ?? []).map(s => s.entity), ...(c.derived?.photographers ?? []), ...(c.derived?.genres ?? [])]);
    if (c.entity.id === this.cardId()) { if (!loaded) this.cardGen++; this.card.set(c); }
    return c;
  }

  private remember(items: Entity[]) {
    if (items.length) this.known.update(m => { const n = new Map(m); for (const e of items) n.set(e.id, e); return n; });
  }

  private async run<T>(p: Promise<T>, prefix: string): Promise<T | null> {
    try { return await p; } catch (e) { this.toasts.error(e, prefix); return null; }
  }
}
