import { EntityKind } from './model';

/** One editable profile field of an entity card (catalog_plan.md §7.3); the value lives in `Entity.profile` (JSON) under `key`. */
export interface Field { key: string; label: string; type: 'text' | 'number' | 'year' | 'chips' | 'textarea'; unit?: 'cm' | 'kg'; hint?: string }

const t = (key: string, label: string, hint?: string): Field => ({ key, label, type: 'text', hint });
const PERSON = [t('yearsActive', 'Years active', '2015–2020'), t('web', 'Web'), { key: 'notes', label: 'Notes', type: 'textarea' } as Field];

/** Profile fields per kind; tags have no profile. */
export const FIELDS: Record<EntityKind, Field[]> = {
  MODEL: [
    { key: 'born', label: 'Born', type: 'year', hint: 'YYYY or YYYY-MM-DD' }, t('yearsActive', 'Years active', '2015–2020'), t('birthplace', 'Birthplace'), t('nationality', 'Nationality'),
    t('ethnicity', 'Ethnicity'), t('hair', 'Hair'), t('eyes', 'Eyes'), { key: 'height', label: 'Height', type: 'number', unit: 'cm' }, { key: 'weight', label: 'Weight', type: 'number', unit: 'kg' },
    t('bodyType', 'Body type'), t('measurements', 'Measurements', '90-60-90'), { key: 'tattoos', label: 'Tattoos', type: 'chips' }, { key: 'piercings', label: 'Piercings', type: 'chips' },
    { key: 'notes', label: 'Notes', type: 'textarea' },
  ],
  STUDIO: PERSON, PHOTOGRAPHER: PERSON,
  GENRE: [{ key: 'description', label: 'Description', type: 'textarea' }],
  LIST: [{ key: 'description', label: 'Description', type: 'textarea' }],
  TAG: [],
};

export type Profile = Record<string, unknown>;

/** The profile JSON as an object; anything unreadable is an empty profile (the next save overwrites it). */
export function parseProfile(json: string | null | undefined): Profile {
  if (!json) return {};
  try { const v = JSON.parse(json); return v && typeof v === 'object' && !Array.isArray(v) ? v : {}; } catch { return {}; }
}

/** `profile` with `key` set (an empty value removes it); null when nothing is left. */
export function withField(profile: Profile, key: string, value: unknown): string | null {
  const next = { ...profile };
  const empty = value == null || value === '' || (Array.isArray(value) && value.length === 0) || (typeof value === 'number' && !isFinite(value));
  if (empty) delete next[key]; else next[key] = value;
  return Object.keys(next).length ? JSON.stringify(next) : null;
}

/** Age in whole years from `born` (`YYYY`, `YYYY-MM` or `YYYY-MM-DD`); null when no year parses. A year alone assumes mid-year. */
export function ageFrom(born: unknown, today = new Date()): number | null {
  const m = /^\s*(\d{4})(?:-(\d{1,2})(?:-(\d{1,2}))?)?\s*$/.exec(String(born ?? ''));
  if (!m) return null;
  const y = +m[1], mo = m[2] ? +m[2] : 7, d = m[3] ? +m[3] : 1;
  if (y < 1900 || y > today.getFullYear()) return null;
  let age = today.getFullYear() - y;
  if (today.getMonth() + 1 < mo || (today.getMonth() + 1 === mo && today.getDate() < d)) age--;
  return age;
}

/** `173` → `5′8″`; empty for a non-number. */
export function cmToFtIn(cm: unknown): string {
  const n = Number(cm);
  if (!(n > 0)) return '';
  const inches = Math.round(n / 2.54), ft = Math.floor(inches / 12);
  return `${ft}′${inches - ft * 12}″`;
}

/** `55` → `121 lb`; empty for a non-number. */
export function kgToLb(kg: unknown): string {
  const n = Number(kg);
  return n > 0 ? `${Math.round(n * 2.2046)} lb` : '';
}

/** A chip list typed as text: split on `,` / `;` / newlines, trimmed, deduplicated case-insensitively, order kept. */
export function parseChips(text: string): string[] {
  const seen = new Set<string>(), out: string[] = [];
  for (const s of text.split(/[,;\n]/)) { const v = s.trim(), k = v.toLowerCase(); if (v && !seen.has(k)) { seen.add(k); out.push(v); } }
  return out;
}

/** Name-list syntax of the album text fields (`Names.parse`): `,`/`;` separate, a quoted name keeps its separators. */
export function parseNames(text: string): string[] {
  const out: string[] = [];
  for (const m of text.matchAll(/"([^"]*)"|([^,;"]+)/g)) { const v = (m[1] ?? m[2]).trim(); if (v && !out.includes(v)) out.push(v); }
  return out;
}

/** `Names.join`: quotes a name that contains a separator, so `parseNames(joinNames(x))` gives `x` back. */
export function joinNames(names: string[]): string {
  return names.map(n => /[,;]/.test(n) ? `"${n}"` : n).join(', ');
}
