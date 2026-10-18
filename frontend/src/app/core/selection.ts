import { computed, signal } from '@angular/core';

/** Most items one selection holds (an action's albums are capped alike on the server). */
export const SELECT_CAP = 500;

/** Multi-selection keyed by string ids (albums, photos, entity rows): plain click = only this id, Ctrl toggles, Shift ranges from the anchor over the given order. */
export class Selection {
  readonly ids = signal(new Set<string>());
  readonly size = computed(() => this.ids().size);
  /** The last plainly or Ctrl-clicked id; Shift ranges start here. */
  private anchor: string | null = null;

  has(id: string) { return this.ids().has(id); }

  click(id: string, order: string[], mods: { ctrl?: boolean; shift?: boolean } = {}) {
    const from = this.anchor === null ? -1 : order.indexOf(this.anchor), to = order.indexOf(id);
    if (mods.shift && from >= 0 && to >= 0) {
      const range = order.slice(Math.min(from, to), Math.max(from, to) + 1);
      return this.put(mods.ctrl ? [...this.ids(), ...range] : range); // the anchor stays, so the next Shift+click re-ranges from it
    }
    if (mods.ctrl) { const next = new Set(this.ids()); if (!next.delete(id)) next.add(id); this.put(next); }
    else this.put([id]);
    this.anchor = id;
  }

  selectAll(order: string[], cap = SELECT_CAP) { this.put(order.slice(0, Math.min(cap, SELECT_CAP))); }
  selectFirst(order: string[], n: number) { this.selectAll(order, n); }
  clear() { this.ids.set(new Set()); this.anchor = null; }
  set(ids: Iterable<string>) { this.put(ids); }

  /** Every write goes through the cap: the first `SELECT_CAP` ids win. */
  private put(ids: Iterable<string>) { this.ids.set(new Set([...new Set(ids)].slice(0, SELECT_CAP))); }
}
