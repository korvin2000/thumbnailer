/** Wire model — mirrors docs/ARCHITECTURE.md §1 and §3. */

export type Kind = 'DIRECTORY' | 'ZIP';

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
  | 'BEST_QUALITY' | 'TOP_RATED' | 'NAME' | 'RECENTLY_INDEXED';

export const SORTS: { value: Sort; label: string }[] = [
  { value: 'NEWEST', label: 'Newest first' },
  { value: 'OLDEST', label: 'Oldest first' },
  { value: 'LARGEST', label: 'Biggest first' },
  { value: 'SMALLEST', label: 'Smallest first' },
  { value: 'MOST_PHOTOS', label: 'Most photos' },
  { value: 'FEWEST_PHOTOS', label: 'Fewest photos' },
  { value: 'BEST_QUALITY', label: 'Best quality' },
  { value: 'TOP_RATED', label: 'Top rated' },
  { value: 'NAME', label: 'Name A–Z' },
  { value: 'RECENTLY_INDEXED', label: 'Recently indexed' },
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

export interface ScanRoot { path: string; exclusions: string[] }
export interface Settings { roots: ScanRoot[]; exclusions: string[]; scanOnStart: boolean }
export interface RootStatus { path: string; exists: boolean; albumCount: number; lastScanAt: string | null }
export interface SettingsInfo { settings: Settings; dataDir: string; roots: RootStatus[] }

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
