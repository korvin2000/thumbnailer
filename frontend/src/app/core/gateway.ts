import { Observable } from 'rxjs';
import {
  Album, AlbumDetails, AlbumPatch, AlbumQuery, AlbumTarget, Comparison, EntityCard, EntityKind, EntityList, EntityQuery, EntitySave, Facets, Filters,
  Folder, GatewayEvent, LibraryInfo, Listing, MetaRow, Op, PhotoList, PhotoMarks, Preflight, SearchResult, Settings, SettingsInfo,
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

  // ---- catalog ----
  abstract entities(kind: EntityKind, opts?: EntityQuery): Promise<EntityList>;
  abstract entity(id: number): Promise<EntityCard>;
  abstract saveEntity(e: EntitySave): Promise<EntityCard>;
  abstract deleteEntities(ids: number[]): Promise<void>;
  abstract mergeEntities(from: number[], into: number): Promise<EntityCard>;
  abstract confirmEntities(ids: number[]): Promise<void>;
  /** Model credited as `alias` at a studio; her albums there are rewritten to say it. */
  abstract creditAs(modelId: number, studioId: number, alias: string, roles?: string): Promise<EntityCard>;
  /** Resolves with the number of albums changed. */
  abstract bulkUpdate(target: AlbumTarget, patch: AlbumPatch): Promise<number>;
  abstract link(target: AlbumTarget, entityId: number): Promise<number>;
  abstract unlink(target: AlbumTarget, entityId: number): Promise<number>;
  /** Every album with the same content stamp, this one included. */
  abstract copies(albumId: string): Promise<Album[]>;
  abstract compareCopies(a: string, b: string): Promise<Comparison>;

  // ---- photos ----
  /** `lists` replaces the photo's list set; the album's marks come back. */
  abstract markPhoto(albumId: string, name: string, mark: { rating?: number; lists?: number[] }): Promise<PhotoMarks>;
  abstract photos(listId: number, offset: number, limit: number): Promise<PhotoList>;
  abstract photoMeta(albumId: string, index: number): Promise<MetaRow[]>;
  abstract albumText(albumId: string, name: string): Promise<string>;

  // ---- file operations ----
  abstract preflight(op: Op): Promise<Preflight>;
  /** Resolves with the operation id once accepted; progress and the result arrive as events. */
  abstract runOp(op: Op): Promise<string>;
  abstract cancelOp(): Promise<void>;

  abstract coverUrl(album: Album): string;
  abstract thumbUrl(album: Album, index: number): string;
  abstract originalUrl(album: Album, index: number): string;
}
