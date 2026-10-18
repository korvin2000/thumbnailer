import { describe, expect, it } from 'vitest';
import { Album, Entity } from '../core/model';
import type { EntityTreeContext } from './entity-tree';
import { AlbumActions, albumMenu, albumsSubmenu, allHidden, assignItems, canvasMenu, EntityActions, entityMenu, favoritesItems, FolderActions, folderMenu, opsItems, Target } from './menus';
import type { MenuEntry, MenuItem } from './shared/context-menu';

const album = (id: string, o: Partial<Album> = {}): Album => ({ id, root: 'D:/P', path: `D:/P/${id}`, kind: 'DIRECTORY', name: id, cover: '1.jpg', coverPinned: false, coverPortrait: false, revision: 1, imageCount: 3, bytes: 1, avgPixels: 1, maxWidth: 1, maxHeight: 1, shotDate: null, modifiedAt: '', indexedAt: '', model: null, studio: null, photographer: null, description: null, tags: [], rating: 0, hidden: false, bio: null, stamp: '0', genres: [], links: [], copies: 1, ...o });
const entity = (id: number, name: string, kind: Entity['kind'] = 'MODEL', auto = false): Entity => ({ id, kind, name, rating: 0, auto, albums: 2 });
const sel = (...albums: Album[]): Target => ({ albumIds: albums.map(a => a.id), albums, complete: true, label: `${albums.length} albums` });
const query: Target = { query: { roots: ['D:/P'] }, label: 'albums below P', albums: [], complete: false };

/** Every callback records its name and arguments (`lists` and `packFormat` answer as the app's state would). */
const spy = () => {
  const calls: unknown[][] = [];
  const a = new Proxy({}, { get: (_, name) => name === 'lists' ? async () => [entity(9, 'Best', 'LIST')] : name === 'packFormat' ? () => 'SEVEN_ZIP' : (...args: unknown[]) => { calls.push([name, ...args]); } }) as AlbumActions & FolderActions & EntityActions;
  return { a, calls };
};
const items = (m: MenuEntry[]) => m.filter((e): e is MenuItem => !('sep' in e));
const labels = (m: MenuEntry[]) => m.map(e => 'sep' in e ? '—' : e.label);
const item = (m: MenuEntry[], label: string) => { const it = items(m).find(e => e.label === label || e.label.startsWith(label + ' (')); if (!it) throw new Error(`no item ${label} in ${labels(m)}`); return it; };
const kids = (it: MenuItem) => it.children as MenuEntry[];

describe('album menu (§7.4)', () => {
  it('has the plan order, a header for several albums, and runs the actions on the target', () => {
    const { a, calls } = spy(), t = sel(album('a'), album('b'));
    const m = albumMenu(t, a, t.albums[0]);
    expect(labels(m).map(l => l.replace(/ \(\d.*\)$/, ''))).toEqual(['2 albums selected', '—', 'Open', 'Details', 'Show in folder', 'Copy path', '—', 'Assign', 'Favorites', '—', 'Pack', 'Unpack', 'Repack', 'Encrypt (.cry)', 'Decrypt', '—',
      'Move to…', 'Copy to…', 'Hide', 'Delete…', '—', 'Select all', 'Select first N…', 'Deselect']);
    expect(item(m, 'Delete…').danger).toBe(true);
    item(m, 'Open').run!(); item(m, 'Delete…').run!(); item(m, 'Hide').run!();
    expect(calls).toEqual([['open', 'a'], ['delete', t], ['hide', t, true]]);
    expect(labels(albumMenu(sel(album('a')), a, null)).slice(0, 2)).toEqual(['Open', 'Details']);
    expect(item(albumMenu(sel(album('a')), a, null), 'Open').disabled).toBe(true);
  });

  it('counts the albums an operation skips and disables it when every one is skipped', () => {
    const { a } = spy(), mixed = sel(album('a'), album('b', { kind: 'ZIP' }), album('c', { kind: 'ZIP' }));
    expect(item(opsItems(mixed, a), 'Pack').label).toBe('Pack (2 of 3 already packed)');
    expect(item(opsItems(mixed, a), 'Unpack').label).toBe('Unpack (1 of 3 already unpacked)');
    expect(item(opsItems(mixed, a), 'Repack').label).toBe('Repack (1 of 3 not packed)');
    const zips = sel(album('b', { kind: 'ZIP' }));
    expect(item(opsItems(zips, a), 'Pack')).toMatchObject({ label: 'Pack (1 of 1 already packed)', disabled: true });
    expect(item(opsItems(zips, a), 'Unpack').disabled).toBeUndefined();
    expect(item(opsItems(query, a), 'Pack').label).toBe('Pack'); // a query target: nothing known
    expect(labels(kids(item(opsItems(zips, a), 'Pack')))).toEqual(['Default (7z)', '—', 'zip (auto)', 'zip', '7z']);
  });

  it('Pack offers the saved default format first (no explicit format: the action reads the setting) and every format as an override', () => {
    const { a, calls } = spy(), t = sel(album('a'));
    const pack = kids(item(opsItems(t, a), 'Pack'));
    (pack[0] as MenuItem).run!(); (pack[4] as MenuItem).run!();
    expect(calls).toEqual([['pack', t], ['pack', t, 'SEVEN_ZIP']]);
  });

  it('offers Unhide when every known album is hidden, also for the Hidden view query', () => {
    const { a, calls } = spy(), hidden = sel(album('a', { hidden: true }), album('b', { hidden: true }));
    expect(allHidden(hidden)).toBe(true);
    expect(allHidden(sel(album('a', { hidden: true }), album('b')))).toBe(false);
    expect(allHidden({ ...query, query: { hidden: true } })).toBe(true);
    item(albumMenu(hidden, a, null), 'Unhide').run!();
    expect(calls).toEqual([['hide', hidden, false]]);
  });

  it('Assign lists the pickers, the known tags to remove, the shared rating checked, and Favorites checks the lists every album is in', async () => {
    const { a, calls } = spy();
    const t = sel(album('a', { rating: 7, links: [{ entity: 5, role: 'TAG', name: 'summer' }, { entity: 9, role: 'LIST', name: 'Best' }] }), album('b', { rating: 7, links: [{ entity: 9, role: 'LIST', name: 'Best' }] }));
    const assign = assignItems(t, a);
    expect(labels(assign)).toEqual(['Model…', 'Studio…', 'Photographer…', 'Genre…', 'Add tag…', 'Remove tag', 'Rating', 'Shot date…']);
    expect(labels(kids(item(assign, 'Remove tag')))).toEqual(['#summer', '—', 'Other…']);
    expect(items(kids(item(assign, 'Rating'))).filter(e => e.checked).map(e => e.label)).toEqual(['7']);
    (kids(item(assign, 'Remove tag'))[0] as MenuItem).run!(); item(assign, 'Model…').run!();
    expect(calls).toEqual([['removeTag', t, 5], ['assign', t, 'MODEL']]);
    const fav = favoritesItems(t, await a.lists(), a);
    expect(items(fav).map(e => [e.label, e.checked])).toEqual([['Best', true], ['New list…', undefined]]);
    (fav[0] as MenuItem).run!();
    expect(calls.at(-1)).toEqual(['favorite', t, 9, false]);
    expect(items(favoritesItems(query, await a.lists(), a))[0].checked).toBe(false); // unknown albums: never checked
    expect(items(kids(item(assignItems(query, a), 'Rating'))).some(e => e.checked)).toBe(false);
  });

  it('the canvas menu is the selection part; the node submenu has no Open, Delete or selection items', () => {
    const { a } = spy();
    expect(labels(canvasMenu(a))).toEqual(['Select all', 'Select first N…', 'Deselect']);
    expect(labels(albumsSubmenu(query, a))).toEqual(['Pack', 'Unpack', 'Repack', 'Encrypt (.cry)', 'Decrypt', '—', 'Move to…', 'Copy to…', 'Hide', '—', 'Assign', 'Favorites']);
  });
});

describe('folder menu (§7.2)', () => {
  it('offers the folder operations, Move and Delete disabled for a root, and the albums below', () => {
    const { a, calls } = spy();
    const m = folderMenu('D:/P/2019', false, query, a);
    expect(labels(m)).toEqual(['Rescan', 'Open in Explorer', 'Copy path', '—', 'Rename…', 'Move to…', 'Copy to…', 'Delete…', '—', 'Albums below']);
    item(m, 'Rename…').run!(); item(m, 'Delete…').run!(); item(m, 'Copy path').run!();
    expect(calls).toEqual([['renameFolder', 'D:/P/2019'], ['deleteFolder', 'D:/P/2019'], ['copyText', 'D:/P/2019']]);
    const root = folderMenu('D:/P', true, query, a);
    expect(item(root, 'Move to…').disabled).toBe(true);
    expect(item(root, 'Delete…').disabled).toBe(true);
    expect(item(root, 'Rename…').disabled).toBeUndefined();
    expect(labels(folderMenu(null, false, query, a))).toEqual(['Rescan all folders']);
  });
});

describe('entity menu (§7.2)', () => {
  const ctx = (entity: Entity | null, selected: number[] = [], kind = entity?.kind ?? 'MODEL', missing = false, scope: number[] | null = entity ? [entity.id] : null): EntityTreeContext => ({ kind, entity, missing, scope, selected, x: 0, y: 0 });

  it('acts on the row, or on the batch when the row is one of the selected rows', () => {
    const { a, calls } = spy(), nika = entity(1, 'Nika', 'MODEL', true);
    const one = entityMenu(ctx(nika), 0, query, a);
    expect(labels(one)).toEqual(['Open card', 'Show albums', '—', 'Rename…', 'Merge into…', 'Confirm', 'Credited as…', 'Delete model…', '—', 'Assign to selected albums', '—', 'Its albums']);
    expect(item(one, 'Assign to selected albums').disabled).toBe(true);
    item(one, 'Delete model…').run!(); item(one, 'Confirm').run!();
    expect(calls).toEqual([['deleteEntities', 'MODEL', [1]], ['confirmEntities', 'MODEL', [1]]]);
    const batch = entityMenu(ctx(nika, [1, 2, 3]), 4, query, a);
    expect(labels(batch).slice(0, 2)).toEqual(['3 models selected', '—']);
    expect(item(batch, 'Rename…').disabled).toBe(true);
    expect(item(batch, 'Credited as…').disabled).toBe(true);
    expect(item(batch, 'Delete 3 models…').danger).toBe(true);
    expect(item(batch, 'Assign to 4 selected albums').disabled).toBe(true);
    item(batch, 'Merge into…').run!();
    expect(calls.at(-1)).toEqual(['mergeInto', 'MODEL', [1, 2, 3]]);
    expect(labels(entityMenu(ctx(nika, [7, 8]), 4, query, a))[0]).toBe('Open card'); // the row is not in the selection: the row alone
  });

  it('a confirmed entity has no Confirm, a list has Show photos and no Credited as, All/Unassigned rows only scope and offer their albums', () => {
    const { a, calls } = spy();
    expect(item(entityMenu(ctx(entity(1, 'Nika')), 0, query, a), 'Confirm').disabled).toBe(true);
    const list = entityMenu(ctx(entity(9, 'Best', 'LIST'), [], 'LIST'), 2, query, a);
    expect(labels(list)).toContain('Show photos');
    expect(labels(list)).not.toContain('Credited as…');
    item(list, 'Assign to 2 selected albums').run!();
    expect(calls.at(-1)).toEqual(['assignSelection', expect.objectContaining({ id: 9 })]);
    const all = entityMenu(ctx(null, [], 'STUDIO'), 0, query, a);
    expect(labels(all)).toEqual(['Show albums', '—', 'Albums']);
    item(all, 'Show albums').run!();
    expect(calls.at(-1)).toEqual(['showAlbums', expect.objectContaining({ kind: 'STUDIO', missing: false })]);
  });

  it('a child row (a studio under a model) is worded and acted on as its own kind, within its parent scope', () => {
    const { a, calls } = spy(), studio = entity(5, 'EL-Studio', 'STUDIO');
    const m = entityMenu(ctx(studio, [5], 'STUDIO', false, [1, 5]), 0, query, a);
    expect(labels(m)).toContain('Delete studio…');
    expect(labels(m)).not.toContain('Credited as…');
    item(m, 'Merge into…').run!(); item(m, 'Show albums').run!();
    expect(calls).toEqual([['mergeInto', 'STUDIO', [5]], ['showAlbums', expect.objectContaining({ kind: 'STUDIO', scope: [1, 5] })]]);
  });
});
