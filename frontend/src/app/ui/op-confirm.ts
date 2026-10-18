import { Op, PackFormat, Preflight } from '../core/model';
import { opNoun } from '../core/ops-store';
import type { ConfirmOption, ConfirmRequest, ConfirmResult } from './shared/confirm';

export { opNoun, opVerb } from '../core/ops-store';

/** Pure: what the confirm dialog asks for each file operation (catalog_plan.md §4.5), and the operation the answers make. Angular-free. */

export const FORMAT_LABEL: Record<PackFormat, string> = { AUTO: 'zip (auto)', ZIP: 'zip', SEVEN_ZIP: '7z' };
/** Conflict policy choices as the dialog shows them; the first is the default (`RENAME`). */
export const CONFLICT_CHOICES = ['Rename the result', 'Skip it'];

/** The session `.cry` key the server holds, by its fingerprint; null = none (encryption asks for a password first). */
export interface KeyState { fingerprint: string | null }

const conflictOption = (op: Op): ConfirmOption => ({ key: 'conflict', label: 'A file or folder has the name', kind: 'select', choices: CONFLICT_CHOICES, value: CONFLICT_CHOICES[op.conflict === 'SKIP' ? 1 : 0] });
const NO_BIN = 'There is no Recycle Bin: deletion is final. Hide (H) keeps the files and only removes the album from the views.';

/**
 * The confirm request for `op` once its preflight is in: title with the target (`label` names what the operation runs on, e.g. "7 albums"),
 * the preflight summary, the conflict policy where a name can collide, danger and the whole item list for deletions, the key note for
 * encryption. `COPY_PHOTOS` is the viewer's own dialog (`AlbumViewer.copyTo`).
 */
export function confirmFor(op: Op, pre: Preflight, label: string, key: KeyState = { fingerprint: null }): ConfirmRequest {
  const todo = pre.items.filter(i => !i.skip).map(i => i.path), conflict = [conflictOption(op)];
  switch (op.kind) {
    case 'PACK': return { title: `Pack ${label} as ${FORMAT_LABEL[op.format ?? 'AUTO']}`, summary: pre, okLabel: 'Pack', options: conflict };
    case 'UNPACK': return { title: `Unpack ${label}`, summary: pre, okLabel: 'Unpack', options: conflict };
    case 'REPACK': return { title: `Repack ${label} as ${FORMAT_LABEL[op.format ?? 'ZIP']}`, summary: pre, okLabel: 'Repack', options: conflict };
    case 'CRYPT': return { title: `${opNoun(op)} ${label}`, message: key.fingerprint ? `Using the session key (fingerprint ${key.fingerprint}).` : undefined, summary: pre, okLabel: opNoun(op) };
    case 'MOVE': return { title: `Move ${label} to ${op.target}`, summary: pre, okLabel: 'Move', options: conflict };
    case 'COPY': return { title: `Copy ${label} to ${op.target}`, summary: pre, okLabel: 'Copy', options: conflict };
    case 'DELETE': return { title: `Delete ${label}?`, message: NO_BIN, items: todo, showAll: true, summary: pre, danger: true, okLabel: 'Delete' };
    case 'COPY_PHOTOS': return { title: `Copy ${label} to ${op.target}`, items: op.names, summary: pre, okLabel: 'Copy', options: conflict };
    case 'FOLDER_RENAME': return { title: `Rename ${label} to ${op.target}`, summary: pre, okLabel: 'Rename' };
    case 'FOLDER_MOVE': return { title: `Move ${label} to ${op.target}`, summary: pre, okLabel: 'Move', options: conflict };
    case 'FOLDER_COPY': return { title: `Copy ${label} to ${op.target}`, summary: pre, okLabel: 'Copy', options: conflict };
    case 'FOLDER_DELETE': return { title: `Delete ${label}?`, message: `Every album below it is deleted from disk. ${NO_BIN}`, items: todo, showAll: true, summary: pre, danger: true, okLabel: 'Delete' };
  }
}

/**
 * Whether the password must be typed (twice) before `op` runs (catalog_plan.md §4.5): encryption unless its preflight proved the session key
 * against an existing `.cry` of the selection (`keyVerified`; a session key alone is no proof, it may be mistyped), decryption only when the server
 * holds no key (a wrong one just skips the items as unreadable).
 */
export function needsPassword(op: Op, pre: Preflight | null, key: KeyState): boolean {
  return op.encrypt !== false ? !pre?.keyVerified : !key.fingerprint;
}

/** The password, typed twice: for encryption it becomes the session key (`Actions.crypt` saves it as `cryPassword`), for decryption this operation's key. */
export function passwordRequest(op: Op, label: string): ConfirmRequest {
  return { title: `${opNoun(op)} ${label}`, message: op.encrypt ? 'The password becomes the session .cry key (kept in server memory only, never on disk) and encrypts these files; the next scans read them with it.' : 'The password the files were encrypted with.', okLabel: 'Continue', options: [{ key: 'password', label: 'Password', kind: 'password2' }] };
}

/** The operation to run after the dialog: the chosen conflict policy (and a typed password) applied to `op`. */
export function finalize(op: Op, r: ConfirmResult): Op {
  const out = { ...op };
  if (typeof r['conflict'] === 'string') out.conflict = r['conflict'] === CONFLICT_CHOICES[1] ? 'SKIP' : 'RENAME';
  if (typeof r['password'] === 'string' && r['password']) out.password = r['password'];
  return out;
}
