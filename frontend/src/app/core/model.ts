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
}

export interface Entry {
  index: number;
  name: string;
  bytes: number;
  width: number;
  height: number;
  shotAt: string | null;
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
}

/** How a root's disk is read: detected (AUTO), or forced; an HDD (also network/unknown) is read one file at a time. */
export type Storage = 'AUTO' | 'SSD' | 'HDD';
export interface ScanRoot { path: string; exclusions: string[]; storage?: Storage }
export type ImageFormat = 'WEBP' | 'JPEG';
/** How covers or thumbnails are rendered: `size` = longer side, `threads` 0 = CPU count. */
export interface Render { format: ImageFormat; quality: number; size: number; threads: number }
/** Prefix rewrite applied on this machine; both ends are normalized dirs ending in '/'. */
export interface Mapping { from: string; to: string }
/** `cryKey`: xanderView's `.cry` key, derived by the server from a `cryPassword` sent with a save (never kept or echoed) and held in server memory for the session only (never in settings.json); on the wire it is the key's 8-hex fingerprint: sending it back keeps the session key, a stale one is dropped; null/absent = none. */
export interface Settings { roots: ScanRoot[]; exclusions: string[]; scanOnStart: boolean; pathMap: Mapping[]; covers: Render; thumbs: Render; cryKey?: string | null; cryPassword?: string | null }
export const DEFAULT_SETTINGS: Settings = { roots: [], exclusions: [], scanOnStart: true, pathMap: [], covers: { format: 'WEBP', quality: 75, size: 512, threads: 4 }, thumbs: { format: 'JPEG', quality: 85, size: 512, threads: 0 } };
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
export interface Facets { tags: Facet[]; models: Facet[]; studios: Facet[]; photographers: Facet[] }

export interface SearchResult { albums: Album[]; total: number }
export interface AlbumDetails { album: Album; entries: Entry[] }

export type GatewayEvent =
  | { type: 'albumChanged'; album: Album }
  | { type: 'albumRemoved'; albumId: string }
  | ({ type: 'scanProgress' } & ScanProgress)
  | { type: 'connection'; connected: boolean };

/** Count of active narrowing criteria, for the filter badge. */
export function activeFilterCount(f: Filters): number {
  return Object.entries(f).filter(([k, v]) => k !== 'text' && v !== undefined && v !== null && !(Array.isArray(v) && v.length === 0)).length;
}
