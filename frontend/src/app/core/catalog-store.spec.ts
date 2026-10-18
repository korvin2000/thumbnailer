import { Injector } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { CatalogStore } from './catalog-store';
import { Gateway } from './gateway';
import { Entity, EntityCard } from './model';
import { Toasts } from './toasts';

const entity = (id: number, name = 'A'): Entity => ({ id, kind: 'MODEL', name, rating: 0, auto: false, albums: 0 });
const card = (id: number, name = 'A'): EntityCard => ({ entity: entity(id, name), aliases: [], links: [] });

/** A gateway that counts reads; mutations resolve at once. */
const setup = () => {
  const calls = { entities: 0, entity: 0 };
  const gw = {
    entities: async () => { calls.entities++; return { items: [entity(1)], total: 1 }; },
    entity: async (id: number) => { calls.entity++; return card(id); },
    saveEntity: async () => card(2, 'B'), mergeEntities: async (_: number[], into: number) => card(into),
    deleteEntities: async () => {}, confirmEntities: async () => {},
  };
  const store = Injector.create({ providers: [{ provide: Gateway, useValue: gw }, { provide: Toasts, useValue: { error: () => {} } }, CatalogStore] }).get(CatalogStore);
  return { store, calls };
};
const settle = () => new Promise(r => setTimeout(r));

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
});
