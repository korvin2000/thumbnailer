import { PhotoListItem } from '../core/model';

/** The stable identity of a listed photo: a refresh replaces the item objects, so a mutation's reply is applied by this key, never by `===`. */
export const photoKey = (p: Pick<PhotoListItem, 'albumId' | 'name'>) => `${p.albumId}/${p.name}`;

/** `items` with the photo `key` rated `rating` (the same array when it is not listed any more). */
export function rated(items: PhotoListItem[], key: string, rating: number): PhotoListItem[] {
  return items.some(x => photoKey(x) === key) ? items.map(x => photoKey(x) === key ? { ...x, rating } : x) : items;
}

/** `items` without the photo `key`; the same array when it was not there, so the caller knows whether a total shrank. */
export function without(items: PhotoListItem[], key: string): PhotoListItem[] {
  const rest = items.filter(x => photoKey(x) !== key);
  return rest.length === items.length ? items : rest;
}

/** The page offsets that cover `loaded` items again (at least the first page), for a refresh that keeps what the user scrolled to. */
export const pagesFor = (loaded: number, page: number) => Array.from({ length: Math.max(1, Math.ceil(loaded / page)) }, (_, i) => i * page);
