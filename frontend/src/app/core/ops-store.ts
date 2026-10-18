import { inject, Injectable, signal } from '@angular/core';
import { Gateway } from './gateway';
import { Op, OpProgress, OpResult, Preflight } from './model';
import { Toasts } from './toasts';

type Kind = Pick<Op, 'kind' | 'encrypt'>;
const NOUN: Record<Op['kind'], string> = { PACK: 'Pack', UNPACK: 'Unpack', REPACK: 'Repack', CRYPT: 'Encrypt', MOVE: 'Move', COPY: 'Copy', DELETE: 'Delete', COPY_PHOTOS: 'Copy photos', FOLDER_RENAME: 'Rename folder', FOLDER_MOVE: 'Move folder', FOLDER_COPY: 'Copy folder', FOLDER_DELETE: 'Delete folder' };
const VERB: Record<Op['kind'], string> = { PACK: 'Packing', UNPACK: 'Unpacking', REPACK: 'Repacking', CRYPT: 'Encrypting', MOVE: 'Moving', COPY: 'Copying', DELETE: 'Deleting', COPY_PHOTOS: 'Copying photos', FOLDER_RENAME: 'Renaming folder', FOLDER_MOVE: 'Moving folder', FOLDER_COPY: 'Copying folder', FOLDER_DELETE: 'Deleting folder' };
/** "Pack", "Decrypt", … for titles and the result. */
export function opNoun(op: Kind): string { return op.kind === 'CRYPT' && op.encrypt === false ? 'Decrypt' : NOUN[op.kind]; }
/** "Packing", "Decrypting", … for the status bar. */
export function opVerb(op: Kind): string { return op.kind === 'CRYPT' && op.encrypt === false ? 'Decrypting' : VERB[op.kind]; }

/** File operation state beside `AlbumStore`: the running (or last) operation's progress and its result. */
@Injectable({ providedIn: 'root' })
export class OpsStore {
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);

  readonly progress = signal<OpProgress | null>(null);
  /** The operation this client started last (its kind and `encrypt` word the status bar and the result). */
  readonly current = signal<Op | null>(null);
  readonly lastResult = signal<OpResult | null>(null);
  /** A result with skipped or failed items, until the result dialog took it (a clean result is a toast). */
  readonly issues = signal<OpResult | null>(null);

  async preflight(op: Op): Promise<Preflight | null> {
    try { return await this.gw.preflight(op); } catch (e) { this.toasts.error(e, 'Preflight failed: '); return null; }
  }

  /** Resolves with the operation id, or null when the server's gate refused it (toast). */
  async run(op: Op): Promise<string | null> {
    try { const id = await this.gw.runOp(op); this.current.set(op); return id; } catch (e) { this.toasts.error(e, 'Cannot start: '); return null; }
  }

  async cancel() {
    try { await this.gw.cancelOp(); } catch (e) { this.toasts.error(e); }
  }

  onProgress(p: OpProgress) { this.progress.set(p); }

  /** The word for the operation `p` reports: the one started here when it is the same kind, else the kind alone. */
  opOf(p: Pick<OpProgress, 'kind'>): Kind { const c = this.current(); return c?.kind === p.kind ? c : { kind: p.kind }; }

  onResult(r: OpResult) {
    this.lastResult.set(r);
    if (r.skipped.length || r.failed.length) { this.issues.set(r); return; }
    const p = this.progress(), what = p?.id === r.id ? opNoun(this.opOf(p)) : 'Operation';
    this.toasts.show(`${what}: ${r.done} done`, 'success', 5000);
  }
}
