import { describe, expect, it } from 'vitest';
import { completeLast, splitLast } from './name-combo';

describe('splitLast', () => {
  it('splits at the last separator, trimming the token', () => {
    expect(splitLast('Polina, Je')).toEqual({ head: 'Polina,', token: 'Je' });
    expect(splitLast('a; b ;c')).toEqual({ head: 'a; b ;', token: 'c' });
    expect(splitLast('Polina')).toEqual({ head: '', token: 'Polina' });
    expect(splitLast('')).toEqual({ head: '', token: '' });
    expect(splitLast('Polina, ')).toEqual({ head: 'Polina,', token: '' });
  });
  it('ignores separators inside quotes and unquotes an open token', () => {
    expect(splitLast('"Smith, John", Ann')).toEqual({ head: '"Smith, John",', token: 'Ann' });
    expect(splitLast('Ann, "Smith, Jo')).toEqual({ head: 'Ann,', token: 'Smith, Jo' });
  });
});

describe('completeLast', () => {
  it('replaces the token with the name and re-joins canonically', () => {
    expect(completeLast('Polina, Je', 'Jeny')).toBe('Polina, Jeny');
    expect(completeLast('', 'Jeny')).toBe('Jeny');
    expect(completeLast('a; b , ', 'c')).toBe('a, b, c');
  });
  it('quotes a name with a separator and drops a repeat', () => {
    expect(completeLast('Ann, Smi', 'Smith, John')).toBe('Ann, "Smith, John"');
    expect(completeLast('Jeny, je', 'Jeny')).toBe('Jeny');
  });
});
