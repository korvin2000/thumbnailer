import { describe, expect, it } from 'vitest';
import { commonSuffixMapping, normPath } from './paths';

describe('normPath', () => {
  it('turns backslashes into slashes and drops trailing slashes', () => {
    expect(normPath('C:\\a\\b\\')).toBe('C:/a/b');
    expect(normPath(' /mnt/photos/ ')).toBe('/mnt/photos');
  });
  it('keeps the slash of a bare drive', () => {
    expect(normPath('C:')).toBe('C:/');
    expect(normPath('C:\\')).toBe('C:/');
  });
});

describe('commonSuffixMapping', () => {
  it('maps drive prefixes', () => expect(commonSuffixMapping('W:/galleries', 'X:/users/galleries')).toEqual({ from: 'W:/', to: 'X:/users/' }));
  it('keeps the leading slash of absolute Unix prefixes', () => expect(commonSuffixMapping('/mnt/photos', '/media/photos')).toEqual({ from: '/mnt/', to: '/media/' }));
  it('matches segments case-insensitively and keeps the stored case', () => expect(commonSuffixMapping('W:/Shoots/2019', 'x:/backup/shoots/2019')).toEqual({ from: 'W:/', to: 'x:/backup/' }));
  it('falls back to the whole paths without a common suffix', () => expect(commonSuffixMapping('W:/galleries', 'X:/pictures')).toEqual({ from: 'W:/galleries/', to: 'X:/pictures/' }));
});
