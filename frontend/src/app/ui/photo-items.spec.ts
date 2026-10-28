import { describe, expect, it } from 'vitest';
import { PhotoListItem } from '../core/model';
import { pagesFor, photoKey, rated, without } from './photo-items';

const item = (albumId: string, name: string, rating = 0): PhotoListItem => ({ albumId, name, index: 1, rating, album: { id: albumId, name: albumId, revision: 1 } });

describe('photo list items', () => {
  it('a mutation finds its photo by key after a refresh replaced the objects', () => {
    const before = item('a', '1.jpg'), refreshed = [item('a', '1.jpg'), item('b', '1.jpg')];
    expect(rated(refreshed, photoKey(before), 7).map(p => p.rating)).toEqual([7, 0]);
    expect(without(refreshed, photoKey(before)).map(photoKey)).toEqual(['b/1.jpg']);
  });

  it('a photo no longer listed leaves the array as it is, so no total is decremented for it', () => {
    const items = [item('a', '1.jpg')];
    expect(rated(items, 'a/2.jpg', 3)).toBe(items);
    expect(without(items, 'a/2.jpg')).toBe(items);
    expect(without(items, 'a/1.jpg')).not.toBe(items);
  });

  it('a refresh re-asks every page the user scrolled to, at least the first', () => {
    expect(pagesFor(0, 200)).toEqual([0]);
    expect(pagesFor(200, 200)).toEqual([0]);
    expect(pagesFor(201, 200)).toEqual([0, 200]);
    expect(pagesFor(1200, 200)).toEqual([0, 200, 400, 600, 800, 1000]);
  });
});
