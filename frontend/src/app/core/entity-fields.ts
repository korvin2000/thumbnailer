import { EntityKind } from './model';

/** A choice of a `select` field: the stored key (stable, short) and its label. */
export type Option = readonly [key: string, label: string];
/**
 * One editable profile field of an entity card (catalog_plan.md §7.3); the value lives in `Entity.profile` (JSON) under `key`:
 * `select` = one `options` key, `bool` = true/false (absent = unknown), `years` = `[from, to]` (null `to` = to date; `YEARS_MIN`–`YEARS_MAX`).
 */
export interface Field { key: string; label: string; type: 'text' | 'number' | 'year' | 'chips' | 'textarea' | 'select' | 'bool' | 'years'; unit?: 'cm' | 'kg'; hint?: string; options?: readonly Option[] }

export const YEARS_MIN = 1960, YEARS_MAX = 2050;

/** ISO 3166-1 alpha-2 codes of inhabited places (+ XK); the names come from `Intl.DisplayNames`, so only the codes ship. */
const ISO2 = 'AD AE AF AG AI AL AM AO AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ '
  + 'DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GT GU GW GY HK HN HR HT HU ID IE IL IM IN IQ IR IS IT JE JM JO JP '
  + 'KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ '
  + 'OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TG TH TJ TK TL TM TN TO TR TT TV TW TZ '
  + 'UA UG US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW';
const regions = (() => { try { return new Intl.DisplayNames(['en'], { type: 'region' }); } catch { return null; } })();
/** Countries as `[ISO-2, English name]`, by name: birthplace and nationality store the code. */
export const COUNTRIES: readonly Option[] = ISO2.split(' ').map(c => [c, regions?.of(c) ?? c] as Option).sort((a, b) => a[1].localeCompare(b[1]));

/** `key` or `key=Label` (a bare key is its own capitalized label). */
const opts = (...pairs: string[]): Option[] => pairs.map(p => { const [k, l] = p.split('='); return [k, l ?? k[0].toUpperCase() + k.slice(1)] as Option; });
export const ETHNICITIES = opts('caucasian', 'asian', 'black', 'latina=Latina / Hispanic', 'middle-eastern=Middle Eastern', 'south-asian=South Asian', 'mixed', 'other');
export const HAIR = opts('blonde', 'brown', 'black', 'red', 'auburn', 'grey', 'other=Other / dyed');
export const EYES = opts('blue', 'green', 'grey', 'hazel', 'brown', 'dark=Dark brown');
export const BODY_TYPES = opts('petite', 'slim', 'athletic', 'average', 'curvy', 'full=Full-figured');
export const BREASTS = opts('xs=Very small (AA)', 's=Small (A)', 'm=Medium (B–C)', 'l=Large (D–DD)', 'xl=Very large (E+)');

const t = (key: string, label: string, hint?: string): Field => ({ key, label, type: 'text', hint });
const select = (key: string, label: string, options: readonly Option[]): Field => ({ key, label, type: 'select', options });
const YEARS: Field = { key: 'yearsActive', label: 'Years active', type: 'years' };
const PERSON = [YEARS, t('web', 'Web'), { key: 'notes', label: 'Notes', type: 'textarea' } as Field];

/** Profile fields per kind; tags have no profile. A key no longer listed (a model's old `measurements`) stays in the JSON, unshown. */
export const FIELDS: Record<EntityKind, Field[]> = {
  MODEL: [
    { key: 'born', label: 'Born', type: 'year', hint: 'YYYY or YYYY-MM-DD' }, YEARS, select('birthplace', 'Birthplace', COUNTRIES), select('nationality', 'Nationality', COUNTRIES),
    select('ethnicity', 'Ethnicity', ETHNICITIES), select('hair', 'Hair', HAIR), select('eyes', 'Eyes', EYES),
    { key: 'height', label: 'Height', type: 'number', unit: 'cm' }, { key: 'weight', label: 'Weight', type: 'number', unit: 'kg' },
    select('bodyType', 'Body type', BODY_TYPES), select('breasts', 'Breast size', BREASTS),
    { key: 'tattoos', label: 'Tattoos', type: 'bool' }, { key: 'piercings', label: 'Piercings', type: 'bool' },
    { key: 'notes', label: 'Notes', type: 'textarea' },
  ],
  STUDIO: PERSON, PHOTOGRAPHER: PERSON,
  GENRE: [{ key: 'description', label: 'Description', type: 'textarea' }],
  LIST: [{ key: 'description', label: 'Description', type: 'textarea' }],
  TAG: [],
};

const fold = (s: string) => s.toLowerCase().replace(/\([^)]*\)/g, '').replace(/[^a-z0-9]/g, '');

/**
 * The option key a stored `select` value stands for: the key itself, or (text typed before the field was a select) a key or label
 * matched ignoring case, punctuation and a `(…)` note; null for anything else, which the card keeps showing as it is until replaced.
 */
export function optionOf(f: Field, v: unknown): string | null {
  if (typeof v !== 'string' || !v.trim() || !f.options) return null;
  if (f.options.some(([k]) => k === v)) return v;
  const x = fold(v);
  return f.options.find(([k, l]) => fold(k) === x || fold(l) === x)?.[0] ?? null;
}

/** A `bool` field's value: true/false, null when unknown; reads the older free text too (`None`/`no` = false, other text = true). */
export function boolOf(v: unknown): boolean | null {
  if (typeof v === 'boolean') return v;
  const items = (Array.isArray(v) ? v : typeof v === 'string' ? [v] : []).map(x => String(x).trim().toLowerCase()).filter(Boolean);
  return items.length ? !items.every(x => x === 'none' || x === 'no' || x === 'false') : null;
}

/** A `years` field as `[from, to]` (null = unknown or, for `to`, to date); reads the older text (`2015–2020`, `2018-present`) too. */
export function yearsOf(v: unknown): [number | null, number | null] {
  const year = (x: unknown) => { const n = Number(x); return x != null && x !== '' && Number.isInteger(n) && n >= YEARS_MIN && n <= YEARS_MAX ? n : null; };
  if (Array.isArray(v)) return [year(v[0]), year(v[1])];
  const m = /(\d{4})(?:\D+(\d{4}))?/.exec(String(v ?? ''));
  return m ? [year(+m[1]), m[2] ? year(+m[2]) : null] : [null, null];
}

/** The stored `years` value of two inputs: whole years clamped to `YEARS_MIN`–`YEARS_MAX`, ordered; `[]` (= removed) when both are empty. */
export function yearsValue(from: unknown, to: unknown): (number | null)[] {
  const year = (x: unknown) => x == null || String(x).trim() === '' || !isFinite(Number(x)) ? null : Math.max(YEARS_MIN, Math.min(YEARS_MAX, Math.round(Number(x))));
  const a = year(from), b = year(to);
  return a === null && b === null ? [] : a !== null && b !== null && b < a ? [b, a] : [a, b];
}

/** `[2015, 2020]` → `2015–2020`, `[2018, null]` → `2018–present`; empty when unknown. */
export function yearsText(v: unknown): string {
  const [a, b] = yearsOf(v);
  return a === null && b === null ? '' : `${a ?? '?'}–${b ?? 'present'}`;
}

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
