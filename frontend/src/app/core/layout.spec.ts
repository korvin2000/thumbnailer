import { describe, expect, it } from 'vitest';
import { layout, Placement, responsiveColumns, Tile } from './layout';

const L = (id: string): Tile => ({ id, aspect: 16 / 9, data: null });
const P = (id: string): Tile => ({ id, aspect: 9 / 16, data: null });

function assertNoOverlap(ps: Placement[]) {
  for (const a of ps) for (const b of ps) {
    if (a === b) continue;
    const overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    expect(overlap, `${a.tile.id} overlaps ${b.tile.id}`).toBe(false);
  }
}

describe('bricks layout', () => {
  it('fills landscape rows of `columns` tiles with exact 16:9 tiles', () => {
    const { placements, height } = layout([L('a'), L('b'), L('c'), L('d'), L('e'), L('f')], { width: 1000, columns: 5, gap: 10, mode: 'bricks' });
    const w = (1000 - 40) / 5, h = w * 9 / 16;
    expect(placements).toHaveLength(6);
    placements.forEach(p => { expect(p.w).toBeCloseTo(w, 5); expect(p.h).toBeCloseTo(h, 5); });
    expect(placements[4].x + placements[4].w).toBeCloseTo(1000, 5);
    expect(placements[5].row).toBe(1);
    expect(height).toBeCloseTo(2 * h + 10, 5);
  });

  it('gives portrait tiles the same area as landscape ones, in their own rows, without gaps or overlaps', () => {
    const tiles = [L('1'), P('p1'), L('2'), P('p2'), L('3'), L('4'), P('p3'), P('p4'), P('p5'), P('p6'), P('p7'), L('5')];
    const { placements } = layout(tiles, { width: 1200, columns: 4, gap: 10, mode: 'bricks' });
    assertNoOverlap(placements);
    const portrait = placements.filter(p => p.tile.aspect < 1), landscape = placements.filter(p => p.tile.aspect >= 1);
    const area = landscape[0].w * landscape[0].h;
    portrait.forEach(p => {
      expect(p.w / p.h).toBeCloseTo(9 / 16, 5);
      expect(p.w * p.h / area).toBeGreaterThan(0.72);
      expect(p.w * p.h / area).toBeLessThan(1.33);
    });
    const portraitRows = new Set(portrait.map(p => p.row)), landscapeRows = new Set(landscape.map(p => p.row));
    expect([...portraitRows].some(r => landscapeRows.has(r))).toBe(false);
    placements.forEach(p => expect(p.x + p.w).toBeLessThanOrEqual(1200.01));
  });

  it('closes a portrait row exactly when that needs at most a 15% scale, otherwise leaves space', () => {
    const closed = layout(Array.from({ length: 9 }, (_, i) => P('p' + i)), { width: 1572, columns: 5, gap: 10, mode: 'bricks' });
    const row = closed.placements.filter(p => p.row === 0);
    expect(row).toHaveLength(9);
    expect(row[row.length - 1].x + row[row.length - 1].w).toBeCloseTo(1572, 5);
    // 1 column, huge gap: one tile per row would need +78%, two would need -20% → natural size, space left over
    const natural = layout(Array.from({ length: 3 }, (_, i) => P('p' + i)), { width: 1000, columns: 1, gap: 100, mode: 'bricks' });
    natural.placements.forEach((p, i) => { expect(p.w).toBeCloseTo(562.5, 5); expect(p.x).toBe(0); expect(p.row).toBe(i); });
  });

  it('keeps encounter order inside each kind and flushes the earlier bucket first', () => {
    const { placements } = layout([P('p1'), L('a'), L('b')], { width: 800, columns: 4, gap: 8, mode: 'bricks' });
    expect(placements.map(p => p.tile.id)).toEqual(['p1', 'a', 'b']);
    expect(placements[0].row).toBe(0);
    expect(placements[1].row).toBe(1);
  });
});

describe('grid layout', () => {
  it('keeps strict order, fits every tile in a square cell with its aspect, and sizes rows by their tallest tile', () => {
    const tiles = [1.5, 0.67, 1.78, 1, 1.33, 0.75, 1.5].map((aspect, i) => ({ id: 't' + i, aspect, data: null }));
    const { placements, height } = layout(tiles, { width: 900, columns: 4, gap: 6, mode: 'grid' });
    const cell = (900 - 18) / 4;
    expect(placements.map(p => p.tile.id)).toEqual(tiles.map(t => t.id));
    assertNoOverlap(placements);
    placements.forEach((p, i) => {
      expect(p.w / p.h).toBeCloseTo(p.tile.aspect, 5);
      expect(Math.max(p.w, p.h)).toBeCloseTo(cell, 5);
      expect(p.row).toBe(Math.floor(i / 4));
      const cellX = (i % 4) * (cell + 6);
      expect(p.x + p.w / 2).toBeCloseTo(cellX + cell / 2, 5);
    });
    expect(placements.filter(p => p.row === 0).every(p => p.y + p.h <= cell + 0.01)).toBe(true);
    expect(height).toBeCloseTo(cell + 6 + cell, 5);
  });
});

describe('responsiveColumns', () => {
  it('keeps the configured count on wide screens and shrinks proportionally below 1440px', () => {
    expect(responsiveColumns(5, 1600)).toBe(5);
    expect(responsiveColumns(5, 1440)).toBe(5);
    expect(responsiveColumns(5, 720)).toBe(3);
    expect(responsiveColumns(5, 300)).toBe(1);
  });
});
