import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { CatalogStore } from '../core/catalog-store';
import { FolderPicker } from '../core/folder-picker';
import { Gateway } from '../core/gateway';
import { Album, AlbumTarget, Entity, EntityKind, Filters, Op, PackFormat, Preflight } from '../core/model';
import { OpsStore } from '../core/ops-store';
import { dir, lastSegment } from '../core/paths';
import { Prefs } from '../core/prefs';
import { SELECT_CAP } from '../core/selection';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import type { EntityTreeContext } from './entity-tree';
import type { FolderMenuEvent } from './folder-tree';
import { NOUN } from './kinds';
import { AlbumActions, albumMenu, allHidden, canvasMenu, EntityActions, entityMenu, FolderActions, folderMenu, plural, Target } from './menus';
import { confirmFor, finalize, KeyState, needsPassword, passwordRequest } from './op-confirm';
import { Confirm } from './shared/confirm';
import { ContextMenu, MenuEntry } from './shared/context-menu';
import { EntityPicker } from './shared/entity-picker';
import type { TileMenuEvent } from './tile-grid';

const strip = (p: string) => p.replace(/\/+$/, '');

/**
 * Every album, folder and entity action behind the menus and keys (catalog_plan.md §7.2, §7.4): bulk edits through `CatalogStore`,
 * file operations as preflight → `Confirm` (summary, options) → `OpsStore.run`, pickers for assign / favorites / targets. The menus
 * themselves are built by `ui/menus.ts` from a `Target` (selection or node query) on every open.
 */
@Injectable({ providedIn: 'root' })
export class Actions implements AlbumActions, FolderActions, EntityActions {
  private readonly store = inject(AlbumStore);
  private readonly catalog = inject(CatalogStore);
  private readonly ops = inject(OpsStore);
  private readonly gw = inject(Gateway);
  private readonly prefs = inject(Prefs);
  private readonly toasts = inject(Toasts);
  private readonly router = inject(Router);
  private readonly menu = inject(ContextMenu);
  private readonly confirm = inject(Confirm);
  private readonly picker = inject(EntityPicker);
  private readonly folders = inject(FolderPicker);

  // ---- targets ----

  /** The albums a grid action applies to (§3.7): the selection when `id` is in it (or none is given), else that album; with nothing selected, the focused one. */
  selectionTarget(id: string | null = null): Target {
    const sel = this.store.selection, focus = this.store.selectedId();
    const ids = id !== null && !sel.has(id) ? [id] : sel.size() ? [...sel.ids()] : focus ? [focus] : [];
    const byId = this.store.byId(), albums = ids.map(x => byId.get(x)).filter((a): a is Album => !!a);
    return { albumIds: ids, albums, complete: albums.length === ids.length, label: ids.length === 1 && albums[0] ? albums[0].name : plural(ids.length, 'album') };
  }

  /** A node's albums (a folder, an entity, Unassigned): the server enumerates them under the 500 cap, the preflight shows the count. */
  queryTarget(query: Filters, label: string): Target { return { query, label, albums: [], complete: false }; }

  private sel(t: Target): AlbumTarget { return t.albumIds ? { albumIds: t.albumIds } : { query: t.query ?? {} }; }

  // ---- menus (one builder per subject, opened at the pointer) ----

  openAlbumMenu(e: TileMenuEvent) {
    this.menu.open(e.x, e.y, e.id === null ? canvasMenu(this) : albumMenu(this.selectionTarget(e.id), this, this.store.byId().get(e.id) ?? null));
  }

  /** The location bar's selection chip (▾), built per open. */
  readonly selectionMenu = (): MenuEntry[] => albumMenu(this.selectionTarget(), this, this.store.selected());

  openFolderMenu(e: FolderMenuEvent) {
    const path = e.path, root = path !== null && this.store.roots().some(r => strip(r.path).toLowerCase() === strip(path).toLowerCase());
    const t = path === null ? this.queryTarget({}, 'all albums') : this.queryTarget({ roots: [path] }, `albums below ${lastSegment(strip(path))}`);
    this.menu.open(e.x, e.y, folderMenu(path, root, t, this));
  }

  /** A child row's albums are its scope (parent ∧ child), not every album of the child. */
  openEntityMenu(c: EntityTreeContext) {
    const e = c.entity, noun = NOUN[c.kind];
    const t = e ? this.queryTarget({ entities: c.scope ?? [e.id] }, `albums of ${e.name}`) : c.missing ? this.queryTarget({ missing: c.kind }, `albums without a ${noun}`) : this.queryTarget({}, 'all albums');
    this.menu.open(c.x, c.y, entityMenu(c, this.store.selection.size(), t, this));
  }

  // ---- keys ----

  /** `H`: hides the selection (else the focused album), or unhides it when every album in it is hidden. */
  toggleHidden() { const t = this.selectionTarget(); if (t.albumIds?.length) void this.hide(t, !allHidden(t)); }
  /** `Delete`. */
  deleteSelection() { const t = this.selectionTarget(); if (t.albumIds?.length) void this.delete(t); }

  // ---- albums ----

  open(id: string) { void this.router.navigate(['/album', id]); }
  details(id: string) { this.store.selectedId.set(id); this.prefs.detailsOpen.set(true); }
  reveal(id: string) { void this.store.reveal(id); }

  async copyText(text: string) {
    try { await navigator.clipboard.writeText(text); this.toasts.show('Copied'); } catch { this.toasts.show(text, 'info', 6000); }
  }

  /** Assign ▸ a role: pick (or create) the entity, then `link` adds its name to the albums' text (a list: membership). */
  async assign(t: Target, kind: EntityKind) {
    const e = await this.picker.pick({ kind, title: `Assign ${NOUN[kind]} to ${t.label}`, allowCreate: true });
    if (!e) return;
    const n = await this.catalog.link(this.sel(t), e.id);
    if (n !== null) this.toasts.show(`${plural(n, 'album')} now ${kind === 'TAG' ? 'tagged #' + e.name : 'with ' + e.name}`, 'success');
  }

  async removeTag(t: Target, entityId: number | null) {
    const id = entityId ?? (await this.picker.pick({ kind: 'TAG', title: `Remove tag from ${t.label}` }))?.id;
    if (id == null) return;
    const n = await this.catalog.unlink(this.sel(t), id);
    if (n !== null) this.toasts.show(`Tag removed from ${plural(n, 'album')}`, 'success');
  }

  async rate(t: Target, rating: number) {
    const n = await this.catalog.bulkUpdate(this.sel(t), { rating });
    if (n !== null) this.toasts.show(rating ? `${plural(n, 'album')} rated ${rating}/10` : `Rating cleared on ${plural(n, 'album')}`, 'success');
  }

  async shotDate(t: Target) {
    const cur = t.complete && t.albums.length === 1 ? t.albums[0].shotDate?.slice(0, 10) ?? '' : '';
    const r = await this.confirm.ask({ title: `Shot date of ${t.label}`, message: 'Overrides the date found in the photos; leave it empty to go back to that date.', okLabel: 'Set', options: [{ key: 'date', label: 'Shot date', kind: 'date', value: cur }] });
    if (!r) return;
    const date = String(r['date'] ?? ''), n = await this.catalog.bulkUpdate(this.sel(t), { date });
    if (n !== null) this.toasts.show(`Shot date ${date ? 'set' : 'reset'} on ${plural(n, 'album')}`, 'success');
  }

  lists() { return this.catalog.loadLists(); }

  async favorite(t: Target, listId: number, on: boolean) {
    const n = await (on ? this.catalog.link(this.sel(t), listId) : this.catalog.unlink(this.sel(t), listId));
    if (n !== null) this.toasts.show(`${plural(n, 'album')} ${on ? 'added to' : 'removed from'} ${this.catalog.entityOf(listId)?.name ?? 'the list'}`, 'success');
  }

  async newList(t: Target) {
    const l = await this.picker.pick({ kind: 'LIST', title: `Add ${t.label} to a favorites list`, allowCreate: true });
    if (l) await this.favorite(t, l.id, true);
  }

  /** The format is sent explicitly even for the default, so the dialog's title names it. */
  pack(t: Target, format = this.packFormat()) { return this.operate({ kind: 'PACK', ...this.sel(t), format, conflict: 'RENAME' }, t.label); }
  packFormat(): PackFormat { return this.store.settings()?.settings.ops?.packFormat ?? 'AUTO'; }
  unpack(t: Target) { return this.operate({ kind: 'UNPACK', ...this.sel(t), conflict: 'RENAME' }, t.label); }
  repack(t: Target, format: PackFormat) { return this.operate({ kind: 'REPACK', ...this.sel(t), format, conflict: 'RENAME' }, t.label); }

  /**
   * Encrypt / decrypt (`needsPassword`, §4.5): encryption runs with the session key once the preflight proved it against an existing `.cry` of
   * the selection; otherwise the password typed twice becomes the session key (saved as `cryPassword`, so the result is readable and re-indexed)
   * and the preflight runs again with it. Decryption without a session key takes the typed password for this operation alone.
   */
  async crypt(t: Target, encrypt: boolean) {
    const op: Op = { kind: 'CRYPT', ...this.sel(t), encrypt, conflict: 'RENAME' };
    let pre: Preflight | null = encrypt ? await this.ops.preflight(op) : null;
    if (encrypt && !pre) return;
    if (needsPassword(op, pre, this.key())) {
      const r = await this.confirm.ask(passwordRequest(op, t.label));
      if (!r) return;
      const password = String(r['password']);
      if (!encrypt) op.password = password;
      else { const s = this.store.settings()?.settings; if (!s || !await this.store.saveSettings({ ...s, cryPassword: password }, 'Session key set')) return; pre = null; }
    }
    await this.operate(op, t.label, pre);
  }

  async moveTo(t: Target) {
    const target = await this.folders.pick({ title: `Move ${t.label} to…`, startAt: this.store.folder() });
    if (target) await this.operate({ kind: 'MOVE', ...this.sel(t), target, conflict: 'RENAME' }, t.label);
  }

  async copyTo(t: Target) {
    const target = await this.folders.pick({ title: `Copy ${t.label} to…`, startAt: this.store.folder() });
    if (target) await this.operate({ kind: 'COPY', ...this.sel(t), target, conflict: 'RENAME' }, t.label);
  }

  /** Hide / unhide = `patch { hidden }` in bulk (no file touched); the albums leave the view, so the selection ends with them and the tree counts reload. */
  async hide(t: Target, hidden: boolean) {
    const n = await this.catalog.bulkUpdate(this.sel(t), { hidden });
    if (n === null) return;
    this.store.selection.clear();
    void this.store.loadSettings(); this.store.treeVersion.update(v => v + 1);
    this.toasts.show(`${plural(n, 'album')} ${hidden ? 'hidden' : 'visible again'}`, 'success');
  }

  delete(t: Target) { return this.operate({ kind: 'DELETE', ...this.sel(t), conflict: 'SKIP' }, t.label); }

  /** `Ctrl+A` / Select all: every loaded album, loading further pages until `cap` or the end (each page joins the selection as it lands); a query change ends it. */
  async selectAll(cap = SELECT_CAP) {
    const s = this.store, q = s.query();
    for (let n = -1; n < s.albums().length && s.query() === q;) {
      n = s.albums().length;
      s.selection.selectAll(s.albums().map(a => a.id), cap);
      if (n >= cap || n >= s.total()) break;
      await s.loadMore();
    }
    if (s.selection.size() >= cap && s.total() > cap) this.toasts.show(`Selected the first ${cap} albums`);
  }

  async selectFirst() {
    const r = await this.confirm.ask({ title: 'Select the first albums', message: `In the current order, at most ${SELECT_CAP}.`, okLabel: 'Select', options: [{ key: 'n', label: 'How many', kind: 'number', value: Math.min(SELECT_CAP, this.store.total() || SELECT_CAP), min: 1, max: SELECT_CAP, required: true }] });
    if (r) await this.selectAll(Number(r['n']));
  }

  deselect() { this.store.selection.clear(); }

  // ---- folders ----

  rescan(path: string | null) { void this.store.startScan(path ? [path] : undefined); }

  /** `reveal` with the folder's path; the first album below it goes along for a server that reveals albums only (it then opens that album's parent). */
  async revealFolder(path: string) {
    try {
      const r = await this.gw.search({ roots: [path], sort: 'NAME', offset: 0, limit: 1 });
      await this.store.reveal(r.albums[0]?.id ?? null, path);
    } catch (e) { this.toasts.error(e, 'Cannot open: '); }
  }

  async renameFolder(path: string) {
    const name = lastSegment(strip(path));
    const r = await this.confirm.ask({ title: `Rename ${name}`, message: path, okLabel: 'Rename', options: [{ key: 'name', label: 'New name', kind: 'text', value: name, required: true }] });
    const to = String(r?.['name'] ?? '').trim();
    if (r && to && to !== name) await this.operate({ kind: 'FOLDER_RENAME', paths: [path], target: to, conflict: 'SKIP' }, `folder ${name}`);
  }

  async moveFolder(path: string) {
    const target = await this.folders.pick({ title: `Move ${lastSegment(strip(path))} to…`, startAt: dir(strip(path)) });
    if (target) await this.operate({ kind: 'FOLDER_MOVE', paths: [path], target, conflict: 'RENAME' }, `folder ${lastSegment(strip(path))}`);
  }

  async copyFolder(path: string) {
    const target = await this.folders.pick({ title: `Copy ${lastSegment(strip(path))} to…`, startAt: dir(strip(path)) });
    if (target) await this.operate({ kind: 'FOLDER_COPY', paths: [path], target, conflict: 'RENAME' }, `folder ${lastSegment(strip(path))}`);
  }

  deleteFolder(path: string) { return this.operate({ kind: 'FOLDER_DELETE', paths: [path], conflict: 'SKIP' }, `folder ${lastSegment(strip(path))}`); }

  // ---- entities ----

  openCard(id: number) { this.catalog.openCard(id); this.prefs.detailsOpen.set(true); }
  showAlbums(c: EntityTreeContext) { this.store.setFilter('missing', c.missing ? c.kind : null); this.store.setFilter('entities', c.entity ? c.scope ?? [c.entity.id] : null); }
  showPhotos(e: Entity) { void this.router.navigate(['/list', e.id]); }

  async renameEntity(e: Entity) {
    const r = await this.confirm.ask({ title: `Rename ${e.name}`, message: 'The old name stays as an alias, so album texts still resolve to it.', okLabel: 'Rename', options: [{ key: 'name', label: 'Name', kind: 'text', value: e.name, required: true }] });
    const name = String(r?.['name'] ?? '').trim();
    if (r && name && name !== e.name && await this.catalog.save({ id: e.id, kind: e.kind, name, rating: e.rating, profile: e.profile ?? null })) this.toasts.show(`Renamed to ${name}`, 'success');
  }

  /** Resolves true when the merge happened (the tree then clears its rows); the scope follows a merged entity to the survivor. */
  async mergeInto(kind: EntityKind, ids: number[]): Promise<boolean> {
    const into = await this.picker.pick({ kind, title: `Merge ${plural(ids.length, NOUN[kind])} into…` });
    const from = into ? ids.filter(id => id !== into.id) : [];
    if (!into || !from.length || !await this.catalog.merge(from, into.id)) return false;
    this.toasts.show(`Merged into ${into.name}`, 'success');
    if (this.store.filters().entities?.some(id => from.includes(id))) this.store.setFilter('entities', [into.id]);
    return true;
  }

  async confirmEntities(kind: EntityKind, ids: number[]): Promise<boolean> {
    const ok = ids.length > 0 && !!await this.catalog.confirm(ids);
    if (ok) this.toasts.show(`${plural(ids.length, NOUN[kind])} confirmed`, 'success');
    return ok;
  }

  async deleteEntities(kind: EntityKind, ids: number[], why = 'Their albums lose this credit; album texts naming them are cleared.'): Promise<boolean> {
    if (!ids.length) return false;
    const ok = await this.confirm.ask({ title: `Delete ${plural(ids.length, NOUN[kind])}?`, message: kind === 'LIST' ? 'The list and its favorites marks go; albums and photos stay.' : why, items: ids.map(id => this.catalog.entityOf(id)?.name ?? `#${id}`), showAll: ids.length <= 60, danger: true, okLabel: 'Delete' });
    if (!ok || !await this.catalog.delete(ids)) return false;
    this.toasts.show(`${plural(ids.length, NOUN[kind])} deleted`, 'success');
    if (this.store.filters().entities?.some(id => ids.includes(id))) this.store.setFilter('entities', null);
    return true;
  }

  /** "Credited as Y at studio X" (§2.5): a studio, the alias (and roles), then `creditAs` rewrites her albums there. */
  async creditAs(e: Entity) {
    const studio = await this.picker.pick({ kind: 'STUDIO', title: `${e.name} is credited at…` });
    if (!studio) return;
    const r = await this.confirm.ask({ title: `${e.name} at ${studio.name}`, message: 'Her albums at this studio are rewritten to the credited name, which becomes an alias of hers.', okLabel: 'Credit',
      options: [{ key: 'alias', label: 'Credited as', kind: 'text', value: e.name, required: true }, { key: 'roles', label: 'Roles', kind: 'text', hint: 'optional, comma-separated' }] });
    const alias = String(r?.['alias'] ?? '').trim();
    if (r && alias && await this.catalog.creditAs(e.id, studio.id, alias, String(r['roles'] ?? '').trim() || undefined)) this.toasts.show(`${e.name} credited as ${alias} at ${studio.name}`, 'success');
  }

  async assignSelection(e: Entity) {
    const ids = [...this.store.selection.ids()];
    if (!ids.length) return;
    const n = await this.catalog.link({ albumIds: ids }, e.id);
    if (n !== null) this.toasts.show(`${plural(n, 'album')} now with ${e.name}`, 'success');
  }

  // ---- operations ----

  private key(): KeyState { return { fingerprint: this.store.settings()?.settings.cryKey ?? null }; }

  /** Every file operation: preflight (`pre` when the caller already has it) → Confirm with its summary and options → run; a move or delete ends the selection (the albums leave). */
  private async operate(op: Op, label: string, pre: Preflight | null = null): Promise<boolean> {
    pre ??= await this.ops.preflight(op);
    if (!pre) return false;
    const r = await this.confirm.ask(confirmFor(op, pre, label, this.key()));
    if (!r) return false;
    const id = await this.ops.run(finalize(op, r));
    if (id && (op.kind === 'DELETE' || op.kind === 'MOVE')) this.store.selection.clear();
    return !!id;
  }
}
