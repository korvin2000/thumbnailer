import { inject, Injectable, signal } from '@angular/core';
import { Gateway } from './gateway';
import { Op, OpProgress, OpResult, Preflight } from './model';
import { Toasts } from './toasts';

/** File operation state beside `AlbumStore`: the running (or last) operation's progress and its result. */
@Injectable({ providedIn: 'root' })
export class OpsStore {
  private readonly gw = inject(Gateway);
  private readonly toasts = inject(Toasts);

  readonly progress = signal<OpProgress | null>(null);
  /** Kept for a result dialog when something was skipped or failed. */
  readonly lastResult = signal<OpResult | null>(null);

  async preflight(op: Op): Promise<Preflight | null> {
    try { return await this.gw.preflight(op); } catch (e) { this.toasts.error(e, 'Preflight failed: '); return null; }
  }

  /** Resolves with the operation id, or null when the server's gate refused it (toast). */
  async run(op: Op): Promise<string | null> {
    try { return await this.gw.runOp(op); } catch (e) { this.toasts.error(e, 'Cannot start: '); return null; }
  }

  async cancel() {
    try { await this.gw.cancelOp(); } catch (e) { this.toasts.error(e); }
  }

  onProgress(p: OpProgress) { this.progress.set(p); }

  onResult(r: OpResult) {
    this.lastResult.set(r);
    const p = this.progress(), what = p?.id === r.id ? p.kind.charAt(0) + p.kind.slice(1).toLowerCase().replace(/_/g, ' ') : 'Operation';
    const issues = [r.skipped.length && `${r.skipped.length} skipped`, r.failed.length && `${r.failed.length} failed`].filter(Boolean);
    this.toasts.show(`${what}: ${r.done} done${issues.length ? ', ' + issues.join(', ') : ''}`, r.failed.length ? 'error' : 'success', 5000);
  }
}
