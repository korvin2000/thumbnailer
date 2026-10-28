import { Injector } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { CatalogStore } from './catalog-store';
import { Gateway } from './gateway';
import { Entity, EntityCard, EntitySave } from './model';
import { Toasts } from './toasts';

const entity = (id: number, name = 'A'): Entity => ({ id, kind: 'MODEL', name, rating: 0, auto: false, albums: 0 });
const card = (id: number, name = 'A'): EntityCard => ({ entity: entity(id, name), aliases: [], links: [] });

/** A gateway that counts reads; mutations resolve at once (`over` replaces any method). */
const setup = (over: Record<string, unknown> = {}) => {
  const calls = { entities: 0, entity: 0 };
  const toasts: string[] = [];
  const gw = {
    entities: async () => { calls.entities++; return { items: [entity(1)], total: 1 }; },
    entity: async (id: number) => { calls.entity++; if (id === 404) throw new Error('No entity 404'); return card(id, 'E' + id); },
    saveEntity: async (e: { name: string }) => { if (e.name === 'taken') throw new Error('"taken" is Owner; merge instead?'); return card(2, 'B'); },
    mergeEntities: async (_: number[], into: number) => card(into),
    deleteEntities: async () => {}, confirmEntities: async () => {},
    bulkUpdate: async () => 3, link: async () => 2,
    ...over,
  };
  const store = Injector.create({ providers: [{ provide: Gateway, useValue: gw }, { provide: Toasts, useValue: { error: (e: Error) => toasts.push(e.message) } }, CatalogStore] }).get(CatalogStore);
  return { store, calls, toasts };
};
const settle = () => new Promise(r => setTimeout(r));
/** A promise settled by the test. */
const deferred = <T>() => { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; };

describe('CatalogStore', () => {
  it('caches entity pages until a mutation of its own resolves', async () => {
    for (const run of [(s: CatalogStore) => s.save({ kind: 'MODEL', name: 'B', rating: 0, profile: null }), (s: CatalogStore) => s.merge([3], 1),
                       (s: CatalogStore) => s.delete([3]), (s: CatalogStore) => s.confirm([3])]) {
      const { store, calls } = setup();
      await store.entities('MODEL'); await store.entities('MODEL');
      expect(calls.entities).toBe(1);
      const v = store.version();
      await run(store);
      await store.entities('MODEL');
      expect(calls.entities).toBe(2);
      expect(store.version()).toBe(v + 1);
    }
  });

  it('reloads the open card after a mutation that did not return it', async () => {
    const { store, calls } = setup();
    store.openCard(1); await settle();
    expect(calls.entity).toBe(1);
    await store.confirm([3]); await settle();
    expect(calls.entity).toBe(2);
    await store.save({ kind: 'MODEL', name: 'B', rating: 0, profile: null }); await settle();
    expect(calls.entity).toBe(3);
  });

  it('a merge into the open card shows the returned card; deleting it closes the card', async () => {
    const { store } = setup();
    store.openCard(3); await settle();
    await store.merge([3], 1); await settle();
    expect(store.cardId()).toBe(1);
    expect(store.card()?.entity.id).toBe(1);
    await store.delete([1]);
    expect(store.cardId()).toBeNull();
    expect(store.card()).toBeNull();
  });

  it('remembers every entity a page or card named, and fetches the ones a scope chip lacks (refreshing known ones only when asked); a gone id is reported', async () => {
    const { store, calls } = setup();
    expect(store.entityOf(1)).toBeUndefined();
    await store.entities('MODEL');
    expect(store.entityOf(1)?.name).toBe('A');
    expect(await store.ensureKnown([1, 7, 7])).toEqual([]);
    expect(calls.entity).toBe(1); // 1 was known, 7 asked once
    expect(store.entityOf(7)?.name).toBe('E7');
    expect(await store.ensureKnown([1, 404], true)).toEqual([404]);
    expect(calls.entity).toBe(3);
    expect(store.entityOf(1)?.name).toBe('E1'); // refreshed from its card
  });

  it('a link drops the pages of the entity\'s kind (its counts changed without a catalogChanged)', async () => {
    const { store, calls } = setup();
    await store.entities('MODEL');
    const v = store.version();
    expect(await store.link({ albumIds: ['a'] }, 1)).toBe(2);
    expect(store.version()).toBe(v + 1);
    await store.entities('MODEL');
    expect(calls.entities).toBe(2);
  });

  it('queues the open card\'s saves and builds each from the card the previous reply updated', async () => {
    const sent: EntitySave[] = [];
    const { store } = setup({ saveEntity: async (e: EntitySave) => { await settle(); sent.push(e); return { ...card(1, e.name), entity: { ...entity(1, e.name), rating: e.rating, ...(e.profile != null ? { profile: e.profile } : {}) } }; } });
    store.openCard(1); await settle();
    const first = store.trySaveCard(() => ({ rating: 5 })), second = store.trySaveCard(c => ({ profile: `{"was":${c.entity.rating}}` })), none = store.trySaveCard(() => null);
    await Promise.all([first, second, none]);
    expect(sent.map(e => [e.rating, e.profile])).toEqual([[5, null], [5, '{"was":5}']]);
    expect(await none).toBeNull();
    expect(store.card()?.entity).toMatchObject({ rating: 5, profile: '{"was":5}' });
  });

  it('a queued save follows the entity it was made on, built from that entity\'s last saved card, not from the card opened since', async () => {
    const d = deferred<EntityCard>(), sent: EntitySave[] = [];
    let first = true;
    const { store } = setup({ saveEntity: (e: EntitySave) => { sent.push(e); if (first) { first = false; return d.promise; } return Promise.resolve({ ...card(e.id!, e.name), entity: { ...entity(e.id!, e.name), rating: e.rating } }); } });
    store.openCard(1); await settle();
    const a = store.trySaveCard(() => ({ rating: 5 })); await settle(); // sent, pending
    const b = store.trySaveCard(c => ({ name: c.entity.name + '!' })); // queued
    store.openCard(2); await settle();
    d.resolve({ ...card(1, 'E1'), entity: { ...entity(1, 'E1'), rating: 5 } });
    await Promise.all([a, b]);
    expect(sent.map(e => [e.id, e.rating, e.name])).toEqual([[1, 5, 'E1'], [1, 5, 'E1!']]);
    expect(store.card()?.entity.id).toBe(2);
  });

  it('a save queued before its entity was deleted goes out and fails on the server\'s answer; the card stays closed', async () => {
    const d = deferred<EntityCard>(), sent: EntitySave[] = [];
    const { store } = setup({ saveEntity: (e: EntitySave) => { sent.push(e); return sent.length === 1 ? d.promise : Promise.reject(new Error('No entity 1')); } });
    store.openCard(1); await settle();
    const a = store.trySaveCard(() => ({ rating: 5 })); await settle();
    const b = store.trySaveCard(() => ({ rating: 6 }));
    await store.delete([1]);
    d.resolve(card(1));
    await a;
    await expect(b).rejects.toThrow('No entity 1');
    expect(sent.map(e => e.rating)).toEqual([5, 6]);
    expect(store.cardId()).toBeNull();
  });

  it('a library switch while saves are queued sends none of them (Library changed), and the reply of the one in flight does not repopulate known', async () => {
    const d = deferred<EntityCard>(), sent: EntitySave[] = [];
    const { store } = setup({ saveEntity: (e: EntitySave) => { sent.push(e); return d.promise; } });
    store.openCard(1); await settle();
    const a = store.trySaveCard(() => ({ rating: 5 })); await settle();
    const b = store.trySaveCard(() => ({ rating: 6 }));
    store.reset();
    d.resolve(card(1));
    await a;
    await expect(b).rejects.toThrow('Library changed');
    expect(sent).toHaveLength(1);
    expect(store.known().size).toBe(0);
  });

  it('a page asked before a reset or a change never repopulates known; a failed page evicts itself, not the newer request for its key', async () => {
    const pending: { resolve: (v: { items: Entity[]; total: number }) => void; reject: (e: Error) => void }[] = [];
    const { store, calls } = setup({ entities: () => { calls.entities++; return new Promise<{ items: Entity[]; total: number }>((resolve, reject) => pending.push({ resolve, reject })); } });
    const p1 = store.entities('MODEL'); store.reset(); const p2 = store.entities('MODEL');
    expect(calls.entities).toBe(2);
    pending[0].reject(new Error('x')); await p1.catch(() => {});
    expect(store.entities('MODEL')).toBe(p2);
    pending[1].resolve({ items: [entity(1, 'new')], total: 1 }); await p2;
    expect(store.entityOf(1)?.name).toBe('new');
    const p3 = store.entities('STUDIO'); store.onChanged(['MODEL']);
    pending[2].resolve({ items: [entity(5, 'late')], total: 1 }); await p3;
    expect(store.entityOf(5)).toBeUndefined();
  });

  it('an id the server no longer has leaves known', async () => {
    let gone = false;
    const { store } = setup({ entity: async (id: number) => { if (gone) throw new Error('No entity ' + id); return card(id, 'E' + id); } });
    await store.ensureKnown([7]);
    expect(store.entityOf(7)?.name).toBe('E7');
    gone = true;
    expect(await store.ensureKnown([7], true)).toEqual([7]);
    expect(store.entityOf(7)).toBeUndefined();
  });

  it('shares one lists request between callers; a LIST change in flight starts another whose answer wins, whichever lands last', async () => {
    const pending: { resolve: (v: { items: Entity[]; total: number }) => void }[] = [];
    const { store, calls } = setup({ entities: () => { calls.entities++; const d = deferred<{ items: Entity[]; total: number }>(); pending.push(d); return d.promise; } });
    const a = store.loadLists(), b = store.loadLists();
    expect(calls.entities).toBe(1);
    store.onChanged(['LIST']);
    expect(calls.entities).toBe(2);
    pending[1].resolve({ items: [entity(9, 'fresh')], total: 1 });
    pending[0].resolve({ items: [entity(8, 'stale')], total: 1 });
    expect((await a).map(e => e.name)).toEqual(['fresh']);
    expect((await b).map(e => e.name)).toEqual(['fresh']);
    expect(store.lists().map(e => e.name)).toEqual(['fresh']);
  });

  it('trySave leaves a name collision to the caller; save toasts it; a hidden bulk update drops the pages', async () => {
    const { store, calls, toasts } = setup();
    await expect(store.trySave({ kind: 'MODEL', name: 'taken', rating: 0, profile: null })).rejects.toThrow();
    expect(toasts).toEqual([]);
    expect(await store.save({ kind: 'MODEL', name: 'taken', rating: 0, profile: null })).toBeNull();
    expect(toasts).toEqual(['"taken" is Owner; merge instead?']);
    expect(CatalogStore.takenBy(new Error(toasts[0]))).toEqual({ name: 'taken', owner: 'Owner' });
    expect(CatalogStore.takenBy(new Error('No entity 3'))).toBeNull();
    await store.entities('MODEL');
    const v = store.version();
    expect(await store.bulkUpdate({ query: { hidden: true } }, { hidden: false })).toBe(3);
    expect(store.version()).toBe(v + 1);
    await store.entities('MODEL');
    expect(calls.entities).toBe(2);
  });
});
