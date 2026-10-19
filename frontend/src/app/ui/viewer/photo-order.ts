import { Entry, PhotoMarks } from '../../core/model';
import { ViewerScale, ViewerSort } from '../../core/prefs';

/** Pure viewer logic: entry order (sort fields, seeded shuffle), the size filter, lightbox scale geometry. Angular-free, spec-covered. */

export const SORT_LABELS: Record<ViewerSort, string> = { name: 'Name', size: 'Size', date: 'Date', dimensions: 'Dimensions', rating: 'Rating', random: 'Random' };
/** Direction a field starts in when picked: names and dates read forward, the rest biggest first. */
export const SORT_DESC_FIRST: Record<ViewerSort, boolean> = { name: false, size: true, date: false, dimensions: true, rating: true, random: false };
export const SCALE_LABELS: Record<ViewerScale, string> = { fit: 'Fit', fitLarge: 'Fit large only', actual: '1 : 1', width: 'Fit width', height: 'Fit height' };
export const SCALE_CYCLE: ViewerScale[] = ['fit', 'fitLarge', 'actual', 'width', 'height'];
export const SIDE_PRESETS = [0, 800, 1200, 1800];
export const BYTE_PRESETS = [0, 200 * 1024, 500 * 1024, 1024 * 1024, 2 * 1024 * 1024, 5 * 1024 * 1024];

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** mulberry32: a small seeded PRNG so a shuffle is reproducible until the user reshuffles. */
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

/** Fisher–Yates with a seeded source; the same seed gives the same order. */
export function shuffle<T>(items: T[], seed: number): T[] {
  const out = [...items], next = rng(seed);
  for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(next() * (i + 1)); [out[i], out[j]] = [out[j], out[i]]; }
  return out;
}

/** Comparator of a sort field in a direction; ties fall back to the entry index (always ascending) so the order is total. Missing dates sort last either way. */
export function comparator(sort: Exclude<ViewerSort, 'random'>, desc: boolean, marks: PhotoMarks): (a: Entry, b: Entry) => number {
  const by: (e: Entry) => number | string | null = {
    name: (e: Entry) => e.name, size: (e: Entry) => e.bytes, date: (e: Entry) => e.shotAt, dimensions: (e: Entry) => e.width * e.height, rating: (e: Entry) => marks[e.name]?.rating ?? 0,
  }[sort];
  return (a, b) => {
    const x = by(a), y = by(b);
    if (x === null || y === null) return x === y ? a.index - b.index : x === null ? 1 : -1;
    const c = typeof x === 'string' ? collator.compare(x, y as string) : x - (y as number);
    return (desc ? -c : c) || a.index - b.index;
  };
}

/** Entries in viewer order: a sort field in its direction, or a seeded shuffle (reversed when `desc`). */
export function order(entries: Entry[], sort: ViewerSort, desc: boolean, marks: PhotoMarks, seed: number): Entry[] {
  if (sort === 'random') { const s = shuffle(entries, seed); return desc ? s.reverse() : s; }
  return [...entries].sort(comparator(sort, desc, marks));
}

/** The size filter: longer side ≥ `minSide` px and ≥ `minBytes`; an entry without dimensions passes the side test. */
export function filter(entries: Entry[], minSide: number, minBytes: number): Entry[] {
  return !minSide && !minBytes ? entries : entries.filter(e => (!minSide || !e.width || Math.max(e.width, e.height) >= minSide) && e.bytes >= minBytes);
}

export function nextScale(s: ViewerScale): ViewerScale { return SCALE_CYCLE[(SCALE_CYCLE.indexOf(s) + 1) % SCALE_CYCLE.length]; }

export interface Size { width: number; height: number }

/**
 * Rendered size of a `natural` image in a `viewport` per scale mode: fit fills (upscaling), fitLarge only shrinks, actual is 1:1, width/height fill one axis.
 * `turns` = quarter turns of a view-only rotation: an odd count swaps the sides, so the box is the one the rotated photo shows in.
 */
export function scaleBox(mode: ViewerScale, natural: Size, viewport: Size, turns = 0): Size {
  const odd = turns % 2 !== 0, nw = (odd ? natural.height : natural.width) || 1, nh = (odd ? natural.width : natural.height) || 1, fit = Math.min(viewport.width / nw, viewport.height / nh);
  const s = { fit, fitLarge: Math.min(1, fit), actual: 1, width: viewport.width / nw, height: viewport.height / nh }[mode];
  return { width: Math.round(nw * s), height: Math.round(nh * s) };
}
