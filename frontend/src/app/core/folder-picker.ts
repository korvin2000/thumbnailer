import { Injectable, signal } from '@angular/core';

export interface PickRequest { title: string; startAt?: string | null; allowNew?: boolean }

/** Promise-style folder chooser: a caller `pick()`s, the `app-folder-picker` dialog shows while `open()` is set and settles it. */
@Injectable({ providedIn: 'root' })
export class FolderPicker {
  private readonly request = signal<PickRequest | null>(null);
  readonly open = this.request.asReadonly();
  private settle: ((path: string | null) => void) | null = null;

  /** Resolves with the chosen path, or null on cancel; a new request cancels a pending one. */
  pick(req: PickRequest): Promise<string | null> {
    this.settle?.(null);
    return new Promise(resolve => { this.settle = resolve; this.request.set(req); });
  }

  choose(path: string) { this.finish(path); }
  cancel() { this.finish(null); }

  private finish(path: string | null) {
    const s = this.settle;
    this.settle = null;
    this.request.set(null);
    s?.(path);
  }
}
