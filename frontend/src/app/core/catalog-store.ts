import { inject, Injectable, signal } from '@angular/core';
import { Gateway } from './gateway';
import { AlbumPatch, AlbumTarget, Entity, EntityCard, EntityKind, EntityList, EntityQuery, EntitySave, ENTITY_KINDS } from './model';
import { Toasts } from './toasts';

const CACHE = 24;

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

  private readonly cache = new Map<string, Promise<EntityList>>();
  private listsLoaded = false;
  private cardGen = 0;

  /** `Gateway.entities` through a small cache; a failed page is not kept. */
  entities(kind: EntityKind, opts: EntityQuery = {}): Promise<EntityList> {
    const key = kind + JSON.stringify(opts);
    let page = this.cache.get(key);
    if (!page) {
      page = this.gw.entities(kind, opts);
      page.catch(() => this.cache.delete(key));
      this.cache.set(key, page);
      if (this.cache.size > CACHE) this.cache.delete(this.cache.keys().next().value!);
    }
    return page;
  }

  async loadLists(): Promise<Entity[]> {
    if (!this.listsLoaded) {
      this.listsLoaded = true;
      try { this.lists.set((await this.gw.entities('LIST', { limit: 500 })).items); } catch (e) { this.listsLoaded = false; this.toasts.error(e, 'Lists failed: '); }
    }
    return this.lists();
  }

  openCard(id: number) {
    this.cardId.set(id);
    this.card.set(null);
    void this.reloadCard();
  }

  closeCard() { this.cardGen++; this.cardId.set(null); this.card.set(null); }

  // ---- mutations (null / false = failed, the toast already said why); save/delete/merge/confirm invalidate at once, not only on the `catalogChanged` that follows ----

  save(e: EntitySave) { return this.run(this.gw.saveEntity(e).then(c => { this.onChanged([c.entity.kind]); return this.show(c); }), 'Save failed: '); }
  delete(ids: number[]) { return this.run(this.gw.deleteEntities(ids).then(() => { if (ids.includes(this.cardId()!)) this.closeCard(); this.onChanged(ENTITY_KINDS); return true; }), 'Delete failed: '); }
  merge(from: number[], into: number) { return this.run(this.gw.mergeEntities(from, into).then(c => { if (from.includes(this.cardId()!)) this.cardId.set(into); this.onChanged([c.entity.kind]); return this.show(c); }), 'Merge failed: '); }
  confirm(ids: number[]) { return this.run(this.gw.confirmEntities(ids).then(() => { this.onChanged(ENTITY_KINDS); return true; }), 'Confirm failed: '); }
  creditAs(modelId: number, studioId: number, alias: string, roles?: string) { return this.run(this.gw.creditAs(modelId, studioId, alias, roles).then(c => this.show(c)), 'Credit failed: '); }
  link(target: AlbumTarget, entityId: number) { return this.run(this.gw.link(target, entityId), 'Assign failed: '); }
  unlink(target: AlbumTarget, entityId: number) { return this.run(this.gw.unlink(target, entityId), 'Unassign failed: '); }
  bulkUpdate(target: AlbumTarget, patch: AlbumPatch) { return this.run(this.gw.bulkUpdate(target, patch), 'Update failed: '); }

  /** Routed from `AlbumStore.onEvent` (and called by the mutations above): drops cached pages and reloads what is shown. */
  onChanged(kinds: EntityKind[]) {
    this.cache.clear();
    this.version.update(v => v + 1);
    if (kinds.includes('LIST') && this.listsLoaded) { this.listsLoaded = false; void this.loadLists(); }
    if (this.cardId() !== null) void this.reloadCard();
  }

  private async reloadCard() {
    const id = this.cardId(), gen = ++this.cardGen;
    if (id === null) return;
    try { const c = await this.gw.entity(id); if (gen === this.cardGen) this.card.set(c); }
    catch (e) { if (gen === this.cardGen) this.toasts.error(e, 'Entity failed: '); }
  }

  /** A returned card refreshes the open one when it is the same entity. */
  private show(c: EntityCard) { if (c.entity.id === this.cardId()) { this.cardGen++; this.card.set(c); } return c; }

  private async run<T>(p: Promise<T>, prefix: string): Promise<T | null> {
    try { return await p; } catch (e) { this.toasts.error(e, prefix); return null; }
  }
}
