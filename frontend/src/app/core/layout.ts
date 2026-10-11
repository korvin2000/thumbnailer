/**
 * Layout engine: turns tiles with aspect ratios into absolute placements.
 *
 * Both modes keep portrait and landscape tiles the same size (a portrait tile is the landscape tile rotated, so
 * both have the same area); empty space is preferred over small thumbnails.
 *
 * `bricks` — covers are exactly 16:9 or 9:16. Landscape tiles fill rows of `columns`; portrait tiles are collected
 *            into their own rows that hold as many tiles as fit at that size (scaled by at most 15% to close the row),
 *            so the two row kinds interlock without cropping. Order shifts by at most one row of each kind.
 * `grid`   — strict order: rows of `columns` square cells; every tile is fitted inside its cell with its own aspect
 *            ratio and centred, and a row is only as tall as its tallest tile.
 */
export interface Tile<T = unknown> { id: string; aspect: number; data: T }
export interface Placement<T = unknown> { tile: Tile<T>; x: number; y: number; w: number; h: number; row: number }
export interface Layout<T = unknown> { placements: Placement<T>[]; height: number }

export type LayoutMode = 'bricks' | 'grid';
export const LAYOUT_MODES: LayoutMode[] = ['bricks', 'grid'];

export interface LayoutOptions { width: number; columns: number; gap: number; mode: LayoutMode }

interface Item<T> { tile: Tile<T>; x: number; dy: number; w: number; h: number }
interface Row<T> { items: Item<T>[]; height: number }

const MAX_SCALE = 0.15;

export function layout<T>(tiles: Tile<T>[], o: LayoutOptions): Layout<T> {
  const width = Math.max(1, o.width), columns = Math.max(1, Math.floor(o.columns));
  const colW = (width - o.gap * (columns - 1)) / columns;
  const rows = o.mode === 'bricks' ? bricks(tiles, width, columns, o.gap, colW) : grid(tiles, columns, o.gap, colW);
  return place(rows, o.gap);
}

function bricks<T>(tiles: Tile<T>[], width: number, columns: number, gap: number, colW: number): Row<T>[] {
  const landH = colW * 9 / 16;
  const fit = fitCount(width, gap, landH);
  const kinds = {
    landscape: { tiles: [] as Tile<T>[], size: columns, first: Infinity, w: colW, h: landH },
    portrait: { tiles: [] as Tile<T>[], size: fit.count, first: Infinity, w: landH * fit.scale, h: colW * fit.scale },
  };
  const rows: Row<T>[] = [];
  const flush = (k: typeof kinds.landscape) => {
    if (k.tiles.length) rows.push({ items: k.tiles.map((tile, i) => ({ tile, x: i * (k.w + gap), dy: 0, w: k.w, h: k.h })), height: k.h });
    k.tiles = []; k.first = Infinity;
  };
  tiles.forEach((t, i) => {
    const k = t.aspect < 1 ? kinds.portrait : kinds.landscape;
    k.first = Math.min(k.first, i);
    k.tiles.push(t);
    if (k.tiles.length === k.size) flush(k);
  });
  for (const k of [kinds.landscape, kinds.portrait].sort((a, b) => a.first - b.first)) flush(k);
  return rows;
}

/** How many tiles of width `w` make a row: the count whose scale factor is closest to 1, if within ±15%; else natural size. */
function fitCount(width: number, gap: number, w: number): { count: number; scale: number } {
  const lo = Math.max(1, Math.floor((width + gap) / (w + gap)));
  const scaleFor = (n: number) => (width - gap * (n - 1)) / (n * w);
  const best = [lo, lo + 1].map(count => ({ count, scale: scaleFor(count) })).sort((a, b) => Math.abs(a.scale - 1) - Math.abs(b.scale - 1))[0];
  return Math.abs(best.scale - 1) <= MAX_SCALE ? best : { count: lo, scale: 1 };
}

function grid<T>(tiles: Tile<T>[], columns: number, gap: number, colW: number): Row<T>[] {
  const rows: Row<T>[] = [];
  for (let i = 0; i < tiles.length; i += columns) {
    const items = tiles.slice(i, i + columns).map((tile, j) => {
      const w = tile.aspect >= 1 ? colW : colW * tile.aspect, h = tile.aspect >= 1 ? colW / tile.aspect : colW;
      return { tile, x: j * (colW + gap) + (colW - w) / 2, dy: 0, w, h };
    });
    const height = Math.max(...items.map(it => it.h));
    items.forEach(it => it.dy = (height - it.h) / 2);
    rows.push({ items, height });
  }
  return rows;
}

function place<T>(rows: Row<T>[], gap: number): Layout<T> {
  const placements: Placement<T>[] = [];
  let y = 0;
  rows.forEach((r, row) => {
    for (const it of r.items) placements.push({ tile: it.tile, x: it.x, y: y + it.dy, w: it.w, h: it.h, row });
    y += r.height + gap;
  });
  return { placements, height: Math.max(0, y - gap) };
}

/** Columns that fit the viewport: the configured count applies from 1440px, fewer below, so tiles never get tiny. */
export function responsiveColumns(configured: number, width: number): number {
  return Math.max(1, Math.min(configured, Math.round(configured * width / 1440)));
}
