import { Observable } from 'rxjs';
import {
  Album, AlbumDetails, AlbumPatch, AlbumQuery, Facets, Filters, Folder, GatewayEvent, LibraryInfo, Listing, SearchResult,
  Settings, SettingsInfo,
} from './model';

/** Transport-agnostic access to the album service; implemented over WebSocket+HTTP and as an in-browser mock. */
export abstract class Gateway {
  abstract readonly events: Observable<GatewayEvent>;
  abstract readonly mock: boolean;

  abstract search(query: AlbumQuery): Promise<SearchResult>;
  abstract album(id: string): Promise<AlbumDetails>;
  abstract update(id: string, patch: AlbumPatch): Promise<Album>;
  /** Each path is a root or a folder below one; none = every root. */
  abstract scan(paths?: string[]): Promise<void>;
  abstract cancelScan(): Promise<void>;
  abstract getSettings(): Promise<SettingsInfo>;
  abstract setSettings(settings: Settings): Promise<SettingsInfo>;
  abstract facets(filters?: Filters): Promise<Facets>;
  abstract reveal(id: string): Promise<void>;
  /** Child folders of a stored album path (the library tree, not the live disk). */
  abstract folders(path: string): Promise<Folder[]>;
  /** One level of the real file system; null = drives/home. */
  abstract browse(path: string | null): Promise<Listing>;
  abstract library(): Promise<LibraryInfo>;
  /** Resolves once the server accepted; it then restarts and the socket reconnects. */
  abstract switchLibrary(dir: string): Promise<void>;
  abstract forgetLibrary(dir: string): Promise<LibraryInfo>;
  /** Compacts the stores; rejected while a scan runs. */
  abstract optimize(): Promise<void>;

  abstract coverUrl(album: Album): string;
  abstract thumbUrl(album: Album, index: number): string;
  abstract originalUrl(album: Album, index: number): string;
}
