import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { Gateway } from './gateway';
import { Album, AlbumPatch, DEFAULT_SETTINGS, GatewayEvent, SettingsInfo } from './model';
import { AlbumStore } from './store';
import { Toasts } from './toasts';

const album = (id: string, revision = 1, o: Partial<Album> = {}): Album => ({ id, root: 'D:/P', path: `D:/P/${id}`, kind: 'DIRECTORY', name: id, cover: '1.jpg', coverPinned: false, coverPortrait: false, revision, imageCount: 3, bytes: 1, avgPixels: 1, maxWidth: 1, maxHeight: 1, shotDate: null, modifiedAt: '', indexedAt: '', model: null, studio: null, photographer: null, description: null, tags: [], rating: 0, hidden: false, bio: null, stamp: '0', genres: [], links: [], copies: 1, ...o });
const info = (dataDir: string): SettingsInfo => ({ settings: DEFAULT_SETTINGS, dataDir, roots: [], renderEpoch: '' });

/** The store over a fake gateway (TestBed, so its effect runs on `TestBed.tick()`); `update` answers revision 3. */
const setup = () => {
  const events = new Subject<GatewayEvent>();
  const gw = {
    events, mock: true,
    search: async () => ({ albums: [], total: 0 }), facets: async () => ({ tags: [], models: [], studios: [], photographers: [], genres: [] }),
    getSettings: async () => info('C:/lib'), setSettings: async (s: SettingsInfo['settings']) => ({ ...info(s.roots[0]?.path ?? 'C:/lib'), settings: s }),
    update: async (id: string, _: AlbumPatch) => album(id, 3),
  };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: Gateway, useValue: gw }, { provide: Toasts, useValue: { show() {}, error() {} } }] });
  const store = TestBed.inject(AlbumStore);
  TestBed.tick(); // the query effect's first run
  return { store, events };
};

describe('AlbumStore', () => {
  it('a query change clears the selection (its albums leave the grid); a refresh keeps it', () => {
    const { store } = setup();
    store.albums.set([album('a'), album('b')]);
    store.selection.set(['a']);
    store.refresh(0);
    TestBed.tick();
    expect(store.selection.size()).toBe(1);
    store.setFilter('kind', 'ZIP');
    TestBed.tick();
    expect(store.selection.size()).toBe(0);
  });

  it('an album shown with a higher revision is never replaced by an older one, from an event or a patch reply', async () => {
    const { store, events } = setup();
    store.albums.set([album('a', 5)]);
    events.next({ type: 'albumChanged', album: album('a', 4, { rating: 4 }) });
    expect(store.byId().get('a')?.revision).toBe(5);
    events.next({ type: 'albumChanged', album: album('a', 6, { rating: 6 }) });
    expect(store.byId().get('a')).toMatchObject({ revision: 6, rating: 6 });
    expect((await store.patch('a', { rating: 1 }))?.revision).toBe(3);
    expect(store.byId().get('a')).toMatchObject({ revision: 6, rating: 6 });
  });

  it('another library drops the entity scope (its ids belong to the old one)', async () => {
    const { store } = setup();
    await store.loadSettings();
    store.setFilter('entities', [7]); store.setFilter('missing', 'MODEL');
    await store.saveSettings({ ...DEFAULT_SETTINGS, roots: [{ path: 'C:/lib', exclusions: [] }] });
    expect(store.filters().entities).toEqual([7]);
    await store.saveSettings({ ...DEFAULT_SETTINGS, roots: [{ path: 'E:/other', exclusions: [] }] });
    expect(store.filters().entities).toBeUndefined();
    expect(store.filters().missing).toBeUndefined();
  });
});
