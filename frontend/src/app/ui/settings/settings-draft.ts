/**
 * Pure: the settings draft after the saved settings changed underneath it (an operation renamed a root, another client saved). Every top-level
 * field the user left as it was saved (`prev`) follows the new saved value (`next`); a field the user edited keeps the edit. So a stale draft
 * never resubmits a root the server has already renamed (`retainRoots` would drop that root's albums).
 */
export function rebase<T extends object>(draft: T, prev: T, next: T): T {
  const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
  const d = draft as Record<string, unknown>, p = prev as Record<string, unknown>, n = next as Record<string, unknown>, out = { ...d };
  for (const k of new Set([...Object.keys(p), ...Object.keys(n)])) {
    if (!same(d[k], p[k])) continue;
    if (k in n) out[k] = structuredClone(n[k]); else delete out[k];
  }
  return out as T;
}
