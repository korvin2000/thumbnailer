import { describe, expect, it } from 'vitest';
import { ageFrom, boolOf, cmToFtIn, COUNTRIES, FIELDS, joinNames, kgToLb, optionOf, parseChips, parseNames, parseProfile, withField, yearsOf, yearsText, yearsValue } from './entity-fields';

describe('entity fields', () => {
  it('has the §7.3 schema per kind, keys unique', () => {
    expect(FIELDS.MODEL.map(f => f.key)).toEqual(['born', 'yearsActive', 'birthplace', 'nationality', 'ethnicity', 'hair', 'eyes', 'height', 'weight', 'bodyType', 'breasts', 'tattoos', 'piercings', 'notes']);
    expect(FIELDS.STUDIO.map(f => f.key)).toEqual(['yearsActive', 'web', 'notes']);
    expect(FIELDS.PHOTOGRAPHER).toBe(FIELDS.STUDIO);
    expect(FIELDS.GENRE.map(f => f.key)).toEqual(['description']);
    expect(FIELDS.LIST.map(f => f.key)).toEqual(['description']);
    expect(FIELDS.TAG).toEqual([]);
    for (const fields of Object.values(FIELDS)) expect(new Set(fields.map(f => f.key)).size).toBe(fields.length);
    expect(FIELDS.MODEL.find(f => f.key === 'height')).toMatchObject({ type: 'number', unit: 'cm' });
    expect(FIELDS.MODEL.find(f => f.key === 'tattoos')?.type).toBe('bool');
    expect(FIELDS.STUDIO.find(f => f.key === 'yearsActive')?.type).toBe('years');
    for (const f of FIELDS.MODEL.filter(f => f.type === 'select')) expect(new Set(f.options!.map(([k]) => k)).size, f.key).toBe(f.options!.length);
  });

  it('lists countries by ISO-2 code with their names', () => {
    expect(COUNTRIES.find(([k]) => k === 'UA')?.[1]).toBe('Ukraine');
    expect(COUNTRIES.length).toBeGreaterThan(200);
    expect(COUNTRIES.every(([k]) => /^[A-Z]{2}$/.test(k))).toBe(true);
  });

  it('reads select values by key, older text by key or label, else null', () => {
    const f = (key: string) => FIELDS.MODEL.find(x => x.key === key)!;
    expect(optionOf(f('hair'), 'brown')).toBe('brown');
    expect(optionOf(f('hair'), ' Brown ')).toBe('brown');
    expect(optionOf(f('nationality'), 'ukraine')).toBe('UA');
    expect(optionOf(f('ethnicity'), 'Latina / Hispanic')).toBe('latina');
    expect(optionOf(f('breasts'), 'Medium')).toBe('m');
    expect(optionOf(f('nationality'), 'ukrainian')).toBeNull();
    expect(optionOf(f('hair'), 7)).toBeNull();
  });

  it('reads booleans, older chip lists and text too', () => {
    expect([true, false, null, undefined, [], ['None'], 'no', ['rose', 'star'], 'yes'].map(boolOf)).toEqual([true, false, null, null, null, false, false, true, true]);
    expect(withField({}, 'tattoos', false)).toBe('{"tattoos":false}');
  });

  it('keeps years active as a clamped, ordered range; reads the older text', () => {
    expect(yearsOf([2015, 2020])).toEqual([2015, 2020]);
    expect(yearsOf([2018, null])).toEqual([2018, null]);
    expect(yearsOf('2015–2020')).toEqual([2015, 2020]);
    expect(yearsOf('2018-present')).toEqual([2018, null]);
    expect(yearsOf('1900-2100')).toEqual([null, null]);
    expect(yearsValue('2020', '2015')).toEqual([2015, 2020]);
    expect(yearsValue('1950', '')).toEqual([1960, null]);
    expect(yearsValue('', '2077')).toEqual([null, 2050]);
    expect(yearsValue('', ' ')).toEqual([]);
    expect(withField({}, 'yearsActive', yearsValue('', ''))).toBeNull();
    expect([yearsText([2015, 2020]), yearsText([2018, null]), yearsText(undefined)]).toEqual(['2015–2020', '2018–present', '']);
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
