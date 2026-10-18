/** Wire model — mirrors docs/ARCHITECTURE.md §1 and §3. */

export type Kind = 'DIRECTORY' | 'ZIP' | 'ARCHIVE'; // ARCHIVE: any other archive 7-Zip reads (rar, 7z, tar, iso, tar.gz ...)

export interface Album {
  id: string;
  root: string;
  path: string;
  kind: Kind;
  name: string;
  cover: string;
  coverPinned: boolean;
  coverPortrait: boolean;
  revision: number;
  imageCount: number;
  bytes: number;
  avgPixels: number;
  maxWidth: number;
  maxHeight: number;
  shotDate: string | null;
  modifiedAt: string;
  indexedAt: string;
  model: string | null;
  studio: string | null;
  photographer: string | null;
  description: string | null;
  tags: string[];
  rating: number;
  /** Safe-deleted: left out of every default query and count. */
  hidden: boolean;
  /** Entry name of the album's `*bio*.txt` / `*model*.txt`, if any. */
  bio: string | null;
  /** Content stamp (16 hex); albums sharing it are copy candidates. */
  stamp: string;
  genres: string[];
  /** Catalog entities the text fields resolve to, plus favorites lists (role LIST). */
  links: Link[];
  /** Albums sharing `stamp`, this one included (1 = unique). */
  copies: number;
}

/** Catalog kinds, in code order 1..6. */
export type EntityKind = 'MODEL' | 'STUDIO' | 'PHOTOGRAPHER' | 'GENRE' | 'TAG' | 'LIST';
export const ENTITY_KINDS: EntityKind[] = ['MODEL', 'STUDIO', 'PHOTOGRAPHER', 'GENRE', 'TAG', 'LIST'];
/** `auto` = created from album text, not yet confirmed; `albums` = visible linked albums; `profile` absent when null; `photos` for lists only. */
export interface Entity { id: number; kind: EntityKind; name: string; rating: number; auto: boolean; albums: number; profile?: string; photos?: number }
/** One album → entity link; `alias` = the token as written when it is not the primary name. */
export interface Link { entity: number; role: EntityKind; name: string; alias?: string }
export interface EntityQuery { text?: string; under?: number; byCount?: boolean; offset?: number; limit?: number }
export interface EntityList { items: Entity[]; total: number }
/** Curated entity → entity fact as read (`roles` = genre names, model → studio only). */
export interface EntityLinkOut { to: Entity; alias?: string; roles?: string }
/** The same fact as written. */
export interface EntityLinkIn { to: number; alias?: string; roles?: string }
export interface StudioRow { entity: Entity; aliases: string[]; genres: string[]; albums: number }
/** Read-only facts from the entity's albums; sections per kind. */
export interface Derived { albums: number; models?: Entity[]; studios?: StudioRow[]; photographers?: Entity[]; genres?: Entity[]; tags?: string[] }
/** `derived` is absent until the server computes it (catalog track B1). */
export interface EntityCard { entity: Entity; aliases: string[]; links: EntityLinkOut[]; derived?: Derived }
/** `aliases` / `links` absent or null keep the current set, a list replaces it. */
export interface EntitySave { id?: number; kind: EntityKind; name: string; rating: number; profile: string | null; aliases?: string[] | null; links?: EntityLinkIn[] | null }

export const ENTRY_META_KNOWN = 1;
export const ENTRY_META = 2;

export interface Entry {
  index: number;
  name: string;
  bytes: number;
  width: number;
  height: number;
  shotAt: string | null;
  /** `ENTRY_META_KNOWN` | `ENTRY_META`; 0 = not known yet. */
  flags: number;
}

export type Sort =
  | 'NEWEST' | 'OLDEST' | 'LARGEST' | 'SMALLEST' | 'MOST_PHOTOS' | 'FEWEST_PHOTOS'
  | 'BEST_QUALITY' | 'TOP_RATED' | 'NAME' | 'RECENTLY_INDEXED'
  | 'WORST_QUALITY' | 'LOWEST_RATED' | 'NAME_DESC' | 'FIRST_INDEXED';

/** One sortable field with its two wire orders; the first listed direction is the default when the field is picked. */
export interface SortField { label: string; desc: Sort; asc: Sort; ascFirst?: boolean }
export const SORT_FIELDS: SortField[] = [
  { label: 'Date shot', desc: 'NEWEST', asc: 'OLDEST' },
  { label: 'Size', desc: 'LARGEST', asc: 'SMALLEST' },
  { label: 'Photos', desc: 'MOST_PHOTOS', asc: 'FEWEST_PHOTOS' },
  { label: 'Quality', desc: 'BEST_QUALITY', asc: 'WORST_QUALITY' },
  { label: 'Rating', desc: 'TOP_RATED', asc: 'LOWEST_RATED' },
  { label: 'Name', desc: 'NAME_DESC', asc: 'NAME', ascFirst: true },
  { label: 'Indexed', desc: 'RECENTLY_INDEXED', asc: 'FIRST_INDEXED' },
];

/** Everything the user can narrow a search by; absent = no filter. */
export interface Filters {
  text?: string;
  tags?: string[];
  model?: string;
  studio?: string;
  photographer?: string;
  roots?: string[];
  kind?: Kind;
  minBytes?: number;
  maxBytes?: number;
  minCount?: number;
  maxCount?: number;
  minMegapixels?: number;
  maxMegapixels?: number;
  dateFrom?: string;
  dateTo?: string;
  minRating?: number;
  maxRating?: number;
  /** Linked to every one of these entities. */
  entities?: number[];
  /** No link of this role. */
  missing?: EntityKind;
  /** true = only hidden albums; absent/false = only visible ones. */
  hidden?: boolean;
  /** Only albums with copies. */
  duplicates?: boolean;
  genres?: string[];
}

export interface AlbumQuery extends Filters {
  sort: Sort;
  offset: number;
  limit: number;
}

/** null/absent = unchanged; "" or [] = explicit empty; cover "" unpins. */
export interface AlbumPatch {
  name?: string;
  model?: string;
  studio?: string;
  photographer?: string;
  description?: string;
  tags?: string[];
  rating?: number;
  cover?: string;
  /** `YYYY-MM-DD`, `YYYY-MM` or `YYYY` (stored as the first day); "" removes the override. */
  date?: string;
  genres?: string[];
  hidden?: boolean;
  /** Additive tag edits (bulk), unlike `tags`, which replaces the list. */
  addTags?: string[];
  removeTags?: string[];
}

/** Albums an action applies to: explicit ids, else a query's matches (≤ 500 either way). */
export interface AlbumTarget { albumIds?: string[]; query?: Filters }

/** How a root's disk is read: detected (AUTO), or forced; an HDD (also network/unknown) is read one file at a time. */
export type Storage = 'AUTO' | 'SSD' | 'HDD';
export interface ScanRoot { path: string; exclusions: string[]; storage?: Storage }
export type ImageFormat = 'WEBP' | 'JPEG';
/** How covers or thumbnails are rendered: `size` = longer side, `threads` 0 = CPU count. */
export interface Render { format: ImageFormat; quality: number; size: number; threads: number }
/** Prefix rewrite applied on this machine; both ends are normalized dirs ending in '/'. */
export interface Mapping { from: string; to: string }
/** `cryKey`: xanderView's `.cry` key, derived by the server from a `cryPassword` sent with a save (never kept or echoed) and held in server memory for the session only (never in settings.json); on the wire it is the key's 8-hex fingerprint: sending it back keeps the session key, a stale one is dropped; null/absent = none. */
/** `copyTargets`: ≤ 10 folders (viewer keys 1–0); `ops.packFormat`: the default for Pack. */
export interface Settings { roots: ScanRoot[]; exclusions: string[]; scanOnStart: boolean; pathMap: Mapping[]; covers: Render; thumbs: Render; cryKey?: string | null; cryPassword?: string | null; copyTargets: string[]; ops: { packFormat: PackFormat } }
export const DEFAULT_SETTINGS: Settings = { roots: [], exclusions: [], scanOnStart: true, pathMap: [], covers: { format: 'WEBP', quality: 75, size: 512, threads: 4 }, thumbs: { format: 'JPEG', quality: 85, size: 512, threads: 0 }, copyTargets: [], ops: { packFormat: 'AUTO' } };
/** `location` = `path` after the path map, i.e. where the root is looked for on this machine. */
export interface RootStatus { path: string; location: string; exists: boolean; albumCount: number; lastScanAt: string | null; storage: 'SSD' | 'HDD' }
export interface SettingsInfo { settings: Settings; dataDir: string; roots: RootStatus[]; renderEpoch: string }

/** Child folder of a stored album path; `albums` counts at or below it, `nested` = some lie strictly below. */
export interface Folder { name: string; path: string; albums: number; nested: boolean }
/** One level of the real file system, for the folder picker; `error` with the nearest reachable ancestor when `path` was not. */
export interface Listing { path: string; parent: string | null; drives: string[]; folders: { name: string; path: string }[]; error: string | null }

export interface LibraryStats {
  albums: number; images: number; imageBytes: number; lastIndexedAt: string | null;
  coversCount: number; coversBytes: number; coversFile: number; metadataBytes: number; metadataFile: number; settingsBytes: number;
}
/** `home` = data dir holding libraries.json; `dir` = active library; `pinned` = forced by CLI/env, switching disabled. */
export interface LibraryInfo { home: string; dir: string; known: string[]; pinned: boolean; stats: LibraryStats; about: { version: string; java: string; os: string } }

export interface ScanProgress {
  running: boolean;
  root: string | null;
  discovered: number;
  indexed: number;
  changed: number;
  removed: number;
  message: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface Facet { value: string; count: number }
export interface Facets { tags: Facet[]; models: Facet[]; studios: Facet[]; photographers: Facet[]; genres: Facet[] }

export interface SearchResult { albums: Album[]; total: number }
/** Marks of one photo; an album's marks are sparse, by entry name. */
export interface PhotoMark { rating: number; lists: number[] }
export type PhotoMarks = { [name: string]: PhotoMark };
export interface AlbumDetails { album: Album; entries: Entry[]; marks: PhotoMarks }
export interface PhotoListItem { albumId: string; name: string; index: number; rating: number; album: { id: string; name: string; revision: number } }
export interface PhotoList { items: PhotoListItem[]; total: number }
export interface MetaRow { dir: string; tag: string; value: string }
export interface Comparison { identical: boolean; differing: string[] }

// ---- file operations ----

export type OpKind = 'MOVE' | 'COPY' | 'DELETE' | 'PACK' | 'UNPACK' | 'REPACK' | 'CRYPT' | 'COPY_PHOTOS' | 'FOLDER_RENAME' | 'FOLDER_MOVE' | 'FOLDER_COPY' | 'FOLDER_DELETE';
/** AUTO = zip without recompression. */
export type PackFormat = 'AUTO' | 'ZIP' | 'SEVEN_ZIP';
export type Conflict = 'SKIP' | 'RENAME';
/** Album ops name albums (`albumIds`/`query`), COPY_PHOTOS one album and its entry `names`, FOLDER_* the folder in `paths[0]`; `target` = destination folder (FOLDER_RENAME: the new name). */
export interface Op extends AlbumTarget { kind: OpKind; paths?: string[]; target?: string; format?: PackFormat; encrypt?: boolean; names?: string[]; conflict: Conflict; password?: string }
export interface PreflightItem { path: string; skip: boolean; reason?: string; note?: string }
export interface Preflight { items: PreflightItem[]; bytes: number; free: number; warnings: string[]; consequences: string[] }
export interface OpProgress { id: string; kind: OpKind; running: boolean; total: number; done: number; current: string | null; skipped: number; failed: number; message: string | null; startedAt: string | null; finishedAt: string | null }
export interface OpIssue { item: string; reason: string }
export interface OpResult { id: string; done: number; skipped: OpIssue[]; failed: OpIssue[] }

export type GatewayEvent =
  | { type: 'albumChanged'; album: Album }
  | { type: 'albumRemoved'; albumId: string }
  | ({ type: 'scanProgress' } & ScanProgress)
  | { type: 'connection'; connected: boolean }
  | { type: 'catalogChanged'; kinds: EntityKind[] }
  | ({ type: 'opProgress' } & OpProgress)
  | ({ type: 'opResult' } & OpResult);

/** Count of active narrowing criteria, for the filter badge (a `false` switch is no criterion). */
export function activeFilterCount(f: Filters): number {
  return Object.entries(f).filter(([k, v]) => k !== 'text' && v !== undefined && v !== null && v !== false && !(Array.isArray(v) && v.length === 0)).length;
}
