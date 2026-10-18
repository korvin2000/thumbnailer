import { describe, expect, it } from 'vitest';
import { norm, tag } from './mock-catalog';
import { MockGateway } from './mock-gateway';
import { AlbumQuery, EntityKind, GatewayEvent } from './model';

const setup = () => {
  const gw = new MockGateway(), events: GatewayEvent[] = [];
  gw.events.subscribe(e => events.push(e));
  const changed = () => events.filter(e => e.type === 'catalogChanged').length;
  const named = async (kind: EntityKind, name: string) => (await gw.entities(kind, { text: name, limit: 500 })).items.find(e => e.name === name)!;
  const first = async (limit: number) => (await gw.search({ sort: 'NAME', offset: 0, limit } as AlbumQuery)).albums;
  return { gw, changed, named, first };
};

describe('mock catalog', () => {
  it('normalizes tags as AlbumMeta.tag and names as Catalog.norm', () => {
    expect(tag('  #Summer  Night ')).toBe('summer-night');
    expect(norm('TAG', '##B&W')).toBe('b&w');
    expect(norm('MODEL', ' #Anna   Lee ')).toBe('#anna lee');
    expect(norm('MODEL', 'x'.repeat(300))).toHaveLength(256);
  });

  it('saveEntity keeps aliases and links when absent or null, replaces them when given, and drops a null profile', async () => {
    const { gw } = setup();
    const studio = (await gw.saveEntity({ kind: 'STUDIO', name: 'North Light', rating: 0, profile: null })).entity;
    const a = await gw.saveEntity({ kind: 'MODEL', name: 'Zora', rating: 4, profile: '{"h":170}', aliases: ['Zorka'], links: [{ to: studio.id, alias: 'Z' }] });
    expect(a.entity.profile).toBe('{"h":170}');
    const kept = await gw.saveEntity({ id: a.entity.id, kind: 'MODEL', name: 'Zora', rating: 5, profile: null });
    expect(kept.aliases).toEqual(['zorka']);
    expect(kept.links.map(l => [l.to.id, l.alias])).toEqual([[studio.id, 'Z']]);
    expect('profile' in kept.entity).toBe(false);
    const nulls = await gw.saveEntity({ id: a.entity.id, kind: 'MODEL', name: 'Zora', rating: 5, profile: null, aliases: null, links: null });
    expect(nulls.aliases).toEqual(['zorka']);
    expect(nulls.links).toHaveLength(1);
    const replaced = await gw.saveEntity({ id: a.entity.id, kind: 'MODEL', name: 'Zora', rating: 5, profile: null, aliases: [], links: [] });
    expect(replaced.aliases).toEqual([]);
    expect(replaced.links).toEqual([]);
  });

  it('a rename keeps the old name as an alias, also when aliases are kept', async () => {
    const { gw } = setup();
    const a = await gw.saveEntity({ kind: 'MODEL', name: 'Ira', rating: 0, profile: null, aliases: ['Irina'] });
    const b = await gw.saveEntity({ id: a.entity.id, kind: 'MODEL', name: 'Irene', rating: 0, profile: null });
    expect(b.entity.name).toBe('Irene');
    expect(b.aliases.sort()).toEqual(['ira', 'irina']);
  });

  it('saveEntity refuses with the server texts', async () => {
    const { gw, named } = setup();
    const nika = await named('MODEL', 'Nika'), studio = await named('STUDIO', (await gw.entities('STUDIO')).items[0].name);
    const save = (e: object) => gw.saveEntity({ kind: 'MODEL', name: 'Neu', rating: 0, profile: null, ...e });
    await expect(save({ name: '  ' })).rejects.toThrow('A name is required');
    await expect(save({ rating: 11 })).rejects.toThrow('Rating must be 0..10');
    await expect(save({ rating: -1 })).rejects.toThrow('Rating must be 0..10');
    await expect(save({ name: ' nikka ' })).rejects.toThrow(/^"nikka" is Nika; merge instead\?$/);
    await expect(save({ aliases: ['NIKA'] })).rejects.toThrow(/^"NIKA" is Nika; merge instead\?$/);
    await expect(save({ id: nika.id, name: 'Nika', links: [{ to: nika.id }] })).rejects.toThrow(`Cannot link to ${nika.id}`);
    await expect(save({ links: [{ to: 99999 }] })).rejects.toThrow('Cannot link to 99999');
    await expect(save({ id: studio.id, name: studio.name })).rejects.toThrow('The kind of an entity cannot change');
    await expect(save({ id: 99999 })).rejects.toThrow('No entity 99999');
  });

  it('saveEntity always announces catalogChanged and stores a tag name normalized', async () => {
    const { gw, changed } = setup();
    const t = await gw.saveEntity({ kind: 'TAG', name: '#Night  Shoot', rating: 0, profile: null });
    expect(t.entity.name).toBe('night-shoot');
    const before = changed();
    await gw.saveEntity({ id: t.entity.id, kind: 'TAG', name: 'night-shoot', rating: 0, profile: null });
    expect(changed()).toBe(before + 1);
  });

  it('entities caps the page at 500', async () => {
    const { gw } = setup();
    for (let i = 0; i < 520; i++) (gw as any).catalog.create('TAG', `bulk-${i}`, false);
    const page = await gw.entities('TAG', { text: 'bulk-', limit: 10_000 });
    expect(page.items).toHaveLength(500);
    expect(page.total).toBe(520);
  });

  it('markPhoto refuses unknown entries, non-list ids and bad ratings', async () => {
    const { gw, named, first } = setup();
    const id = (await first(1))[0].id;
    const model = await named('MODEL', 'Nika');
    await expect(gw.markPhoto(id, 'nope.jpg', { rating: 3 })).rejects.toThrow('No entry nope.jpg');
    await expect(gw.markPhoto(id, '001.jpg', { lists: [model.id] })).rejects.toThrow(`No favorites list ${model.id}`);
    await expect(gw.markPhoto(id, '001.jpg', { rating: 11 })).rejects.toThrow('Rating must be 0..10');
    await expect(gw.markPhoto('nope', '001.jpg', { rating: 1 })).rejects.toThrow('No album nope');
  });

  it('merging lists moves their favorites; a deleted list leaves none behind', async () => {
    const { gw, named } = setup();
    const english = await named('LIST', 'English models'), best = await named('LIST', 'Best of 2024');
    const n = (await gw.photos(english.id, 0, 1000)).total + (await gw.photos(best.id, 0, 1000)).total;
    expect(n).toBeGreaterThan(0);
    await gw.mergeEntities([english.id], best.id);
    expect((await gw.photos(best.id, 0, 1000)).total).toBe(n);
    await expect(gw.photos(english.id, 0, 10)).rejects.toThrow(`No favorites list ${english.id}`);
    const marked = (await gw.photos(best.id, 0, 1)).items[0].albumId;
    await gw.deleteEntities([best.id]);
    await expect(gw.photos(best.id, 0, 10)).rejects.toThrow(`No favorites list ${best.id}`);
    const marks = (await gw.album(marked)).marks;
    expect(Object.values(marks).flatMap(m => m.lists)).toEqual([]);
    expect(Object.values(marks).every(m => m.rating > 0)).toBe(true);
  });

  it('under another entity, items are those sharing albums with it and their count is the shared one (B1)', async () => {
    const { gw } = setup();
    const studio = (await gw.entities('STUDIO', { byCount: true, limit: 1 })).items[0];
    const under = await gw.entities('MODEL', { under: studio.id, byCount: true, limit: 500 });
    expect(under.total).toBe(under.items.length);
    expect(under.items.length).toBeGreaterThan(0);
    for (const m of under.items) {
      const shared = (await gw.search({ entities: [studio.id, m.id], sort: 'NAME', offset: 0, limit: 500 } as AlbumQuery)).total;
      expect(m.albums).toBe(shared);
      expect(shared).toBeGreaterThan(0);
    }
    expect([...under.items].sort((x, y) => y.albums - x.albums || x.name.localeCompare(y.name))).toEqual(under.items);
    const all = await gw.entities('MODEL', { limit: 500 });
    expect(all.items.find(e => e.id === under.items[0].id)!.albums).toBeGreaterThanOrEqual(under.items[0].albums);
  });

  it('cards carry the derived sections of their kind with shared counts, most first (B1)', async () => {
    const { gw, named } = setup();
    const model = await gw.entity((await named('MODEL', 'Sofia')).id);
    expect(Object.keys(model.derived!).sort()).toEqual(['albums', 'genres', 'photographers', 'studios', 'tags']);
    expect(model.derived!.studios!.map(s => s.albums)).toEqual([...model.derived!.studios!.map(s => s.albums)].sort((a, b) => b - a));
    const studio = await gw.entity((await gw.entities('STUDIO', { limit: 1 })).items[0].id);
    expect(Object.keys(studio.derived!).sort()).toEqual(['albums', 'genres', 'models', 'photographers']);
    const genre = await gw.entity((await gw.entities('GENRE', { limit: 1 })).items[0].id);
    expect(Object.keys(genre.derived!).sort()).toEqual(['albums', 'models', 'studios']);
    const list = await gw.entity((await named('LIST', 'Best of 2024')).id);
    expect(Object.keys(list.derived!)).toEqual(['albums']);
  });

  it('name filters resolve through aliases, an unknown name matches nothing, facets count the links (B1)', async () => {
    const { gw } = setup();
    const q = (f: object) => gw.search({ sort: 'NAME', offset: 0, limit: 1, ...f } as AlbumQuery).then(r => r.total);
    const byName = await q({ model: 'Nika' }), byAlias = await q({ model: 'nikka' });
    expect(byName).toBeGreaterThan(0);
    expect(byAlias).toBe(byName);
    expect(await q({ model: 'Nik' })).toBe(0);
    expect(await q({ model: 'Nobody' })).toBe(0);
    const facets = await gw.facets();
    expect(facets.models.find(f => f.value === 'Nika')?.count).toBe(byName);
    expect(facets.genres.length).toBeGreaterThan(0);
  });

  it('merging fills the survivor\'s empty profile fields; compareCopies is busy while an operation runs', async () => {
    const { gw, first } = setup();
    const a = (await gw.saveEntity({ kind: 'MODEL', name: 'Mer-A', rating: 0, profile: '{"eyes":"green","hair":"red"}' })).entity;
    const b = (await gw.saveEntity({ kind: 'MODEL', name: 'Mer-B', rating: 0, profile: '{"eyes":"blue","hair":""}' })).entity;
    expect(JSON.parse((await gw.mergeEntities([a.id], b.id)).entity.profile!)).toEqual({ eyes: 'blue', hair: 'red' });
    await expect(gw.mergeEntities([b.id], b.id)).rejects.toThrow('Nothing to merge');
    const [x, y] = await first(2);
    await gw.runOp({ kind: 'PACK', albumIds: [x.id], format: 'AUTO', conflict: 'RENAME' });
    await expect(gw.compareCopies(x.id, y.id)).rejects.toThrow('busy');
    await new Promise(r => setTimeout(r, 400));
    await expect(gw.compareCopies(x.id, y.id)).resolves.toBeTruthy();
  });

  it('link and unlink count only albums actually changed and announce only then', async () => {
    const { gw, changed, named, first } = setup();
    const list = await gw.saveEntity({ kind: 'LIST', name: 'Fresh', rating: 0, profile: null });
    const id = (await first(1))[0].id;
    let before = changed();
    expect(await gw.link({ albumIds: [id, id] }, list.entity.id)).toBe(1);
    expect(changed()).toBe(before + 1);
    before = changed();
    expect(await gw.link({ albumIds: [id] }, list.entity.id)).toBe(0);
    expect(await gw.unlink({ albumIds: [id, id] }, list.entity.id)).toBe(1);
    expect(await gw.unlink({ albumIds: [id] }, list.entity.id)).toBe(0);
    expect(changed()).toBe(before + 1);
    const tagged = await named('TAG', (await gw.entities('TAG')).items[0].name);
    const three = await first(3), albums = three.map(a => a.id), linked = three.filter(a => a.links.some(l => l.entity === tagged.id)).length;
    expect(await gw.link({ albumIds: [...albums, ...albums] }, tagged.id)).toBe(3 - linked);
    expect(await gw.unlink({ albumIds: albums }, tagged.id)).toBe(3);
  });
});
