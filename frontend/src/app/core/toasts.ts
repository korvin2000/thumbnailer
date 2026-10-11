import { Injectable, signal } from '@angular/core';

export interface Toast { id: number; text: string; kind: 'info' | 'success' | 'error' }

@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly list = signal<Toast[]>([]);
  private seq = 0;

  show(text: string, kind: Toast['kind'] = 'info', ms = 3200) {
    const id = ++this.seq;
    this.list.update(l => [...l, { id, text, kind }]);
    setTimeout(() => this.dismiss(id), ms);
  }

  error(e: unknown, prefix = '') {
    this.show(`${prefix}${e instanceof Error ? e.message : String(e)}`, 'error', 5000);
  }

  dismiss(id: number) { this.list.update(l => l.filter(t => t.id !== id)); }
}
