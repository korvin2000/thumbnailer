/** Path helpers for the normalized ('/'-separated, absolute) paths the backend stores and sends. */

/** 'C:\\a\\b\\' → 'C:/a/b'; a bare drive keeps its slash ('C:/'). */
export function normPath(raw: string): string {
  const p = raw.trim().replace(/\\/g, '/').replace(/\/+$/, '');
  return /^[A-Za-z]:$/.test(p) ? p + '/' : p;
}

/** 'D:/Photos/2019' → '2019'; a drive root stays 'D:'. */
export const lastSegment = (path: string) => path.split('/').filter(Boolean).pop() ?? path;

/** 'X:/a' → 'X:/a/': a directory prefix as path-map rules store it. */
export const dir = (p: string) => p.endsWith('/') ? p : p + '/';

/** Path-map rule moving `stored` to `found` by their longest common (case-insensitive) segment suffix; the prefixes are cut from the original strings, so '/' and 'C:/' survive. `from` keeps at least one segment; no common suffix → the whole paths. */
export function commonSuffixMapping(stored: string, found: string): { from: string; to: string } {
  const a = normPath(stored), b = normPath(found), sa = a.split('/').filter(Boolean), sb = b.split('/').filter(Boolean);
  let k = 0;
  while (k < sa.length - 1 && k < sb.length && sa[sa.length - 1 - k].toLowerCase() === sb[sb.length - 1 - k].toLowerCase()) k++;
  if (!k) return { from: dir(a), to: dir(b) };
  const cut = (p: string, segs: string[]) => dir(p.slice(0, p.length - segs.slice(-k).join('/').length));
  return { from: cut(a, sa), to: cut(b, sb) };
}
