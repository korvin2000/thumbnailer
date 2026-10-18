import { Album, Entry, ENTRY_META, MetaRow, PhotoList, PhotoMarks } from './model';
import { delay, hash, rng } from './mock-gateway';

const BIO = 'Born in 1998, modelling since 2017. Height 172 cm, brown hair, green eyes. Works mostly in studio, portrait and fashion sessions; available for travel.';

/** Per-photo marks (rating, favorites lists), the photos of a list across albums, fake EXIF and the albums' text files. */
export class MockPhotos {
  /** Album id → its sparse marks. */
  private readonly marks = new Map<string, PhotoMarks>();

  /** Seeds marks on every 9th album: one favorite per list, one plain rating; `isList` = the id is a favorites list. */
  constructor(private readonly albums: Map<string, Album>, private readonly entries: (a: Album) => Entry[], private readonly isList: (id: number) => boolean, lists: number[]) {
    [...albums.keys()].sort().filter((_, i) => i % 9 === 0)
      .forEach((id, i) => this.marks.set(id, { '002.jpg': { rating: 8, lists: [lists[i % lists.length]] }, '005.jpg': { rating: 6, lists: [] } }));
  }

  marksOf(albumId: string): PhotoMarks { return { ...this.marks.get(albumId) }; }

  /** Photos of a list in visible albums. */
  count(listId: number): number {
    let n = 0;
    for (const [id, m] of this.marks) if (this.albums.get(id)?.hidden === false) n += Object.values(m).filter(x => x.lists.includes(listId)).length;
    return n;
  }

  /** A mark with no rating and no list is dropped (marks are sparse); the server's refusals and texts. */
  async markPhoto(albumId: string, name: string, mark: { rating?: number; lists?: number[] }): Promise<PhotoMarks> {
    await delay(40);
    const a = this.albums.get(albumId);
    if (!a) throw new Error(`No album ${albumId}`);
    if (!this.entries(a).some(e => e.name === name)) throw new Error(`No entry ${name}`);
    if (mark.rating != null && (mark.rating < 0 || mark.rating > 10)) throw new Error('Rating must be 0..10');
    const bad = mark.lists?.find(id => !this.isList(id));
    if (bad != null) throw new Error(`No favorites list ${bad}`);
    const m = { ...this.marks.get(albumId) }, cur = m[name] ?? { rating: 0, lists: [] };
    const next = { rating: mark.rating ?? cur.rating, lists: [...new Set(mark.lists ?? cur.lists)] };
    if (next.rating || next.lists.length) m[name] = next; else delete m[name];
    this.marks.set(albumId, m);
    return { ...m };
  }

  /** A merged list's favorites move to the surviving one. */
  remapList(from: number, into: number) { this.edit(l => l.map(id => id === from ? into : id)); }

  /** A deleted list leaves no favorite behind (a mark left with neither rating nor list goes). */
  removeList(id: number) { this.edit(l => l.filter(x => x !== id)); }

  async photos(listId: number, offset: number, limit: number): Promise<PhotoList> {
    await delay(60);
    if (!this.isList(listId)) throw new Error(`No favorites list ${listId}`);
    const items = [...this.marks].flatMap(([albumId, m]) => {
      const a = this.albums.get(albumId);
      if (!a || a.hidden) return [];
      const entries = this.entries(a);
      return Object.entries(m).filter(([, x]) => x.lists.includes(listId))
        .map(([name, x]) => ({ albumId, name, index: entries.findIndex(e => e.name === name), rating: x.rating, album: { id: a.id, name: a.name, revision: a.revision } }));
    }).sort((x, y) => x.album.name.localeCompare(y.album.name) || x.index - y.index);
    return { items: items.slice(offset, offset + limit), total: items.length };
  }

  /** A fake EXIF table; none for an entry known to carry no metadata. */
  async photoMeta(albumId: string, index: number): Promise<MetaRow[]> {
    await delay(90);
    const a = this.albums.get(albumId), e = a && this.entries(a)[index];
    if (!e) throw new Error('Photo not found');
    if (!(e.flags & ENTRY_META)) return [];
    const r = rng(hash(albumId) + index), pick = (xs: string[]) => xs[Math.floor(r() * xs.length)];
    const ifd0 = 'Exif IFD0', sub = 'Exif SubIFD';
    return [
      { dir: ifd0, tag: 'Make', value: pick(['Canon', 'Nikon', 'Sony', 'FUJIFILM']) },
      { dir: ifd0, tag: 'Model', value: pick(['EOS 5D Mark IV', 'D850', 'ILCE-7RM4', 'X-T4']) },
      { dir: sub, tag: 'DateTimeOriginal', value: (e.shotAt ?? '').replace(/-/g, ':').replace('T', ' ') },
      { dir: sub, tag: 'ExposureTime', value: pick(['1/125 sec', '1/160 sec', '1/200 sec', '1/250 sec']) },
      { dir: sub, tag: 'FNumber', value: pick(['f/2.8', 'f/5.6', 'f/8.0', 'f/11.0']) },
      { dir: sub, tag: 'ISO', value: pick(['100', '200', '400']) },
    ];
  }

  async albumText(albumId: string, name: string): Promise<string> {
    await delay(60);
    const a = this.albums.get(albumId);
    if (!a || a.bio !== name) throw new Error(`${name}: not found`);
    return `${a.model ?? 'The model'}. ${BIO}`;
  }

  private edit(lists: (l: number[]) => number[]) {
    for (const [albumId, m] of this.marks) {
      const next: PhotoMarks = {};
      for (const [name, x] of Object.entries(m)) { const l = [...new Set(lists(x.lists))]; if (x.rating || l.length) next[name] = { rating: x.rating, lists: l }; }
      this.marks.set(albumId, next);
    }
  }
}
