import { describe, expect, it } from 'vitest';
import { SELECT_CAP, Selection } from './selection';

const order = ['a', 'b', 'c', 'd', 'e'];
const ids = (s: Selection) => [...s.ids()];

describe('Selection', () => {
  it('plain click selects only that id', () => {
    const s = new Selection();
    s.click('b', order); s.click('d', order);
    expect(ids(s)).toEqual(['d']);
    expect(s.size()).toBe(1);
  });
  it('ctrl click toggles', () => {
    const s = new Selection();
    s.click('b', order); s.click('d', order, { ctrl: true });
    expect(ids(s)).toEqual(['b', 'd']);
    s.click('b', order, { ctrl: true });
    expect(ids(s)).toEqual(['d']);
    expect(s.has('b')).toBe(false);
  });
  it('shift click ranges from the anchor over the order, either direction, and keeps the anchor', () => {
    const s = new Selection();
    s.click('b', order); s.click('d', order, { shift: true });
    expect(ids(s)).toEqual(['b', 'c', 'd']);
    s.click('a', order, { shift: true });
    expect(ids(s)).toEqual(['a', 'b']);
  });
  it('ctrl+shift adds the range to the selection', () => {
    const s = new Selection();
    s.click('a', order); s.click('d', order, { ctrl: true }); s.click('e', order, { ctrl: true, shift: true });
    expect(ids(s)).toEqual(['a', 'd', 'e']);
  });
  it('shift without an anchor acts as a plain click', () => {
    const s = new Selection();
    s.click('c', order, { shift: true });
    expect(ids(s)).toEqual(['c']);
  });
  it('select all stops at the cap', () => {
    const s = new Selection(), many = Array.from({ length: SELECT_CAP + 20 }, (_, i) => `x${i}`);
    s.selectAll(many);
    expect(s.size()).toBe(SELECT_CAP);
    expect(s.has(`x${SELECT_CAP - 1}`)).toBe(true);
    s.click(`x${SELECT_CAP + 5}`, many, { ctrl: true });
    expect(s.size()).toBe(SELECT_CAP);
  });
  it('select first N takes the order prefix, never beyond the cap', () => {
    const s = new Selection();
    s.selectFirst(order, 3);
    expect(ids(s)).toEqual(['a', 'b', 'c']);
    s.selectFirst(Array.from({ length: 900 }, (_, i) => String(i)), 800);
    expect(s.size()).toBe(SELECT_CAP);
  });
  it('keyboard: an arrow move is a plain click, Shift+arrows range from it either way, Ctrl+Space toggles without moving the anchor', () => {
    const s = new Selection();
    s.click('b', order); // → lands on b
    s.click('c', order, { shift: true }); s.click('d', order, { shift: true }); // Shift+→ twice
    expect(ids(s)).toEqual(['b', 'c', 'd']);
    s.click('c', order, { shift: true }); // Shift+← shrinks back towards the anchor
    expect(ids(s)).toEqual(['b', 'c']);
    s.click('a', order, { shift: true }); // Shift+Home-like jump past the anchor
    expect(ids(s)).toEqual(['a', 'b']);
    s.click('e', order, { ctrl: true }); // Ctrl+Space on e
    expect(ids(s)).toEqual(['a', 'b', 'e']);
    s.click('d', order, { shift: true }); // the anchor is now e
    expect(ids(s)).toEqual(['d', 'e']);
  });
  it('clear empties the selection and drops the anchor', () => {
    const s = new Selection();
    s.click('b', order); s.clear();
    expect(s.size()).toBe(0);
    s.click('d', order, { shift: true });
    expect(ids(s)).toEqual(['d']);
  });
});
