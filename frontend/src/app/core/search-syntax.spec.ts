import { describe, expect, it } from 'vitest';
import { parseSearch } from './search-syntax';

describe('parseSearch', () => {
  it('splits hashtags, key:value tokens and free text', () => {
    expect(parseSearch('#Wedding model:"Anna K" by:ivanov kind:zip soft light #outdoor')).toEqual({
      text: 'soft light', tags: ['wedding', 'outdoor'], model: 'Anna K', photographer: 'ivanov', kind: 'ZIP',
    });
  });

  it('accepts short keys and folder kinds, ignores unknown keys as text', () => {
    expect(parseSearch('m:Eva s:Lumen kind:folder foo:bar')).toEqual({ text: 'foo:bar', tags: [], model: 'Eva', studio: 'Lumen', kind: 'DIRECTORY' });
  });

  it('returns no text for an empty or whitespace entry', () => {
    expect(parseSearch('   ')).toEqual({ tags: [] });
  });
});
