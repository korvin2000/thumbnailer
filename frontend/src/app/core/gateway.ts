import { Observable } from 'rxjs';
import {
  Album, AlbumDetails, AlbumPatch, AlbumQuery, Facets, Filters, GatewayEvent, SearchResult, Settings, SettingsInfo,
} from './model';

/** Transport-agnostic access to the album service; implemented over WebSocket+HTTP and as an in-browser mock. */
export abstract class Gateway {
  abstract readonly events: Observable<GatewayEvent>;
  abstract readonly mock: boolean;

  abstract search(query: AlbumQuery): Promise<SearchResult>;
  abstract album(id: string): Promise<AlbumDetails>;
  abstract update(id: string, patch: AlbumPatch): Promise<Album>;
  abstract scan(roots?: string[]): Promise<void>;
  abstract cancelScan(): Promise<void>;
  abstract getSettings(): Promise<SettingsInfo>;
  abstract setSettings(settings: Settings): Promise<SettingsInfo>;
  abstract facets(filters?: Filters): Promise<Facets>;
  abstract reveal(id: string): Promise<void>;

  abstract coverUrl(album: Album): string;
  abstract thumbUrl(album: Album, index: number): string;
  abstract originalUrl(album: Album, index: number): string;
}
