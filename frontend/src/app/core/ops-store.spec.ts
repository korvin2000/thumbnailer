import { Injector } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { Gateway } from './gateway';
import { Op } from './model';
import { opNoun, OpsStore } from './ops-store';
import { Toasts } from './toasts';

const setup = () => {
  const toasts: string[] = [];
  const gw = { runOp: async () => 'op-1' };
  const store = Injector.create({ providers: [{ provide: Gateway, useValue: gw }, { provide: Toasts, useValue: { show: (t: string) => toasts.push(t), error: (e: Error) => toasts.push(e.message) } }, OpsStore] }).get(OpsStore);
  return { store, toasts };
};
const decrypt: Op = { kind: 'CRYPT', albumIds: ['a'], encrypt: false, conflict: 'RENAME' };

describe('OpsStore', () => {
  it('words an operation by its id: the one started here keeps its direction, another client\'s CRYPT gets both words', async () => {
    const { store } = setup();
    expect(await store.run(decrypt)).toBe('op-1');
    expect(store.current()).toEqual({ id: 'op-1', op: decrypt });
    expect(opNoun(store.opOf({ id: 'op-1', kind: 'CRYPT' }))).toBe('Decrypt');
    expect(opNoun(store.opOf({ id: 'op-2', kind: 'CRYPT' }))).toBe('Encrypt/decrypt');
    expect(opNoun(store.opOf({ id: 'op-2', kind: 'PACK' }))).toBe('Pack');
  });

  it('a clean result is a toast worded from the progress or the local op, "Operation" when neither is known', async () => {
    const { store, toasts } = setup();
    await store.run(decrypt);
    store.onResult({ id: 'op-1', done: 2, skipped: [], failed: [] });
    store.onResult({ id: 'op-9', done: 1, skipped: [], failed: [] });
    expect(toasts).toEqual(['Decrypt: 2 done', 'Operation: 1 done']);
    store.onResult({ id: 'op-1', done: 1, skipped: [{ item: 'x', reason: 'in use' }], failed: [] });
    expect(store.issues()?.id).toBe('op-1');
  });
});
