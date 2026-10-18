import { describe, expect, it } from 'vitest';
import { DEFAULT_SETTINGS, Settings } from '../../core/model';
import { rebase } from './settings-draft';

const saved: Settings = { ...DEFAULT_SETTINGS, roots: [{ path: 'D:/Photos', exclusions: [] }], cryKey: 'ab12cd34' };
const renamed: Settings = { ...saved, roots: [{ path: 'D:/Pictures', exclusions: [] }] };

describe('settings draft rebase', () => {
  it('a clean draft follows the saved settings entirely (a renamed root is never resubmitted)', () => {
    expect(rebase(structuredClone(saved), saved, renamed)).toEqual(renamed);
  });

  it('a draft edited elsewhere keeps its edits and takes the rest from the saved settings', () => {
    const draft: Settings = { ...structuredClone(saved), thumbs: { ...saved.thumbs, quality: 60 } };
    const out = rebase(draft, saved, renamed);
    expect(out.roots).toEqual(renamed.roots);
    expect(out.thumbs.quality).toBe(60);
    expect(out).not.toBe(draft);
  });

  it('an edit to the very field that changed wins over the server (the user decides at Save)', () => {
    const draft: Settings = { ...structuredClone(saved), roots: [{ path: 'E:/Mine', exclusions: [] }] };
    expect(rebase(draft, saved, renamed).roots).toEqual([{ path: 'E:/Mine', exclusions: [] }]);
  });

  it('a field the server dropped leaves a clean draft, and the new values are copies', () => {
    const { cryKey, ...dropped } = renamed;
    const out = rebase(structuredClone(saved), saved, dropped as Settings);
    expect('cryKey' in out).toBe(false);
    expect(out.roots).toEqual(dropped.roots);
    expect(out.roots).not.toBe(dropped.roots);
  });
});
