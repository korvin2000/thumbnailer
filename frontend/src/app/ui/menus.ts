import { Album, Entity, EntityKind, Filters, PackFormat } from '../core/model';
import type { EntityTreeContext } from './entity-tree';
import { KIND_ICON, NOUN } from './kinds';
import { FORMAT_LABEL } from './op-confirm';
import type { MenuEntry, MenuItem } from './shared/context-menu';

/**
 * Pure menu builders (catalog_plan.md §7.2, §7.4), one per subject, rebuilt from current state on every open. The callbacks live in
 * `ui/actions.ts`; here only labels, order, what is disabled, checked or dangerous. Angular-free.
 */

/** What an album action applies to: explicit ids, or a query (a folder's or an entity's albums); `albums` = the loaded ones among them (facts for labels, every one when `complete`), `label` names it in dialog titles ("7 albums", "albums below 2019"). */
export interface Target { albumIds?: string[]; query?: Filters; label: string; albums: Album[]; complete: boolean }

export interface AlbumActions {
  open(id: string): void; details(id: string): void; reveal(id: string): void; copyText(text: string): void;
  assign(t: Target, kind: EntityKind): void; removeTag(t: Target, entityId: number | null): void; rate(t: Target, rating: number): void; shotDate(t: Target): void;
  lists(): Promise<Entity[]>; favorite(t: Target, listId: number, on: boolean): void; newList(t: Target): void;
  pack(t: Target, format: PackFormat): void; unpack(t: Target): void; repack(t: Target, format: PackFormat): void; crypt(t: Target, encrypt: boolean): void;
  moveTo(t: Target): void; copyTo(t: Target): void; hide(t: Target, hidden: boolean): void; delete(t: Target): void;
  selectAll(): void; selectFirst(): void; deselect(): void;
}
export interface FolderActions { rescan(path: string | null): void; revealFolder(path: string): void; copyText(text: string): void; renameFolder(path: string): void; moveFolder(path: string): void; copyFolder(path: string): void; deleteFolder(path: string): void }
export interface EntityActions {
  openCard(id: number): void; showAlbums(c: EntityTreeContext): void; showPhotos(e: Entity): void; renameEntity(e: Entity): void; mergeInto(kind: EntityKind, ids: number[]): void;
  confirmEntities(kind: EntityKind, ids: number[]): void; deleteEntities(kind: EntityKind, ids: number[]): void; creditAs(e: Entity): void; assignSelection(e: Entity): void;
}

const SEP: MenuEntry = { sep: true };
const PACK_FORMATS: PackFormat[] = ['AUTO', 'ZIP', 'SEVEN_ZIP'];
export const plural = (n: number, noun: string) => `${n} ${noun}${n === 1 ? '' : 's'}`;

/** "Pack (3 of 7 already packed)": how many known albums the operation skips; disabled when every one does; a query target (albums unknown) says nothing. */
function counted(label: string, t: Target, skips: (a: Album) => boolean, reason: string): Pick<MenuItem, 'label' | 'disabled'> {
  if (!t.complete || !t.albums.length) return { label };
  const n = t.albums.filter(skips).length;
  return n ? { label: `${label} (${n} of ${t.albums.length} ${reason})`, disabled: n === t.albums.length } : { label };
}

/** True when every known album is hidden (the Hidden view's query counts too): the menu offers Unhide. */
export function allHidden(t: Target): boolean { return !!t.query?.hidden || (t.albums.length > 0 && t.albums.every(a => a.hidden)); }

/** Pack ▸ · Unpack · Repack ▸ · Encrypt · Decrypt. */
export function opsItems(t: Target, a: AlbumActions): MenuEntry[] {
  return [
    { ...counted('Pack', t, x => x.kind !== 'DIRECTORY', 'already packed'), icon: 'archive', children: PACK_FORMATS.map(f => ({ label: FORMAT_LABEL[f], run: () => a.pack(t, f) })) },
    { ...counted('Unpack', t, x => x.kind === 'DIRECTORY', 'already unpacked'), icon: 'folderOpen', run: () => a.unpack(t) },
    { ...counted('Repack', t, x => x.kind === 'DIRECTORY', 'not packed'), icon: 'refresh', children: [{ label: 'zip', run: () => a.repack(t, 'ZIP') }, { label: '7z', run: () => a.repack(t, 'SEVEN_ZIP') }] },
    { label: 'Encrypt (.cry)', icon: 'lock', run: () => a.crypt(t, true) },
    { label: 'Decrypt', icon: 'unlock', run: () => a.crypt(t, false) },
  ];
}

/** Assign ▸: pickers per role, tags (add / remove the known ones), rating, shot date. */
export function assignItems(t: Target, a: AlbumActions): MenuEntry[] {
  const tags = new Map<number, string>();
  for (const x of t.albums) for (const l of x.links ?? []) if (l.role === 'TAG') tags.set(l.entity, l.name);
  const rating = t.complete && t.albums.length && t.albums.every(x => x.rating === t.albums[0].rating) ? t.albums[0].rating : -1;
  return [
    { label: 'Model…', icon: 'user', run: () => a.assign(t, 'MODEL') },
    { label: 'Studio…', icon: 'building', run: () => a.assign(t, 'STUDIO') },
    { label: 'Photographer…', icon: 'camera', run: () => a.assign(t, 'PHOTOGRAPHER') },
    { label: 'Genre…', icon: 'sparkles', run: () => a.assign(t, 'GENRE') },
    { label: 'Add tag…', icon: 'hash', run: () => a.assign(t, 'TAG') },
    { label: 'Remove tag', icon: 'hash', children: [...[...tags].map(([id, name]) => ({ label: '#' + name, run: () => a.removeTag(t, id) })), ...(tags.size ? [SEP] : []), { label: 'Other…', run: () => a.removeTag(t, null) }] },
    { label: 'Rating', icon: 'star', children: [{ label: 'Unrated', checked: rating === 0, run: () => a.rate(t, 0) }, ...Array.from({ length: 10 }, (_, i) => ({ label: `${i + 1}`, checked: rating === i + 1, run: () => a.rate(t, i + 1) }))] },
    { label: 'Shot date…', icon: 'calendar', run: () => a.shotDate(t) },
  ];
}

/** Favorites ▸: every list, checked when every known album is in it; New list…. */
export function favoritesItems(t: Target, lists: Entity[], a: AlbumActions): MenuEntry[] {
  const inAll = (id: number) => t.complete && t.albums.length > 0 && t.albums.every(x => (x.links ?? []).some(l => l.entity === id));
  return [...lists.map(l => ({ label: l.name, checked: inAll(l.id), run: () => a.favorite(t, l.id, !inAll(l.id)) })), ...(lists.length ? [SEP] : []), { label: 'New list…', icon: 'plus', run: () => a.newList(t) }];
}

/** The album submenu of a folder or entity node ("Albums below ▸", "Its albums ▸"): the operations, move/copy/hide, Assign ▸, Favorites ▸ over the node's query. */
export function albumsSubmenu(t: Target, a: AlbumActions): MenuEntry[] {
  const hidden = allHidden(t);
  return [
    ...opsItems(t, a), SEP,
    { label: 'Move to…', icon: 'arrowRight', run: () => a.moveTo(t) },
    { label: 'Copy to…', icon: 'copy', run: () => a.copyTo(t) },
    { label: hidden ? 'Unhide' : 'Hide', icon: hidden ? 'eye' : 'eyeOff', run: () => a.hide(t, !hidden) },
    SEP,
    { label: 'Assign', icon: 'tag', children: assignItems(t, a) },
    { label: 'Favorites', icon: 'heart', children: () => a.lists().then(l => favoritesItems(t, l, a)) },
  ];
}

function selectionItems(a: AlbumActions): MenuEntry[] {
  return [{ label: 'Select all', keys: 'Ctrl+A', run: () => a.selectAll() }, { label: 'Select first N…', run: () => a.selectFirst() }, { label: 'Deselect', keys: 'Esc', run: () => a.deselect() }];
}

/** Right-click on the empty canvas. */
export function canvasMenu(a: AlbumActions): MenuEntry[] { return selectionItems(a); }

/** §7.4: the grid's menu for `t` (the selection when the clicked tile is in it, else that tile); `focused` = the one album Open/Details/Show in folder act on. */
export function albumMenu(t: Target, a: AlbumActions, focused: Album | null): MenuEntry[] {
  const count = t.albumIds?.length ?? t.albums.length, hidden = allHidden(t);
  return [
    ...(count > 1 ? [{ label: `${plural(count, 'album')} selected`, disabled: true } as MenuEntry, SEP] : []),
    { label: 'Open', icon: 'maximize', keys: 'Enter', disabled: !focused, run: () => a.open(focused!.id) },
    { label: 'Details', icon: 'info', keys: 'I', disabled: !focused, run: () => a.details(focused!.id) },
    { label: 'Show in folder', icon: 'externalLink', disabled: !focused, run: () => a.reveal(focused!.id) },
    { label: 'Copy path', icon: 'copy', disabled: !focused, run: () => a.copyText(focused!.path) },
    SEP,
    { label: 'Assign', icon: 'tag', children: assignItems(t, a) },
    { label: 'Favorites', icon: 'heart', children: () => a.lists().then(l => favoritesItems(t, l, a)) },
    SEP,
    ...opsItems(t, a),
    SEP,
    { label: 'Move to…', icon: 'arrowRight', run: () => a.moveTo(t) },
    { label: 'Copy to…', icon: 'copy', run: () => a.copyTo(t) },
    { label: hidden ? 'Unhide' : 'Hide', icon: hidden ? 'eye' : 'eyeOff', keys: 'H', run: () => a.hide(t, !hidden) },
    { label: 'Delete…', icon: 'trash', keys: 'Del', danger: true, run: () => a.delete(t) },
    SEP,
    ...selectionItems(a),
  ];
}

/** §7.2 folder tree: the Library row offers a rescan only; a root cannot be moved or deleted (the server skips it), renaming one rebases the library. `t` = the albums below the folder. */
export function folderMenu(path: string | null, root: boolean, t: Target, a: FolderActions & AlbumActions): MenuEntry[] {
  if (path === null) return [{ label: 'Rescan all folders', icon: 'refresh', run: () => a.rescan(null) }];
  return [
    { label: 'Rescan', icon: 'refresh', run: () => a.rescan(path) },
    { label: 'Open in Explorer', icon: 'externalLink', run: () => a.revealFolder(path) },
    { label: 'Copy path', icon: 'copy', run: () => a.copyText(path) },
    SEP,
    { label: 'Rename…', icon: 'edit', run: () => a.renameFolder(path) },
    { label: 'Move to…', icon: 'arrowRight', disabled: root, run: () => a.moveFolder(path) },
    { label: 'Copy to…', icon: 'copy', run: () => a.copyFolder(path) },
    { label: 'Delete…', icon: 'trash', danger: true, disabled: root, run: () => a.deleteFolder(path) },
    SEP,
    { label: 'Albums below', icon: 'images', children: albumsSubmenu(t, a) },
  ];
}

/** §7.2 entity tree: the row's entity (the batch when the row is one of several selected rows); All / Unassigned rows only scope the grid and offer their albums. `selectedAlbums` = the grid's selection size. `t` = the row's albums. */
export function entityMenu(c: EntityTreeContext, selectedAlbums: number, t: Target, a: EntityActions & AlbumActions): MenuEntry[] {
  const e = c.entity, noun = NOUN[c.kind];
  if (!e) return [{ label: 'Show albums', icon: 'images', run: () => a.showAlbums(c) }, SEP, { label: 'Albums', icon: 'images', children: albumsSubmenu(t, a) }];
  const ids = c.selected.includes(e.id) ? c.selected : [e.id], many = ids.length > 1;
  return [
    ...(many ? [{ label: `${plural(ids.length, noun)} selected`, disabled: true } as MenuEntry, SEP] : []),
    { label: 'Open card', icon: KIND_ICON[c.kind], run: () => a.openCard(e.id) },
    { label: 'Show albums', icon: 'images', run: () => a.showAlbums(c) },
    ...(c.kind === 'LIST' ? [{ label: 'Show photos', icon: 'image', run: () => a.showPhotos(e) } as MenuEntry] : []),
    SEP,
    { label: 'Rename…', icon: 'edit', disabled: many, run: () => a.renameEntity(e) },
    { label: 'Merge into…', icon: 'layers', run: () => a.mergeInto(c.kind, ids) },
    { label: 'Confirm', icon: 'check', disabled: !many && !e.auto, run: () => a.confirmEntities(c.kind, ids) },
    ...(c.kind === 'MODEL' ? [{ label: 'Credited as…', icon: 'building', disabled: many, run: () => a.creditAs(e) } as MenuEntry] : []),
    { label: `Delete ${many ? plural(ids.length, noun) : noun}…`, icon: 'trash', danger: true, run: () => a.deleteEntities(c.kind, ids) },
    SEP,
    { label: selectedAlbums ? `Assign to ${plural(selectedAlbums, 'selected album')}` : 'Assign to selected albums', icon: 'tag', disabled: !selectedAlbums || many, run: () => a.assignSelection(e) },
    SEP,
    { label: 'Its albums', icon: 'images', children: albumsSubmenu(t, a) },
  ];
}
