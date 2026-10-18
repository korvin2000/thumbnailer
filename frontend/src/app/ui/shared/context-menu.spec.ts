import { describe, expect, it } from 'vitest';
import { placeMenu } from './context-menu';

const vw = 1000, vh = 800, w = 200, h = 300;
const pt = (x: number, y: number) => ({ x, y, w: 0, h: 0 });
const row = (x: number, y: number) => ({ x, y, w: 190, h: 28 });

describe('placeMenu', () => {
  it('opens a root menu at its point when it fits', () => {
    expect(placeMenu(pt(100, 100), w, h, vw, vh)).toEqual({ x: 100, y: 100 });
  });
  it('flips a root menu to the left of / above its point near the right / bottom edge', () => {
    expect(placeMenu(pt(900, 100), w, h, vw, vh)).toEqual({ x: 700, y: 100 });
    expect(placeMenu(pt(100, 700), w, h, vw, vh)).toEqual({ x: 100, y: 400 });
    expect(placeMenu(pt(900, 700), w, h, vw, vh)).toEqual({ x: 700, y: 400 });
  });
  it('slides a root menu inside the viewport when it fits neither way', () => {
    expect(placeMenu(pt(150, 200), w, h, 300, vh)).toEqual({ x: 96, y: 200 });
    expect(placeMenu(pt(10, 10), 400, 900, 300, vh)).toEqual({ x: 4, y: 4 });
  });
  it('hangs a submenu off the right of its parent row, first row level with it', () => {
    expect(placeMenu(row(100, 100), w, h, vw, vh, true)).toEqual({ x: 286, y: 95 });
  });
  it('flips a submenu to the left of the row near the right edge and slides it up near the bottom', () => {
    expect(placeMenu(row(780, 100), w, h, vw, vh, true)).toEqual({ x: 584, y: 95 });
    expect(placeMenu(row(100, 700), w, h, vw, vh, true)).toEqual({ x: 286, y: 496 });
  });
  it('keeps a submenu inside the viewport when neither side fits', () => {
    expect(placeMenu(row(50, 100), 400, h, 500, vh, true)).toEqual({ x: 96, y: 95 });
  });
});
