import { describe, expect, it } from 'vitest';
import { ageFrom, cmToFtIn, FIELDS, joinNames, kgToLb, parseChips, parseNames, parseProfile, withField } from './entity-fields';

describe('entity fields', () => {
  it('has the §7.3 schema per kind, keys unique', () => {
    expect(FIELDS.MODEL.map(f => f.key)).toEqual(['born', 'yearsActive', 'birthplace', 'nationality', 'ethnicity', 'hair', 'eyes', 'height', 'weight', 'bodyType', 'measurements', 'tattoos', 'piercings', 'notes']);
    expect(FIELDS.STUDIO.map(f => f.key)).toEqual(['yearsActive', 'web', 'notes']);
    expect(FIELDS.PHOTOGRAPHER).toBe(FIELDS.STUDIO);
    expect(FIELDS.GENRE.map(f => f.key)).toEqual(['description']);
    expect(FIELDS.LIST.map(f => f.key)).toEqual(['description']);
    expect(FIELDS.TAG).toEqual([]);
    for (const fields of Object.values(FIELDS)) expect(new Set(fields.map(f => f.key)).size).toBe(fields.length);
    expect(FIELDS.MODEL.find(f => f.key === 'height')).toMatchObject({ type: 'number', unit: 'cm' });
    expect(FIELDS.MODEL.find(f => f.key === 'tattoos')?.type).toBe('chips');
  });

  it('parses a profile tolerantly and writes fields back, dropping empties', () => {
    expect(parseProfile(null)).toEqual({});
    expect(parseProfile('nonsense')).toEqual({});
    expect(parseProfile('[1]')).toEqual({});
    expect(parseProfile('{"hair":"red"}')).toEqual({ hair: 'red' });
    expect(withField({ hair: 'red' }, 'eyes', 'green')).toBe('{"hair":"red","eyes":"green"}');
    expect(withField({ hair: 'red' }, 'hair', '')).toBeNull();
    expect(withField({ hair: 'red' }, 'tattoos', [])).toBe('{"hair":"red"}');
    expect(withField({}, 'height', NaN)).toBeNull();
  });

  it('computes the age from a year, a month or a full date', () => {
    const today = new Date(2026, 9, 18); // 18 Oct 2026
    expect(ageFrom('1996', today)).toBe(30);
    expect(ageFrom('1996-10-19', today)).toBe(29);
    expect(ageFrom('1996-10-18', today)).toBe(30);
    expect(ageFrom('1996-11', today)).toBe(29);
    expect(ageFrom(' 2001 ', today)).toBe(25);
    expect(ageFrom('late 90s', today)).toBeNull();
    expect(ageFrom('', today)).toBeNull();
    expect(ageFrom('2031', today)).toBeNull();
  });

  it('shows metric values with imperial hints', () => {
    expect(cmToFtIn(173)).toBe('5′8″');
    expect(cmToFtIn('180')).toBe('5′11″');
    expect(cmToFtIn('')).toBe('');
    expect(kgToLb(55)).toBe('121 lb');
    expect(kgToLb('x')).toBe('');
  });

  it('parses chip lists and name lists', () => {
    expect(parseChips(' rose, Rose; star\nmoon,,')).toEqual(['rose', 'star', 'moon']);
    expect(parseNames('Ellena, Aurora; "Smith, John" , Ellena')).toEqual(['Ellena', 'Aurora', 'Smith, John']);
    expect(joinNames(['Ellena', 'Smith, John'])).toBe('Ellena, "Smith, John"');
    expect(parseNames(joinNames(['A', 'B; C']))).toEqual(['A', 'B; C']);
    expect(parseNames('')).toEqual([]);
  });
});
