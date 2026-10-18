import { describe, expect, it } from 'vitest';
import { Entry, PhotoMarks } from '../../core/model';
import { filter, nextScale, order, SCALE_CYCLE, scaleBox, shuffle } from './photo-order';

const e = (index: number, name: string, bytes: number, width: number, height: number, shotAt: string | null = null): Entry => ({ index, name, bytes, width, height, shotAt, flags: 0 });
const entries = [e(0, 'img10.jpg', 300, 1000, 1500, '2020-01-03T10:00:00'), e(1, 'img2.jpg', 100, 3000, 2000, null), e(2, 'img1.jpg', 200, 2000, 2000, '2020-01-01T10:00:00'), e(3, 'IMG3.jpg', 200, 800, 600, '2020-01-02T10:00:00')];
const marks: PhotoMarks = { 'img2.jpg': { rating: 8, lists: [1] }, 'IMG3.jpg': { rating: 3, lists: [] } };
const names = (xs: Entry[]) => xs.map(x => x.name);

describe('order', () => {
  it('sorts names naturally and case-insensitively, reversed when desc', () => {
    expect(names(order(entries, 'name', false, marks, 1))).toEqual(['img1.jpg', 'img2.jpg', 'IMG3.jpg', 'img10.jpg']);
    expect(names(order(entries, 'name', true, marks, 1))).toEqual(['img10.jpg', 'IMG3.jpg', 'img2.jpg', 'img1.jpg']);
  });
  it('breaks size ties by index so the order is stable', () => {
    expect(names(order(entries, 'size', false, marks, 1))).toEqual(['img2.jpg', 'img1.jpg', 'IMG3.jpg', 'img10.jpg']);
    expect(names(order(entries, 'size', true, marks, 1))).toEqual(['img10.jpg', 'img1.jpg', 'IMG3.jpg', 'img2.jpg']);
  });
  it('sorts by pixel count and by rating (unmarked = 0)', () => {
    expect(names(order(entries, 'dimensions', true, marks, 1))).toEqual(['img2.jpg', 'img1.jpg', 'img10.jpg', 'IMG3.jpg']);
    expect(names(order(entries, 'rating', true, marks, 1))).toEqual(['img2.jpg', 'IMG3.jpg', 'img10.jpg', 'img1.jpg']); // ties keep index order in both directions
    expect(names(order(entries, 'rating', false, marks, 1))).toEqual(['img10.jpg', 'img1.jpg', 'IMG3.jpg', 'img2.jpg']);
  });
  it('keeps undated photos last in both date directions', () => {
    expect(names(order(entries, 'date', false, marks, 1))).toEqual(['img1.jpg', 'IMG3.jpg', 'img10.jpg', 'img2.jpg']);
    expect(names(order(entries, 'date', true, marks, 1))).toEqual(['img10.jpg', 'IMG3.jpg', 'img1.jpg', 'img2.jpg']);
  });
  it('shuffles reproducibly per seed and differently per reshuffle', () => {
    const many = Array.from({ length: 40 }, (_, i) => e(i, `${i}.jpg`, i, 10, 10));
    const a = order(many, 'random', false, {}, 7), b = order(many, 'random', false, {}, 7), c = order(many, 'random', false, {}, 8);
    expect(names(a)).toEqual(names(b));
    expect(names(a)).not.toEqual(names(c));
    expect([...names(a)].sort()).toEqual([...names(many)].sort());
    expect(names(order(many, 'random', true, {}, 7))).toEqual(names(a).reverse());
    expect(shuffle([], 1)).toEqual([]);
  });
});

describe('filter', () => {
  it('passes everything when both limits are 0 and keeps the array identity', () => { expect(filter(entries, 0, 0)).toBe(entries); });
  it('applies the longer side (orientation-free) and the byte floor together', () => {
    expect(names(filter(entries, 1200, 0))).toEqual(['img10.jpg', 'img2.jpg', 'img1.jpg']);
    expect(names(filter(entries, 1200, 150))).toEqual(['img10.jpg', 'img1.jpg']);
    expect(names(filter([e(9, 'x', 50, 0, 0)], 1800, 0))).toEqual(['x']);
  });
});

describe('scale', () => {
  const vp = { width: 1000, height: 800 }, small = { width: 500, height: 200 }, big = { width: 4000, height: 3000 };
  it('fit fills the viewport either way, fitLarge only shrinks', () => {
    expect(scaleBox('fit', small, vp)).toEqual({ width: 1000, height: 400 });
    expect(scaleBox('fit', big, vp)).toEqual({ width: 1000, height: 750 });
    expect(scaleBox('fitLarge', small, vp)).toEqual(small);
    expect(scaleBox('fitLarge', big, vp)).toEqual({ width: 1000, height: 750 });
  });
  it('actual is 1:1, width and height fill one axis and may overflow the other', () => {
    expect(scaleBox('actual', big, vp)).toEqual(big);
    expect(scaleBox('width', big, vp)).toEqual({ width: 1000, height: 750 });
    expect(scaleBox('height', big, vp)).toEqual({ width: 1067, height: 800 });
    expect(scaleBox('width', small, vp)).toEqual({ width: 1000, height: 400 });
  });
  it('survives unknown dimensions and cycles through every mode', () => {
    expect(scaleBox('fit', { width: 0, height: 0 }, vp)).toEqual({ width: 800, height: 800 });
    let s = SCALE_CYCLE[0]; const seen = [s];
    for (let i = 1; i < SCALE_CYCLE.length; i++) seen.push(s = nextScale(s));
    expect(seen).toEqual(SCALE_CYCLE);
    expect(nextScale('height')).toBe('fit');
  });
});
