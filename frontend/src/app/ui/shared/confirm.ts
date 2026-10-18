import { afterRenderEffect, ChangeDetectionStrategy, Component, computed, ElementRef, inject, Injectable, linkedSignal, signal, untracked, viewChild } from '@angular/core';
import { BytesPipe } from '../../core/format';
import { Preflight } from '../../core/model';
import { Icon } from './icon';

/** A control in the dialog; its value comes back under `key` (`password2`: asked twice, OK only when both agree; `text`/`number`/`date`: one input, `required` blocks OK while empty). */
export interface ConfirmOption { key: string; label: string; kind: 'select' | 'check' | 'password2' | 'text' | 'number' | 'date'; choices?: string[]; value?: unknown; hint?: string; required?: boolean; min?: number; max?: number }
export type ConfirmResult = Record<string, unknown>;
/** `cancelLabel` null = no Cancel button (a dialog that only informs). */
export interface ConfirmRequest { title: string; message?: string; items?: string[]; showAll?: boolean; summary?: Preflight | null; danger?: boolean; okLabel?: string; cancelLabel?: string | null; options?: ConfirmOption[] }

/** Promise-style confirmation every operation goes through; `app-confirm` shows while `open()` is set. */
@Injectable({ providedIn: 'root' })
export class Confirm {
  private readonly request = signal<ConfirmRequest | null>(null);
  readonly open = this.request.asReadonly();
  private settle: ((r: ConfirmResult | null) => void) | null = null;

  /** Resolves with the option values (`{}` without options) on OK, null on cancel; a new question cancels a pending one. */
  ask(opts: ConfirmRequest): Promise<ConfirmResult | null> {
    this.settle?.(null);
    return new Promise(resolve => { this.settle = resolve; this.request.set(opts); });
  }

  finish(result: ConfirmResult | null) {
    const s = this.settle;
    this.settle = null;
    this.request.set(null);
    s?.(result);
  }
}

const SHOWN = 10;

@Component({
  selector: 'app-confirm',
  imports: [Icon, BytesPipe],
  template: `
    <dialog #dlg (cancel)="$event.preventDefault(); confirm.finish(null)">
      @if (confirm.open(); as q) {
        <form (submit)="$event.preventDefault(); ok()">
          <header><app-icon [name]="q.danger ? 'alert' : 'info'" [size]="16" [class.danger]="q.danger" /><h2>{{ q.title }}</h2></header>
          <div class="body">
            @if (q.message) { <p>{{ q.message }}</p> }
            @if (q.items?.length) {
              <ul class="items" [class.all]="q.showAll">
                @for (it of shown(); track $index) { <li class="mono">{{ it }}</li> }
                @if (more()) { <li class="more">and {{ more() }} more</li> }
              </ul>
            }
            @if (q.summary; as s) {
              <div class="summary">
                <div class="totals" [class.none]="!todo()"><b>{{ todo() }}</b> of {{ s.items.length }} to do · {{ s.bytes | bytes }}@if (s.free >= 0) { · {{ s.free | bytes }} free@if (s.bytes > s.free) { <span class="warn"> — not enough space</span> } }</div>
                @for (x of notedShown(); track $index) { <div class="dim"><span class="mono">{{ x.path }}</span> — {{ x.note }}</div> }
                @for (x of skippedShown(); track $index) { <div class="dim skip"><span class="mono">{{ x.path }}</span> — skipped: {{ x.reason ?? 'no reason given' }}</div> }
                @if (omitted()) { <div class="dim">and {{ omitted() }} more</div> }
                @for (c of s.consequences; track $index) { <div class="cons"><app-icon name="arrowRight" [size]="13" /> {{ c }}</div> }
                @for (w of s.warnings; track $index) { <div class="warn"><app-icon name="alert" [size]="13" /> {{ w }}</div> }
              </div>
            }
            @for (o of q.options ?? []; track o.key) {
              @switch (o.kind) {
                @case ('select') {
                  <label class="field"><span>{{ o.label }}</span>
                    <select class="input sm" (change)="set(o.key, $any($event.target).value)">@for (c of o.choices ?? []; track c) { <option [value]="c" [selected]="c === values()[o.key]">{{ c }}</option> }</select></label>
                }
                @case ('check') { <label class="check"><input type="checkbox" [checked]="!!values()[o.key]" (change)="set(o.key, $any($event.target).checked)" /> {{ o.label }}</label> }
                @case ('password2') {
                  <label class="field"><span>{{ o.label }}</span><input class="input sm" type="password" autocomplete="new-password" autofocus (input)="set(o.key, $any($event.target).value)" /></label>
                  <label class="field"><span>Repeat</span><input class="input sm" type="password" autocomplete="new-password" (input)="set(o.key, $any($event.target).value, repeat)" /></label>
                }
                @default {
                  <label class="field"><span>{{ o.label }}</span><input class="input sm" [type]="o.kind" [value]="values()[o.key] ?? ''" [placeholder]="o.hint ?? ''" [attr.min]="o.min" [attr.max]="o.max" autofocus spellcheck="false" autocomplete="off"
                                                                     (input)="set(o.key, o.kind === 'number' ? $any($event.target).valueAsNumber : $any($event.target).value)" /></label>
                }
              }
            }
          </div>
          <footer>
            @if (hint(); as hint) { <span class="hint muted">{{ hint }}</span> }
            @if (q.cancelLabel !== null) { <button type="button" class="btn sm" [attr.autofocus]="q.danger ? '' : null" (click)="confirm.finish(null)">{{ q.cancelLabel ?? 'Cancel' }}</button> }
            <button type="submit" class="btn sm" [class.primary]="!q.danger" [class.danger]="q.danger" [attr.autofocus]="q.danger ? null : ''" [disabled]="blocked()">{{ q.okLabel ?? 'OK' }}</button>
          </footer>
        </form>
      }
    </dialog>
  `,
  styles: `
    dialog { width: min(520px, 92vw); max-height: 86vh; padding: 0; border: 1px solid var(--border); border-radius: var(--radius-l); background: var(--bg-elev); color: var(--text); box-shadow: var(--shadow-2); }
    dialog::backdrop { background: oklch(5% 0.01 260 / 0.45); backdrop-filter: blur(2px); }
    dialog[open] { animation: fade-up 0.18s var(--ease); }
    form { display: flex; flex-direction: column; max-height: 86vh; }
    header { display: flex; align-items: center; gap: 10px; padding: 14px 16px 6px; }
    header app-icon { color: var(--accent); }
    header app-icon.danger { color: var(--danger); }
    h2 { font-size: 14px; font-weight: 600; }
    .body { display: flex; flex-direction: column; gap: 10px; padding: 6px 16px 12px; overflow: auto; font-size: 12.5px; color: var(--text-2); }
    .items { margin: 0; padding: 6px 10px; list-style: none; border-radius: var(--radius-s); background: var(--surface); border: 1px solid var(--border); }
    .items.all { max-height: 40vh; overflow: auto; }
    .items li { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; line-height: 1.7; }
    .items .more { color: var(--text-3); }
    .summary { display: flex; flex-direction: column; gap: 3px; padding: 8px 10px; border-radius: var(--radius-s); background: var(--surface); border: 1px solid var(--border); }
    .totals b { color: var(--text); font-weight: 600; }
    .totals.none b { color: var(--danger); }
    .dim { color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .dim.skip .mono { text-decoration: line-through; }
    .cons { display: flex; align-items: center; gap: 6px; color: var(--text); }
    .warn { display: inline-flex; align-items: center; gap: 6px; color: var(--danger); }
    footer { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--border); }
    footer .hint { flex: 1; }
    .btn.danger { background: var(--danger); border-color: var(--danger); color: var(--on-accent); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  protected readonly confirm = inject(Confirm);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  /** Option values, seeded per question: the given value, else the first choice / unchecked / empty. */
  protected readonly values = linkedSignal<ConfirmResult>(() => Object.fromEntries((this.confirm.open()?.options ?? [])
    .map(o => [o.key, o.value ?? (o.kind === 'select' ? o.choices?.[0] : o.kind === 'check' ? false : '')])));
  protected readonly repeat = linkedSignal({ source: this.confirm.open, computation: (): ConfirmResult => ({}) });
  protected readonly shown = computed(() => { const q = this.confirm.open(), items = q?.items ?? []; return q?.showAll ? items : items.slice(0, SHOWN); });
  protected readonly more = computed(() => (this.confirm.open()?.items?.length ?? 0) - this.shown().length);
  protected readonly skipped = computed(() => this.confirm.open()?.summary?.items.filter(i => i.skip) ?? []);
  protected readonly noted = computed(() => this.confirm.open()?.summary?.items.filter(i => !i.skip && i.note) ?? []);
  /** Notes and skips: `SHOWN` of each, every one when `showAll` (a deletion must say all it touches); `omitted` counts what the cut hid. */
  protected readonly notedShown = computed(() => this.confirm.open()?.showAll ? this.noted() : this.noted().slice(0, SHOWN));
  protected readonly skippedShown = computed(() => this.confirm.open()?.showAll ? this.skipped() : this.skipped().slice(0, SHOWN));
  protected readonly omitted = computed(() => this.noted().length - this.notedShown().length + this.skipped().length - this.skippedShown().length);
  protected readonly todo = computed(() => { const s = this.confirm.open()?.summary; return s ? s.items.length - this.skipped().length : 1; });
  /** Why OK is unavailable: a password typed once or twice differently, a required value missing or out of range, or a preflight that leaves nothing to do. */
  protected readonly hint = computed(() => {
    const options = this.confirm.open()?.options ?? [], pw = options.find(o => o.kind === 'password2'), v = this.values();
    if (pw && !v[pw.key]) return 'Enter the password twice';
    if (pw && v[pw.key] !== this.repeat()[pw.key]) return 'The passwords differ';
    for (const o of options) {
      const x = v[o.key], empty = x === '' || x == null || (typeof x === 'number' && isNaN(x));
      if (o.required && empty) return `Enter ${o.label.toLowerCase()}`;
      if (o.kind === 'number' && !empty && ((o.min != null && (x as number) < o.min) || (o.max != null && (x as number) > o.max))) return `${o.label} must be ${o.min ?? '…'}–${o.max ?? '…'}`;
    }
    return this.todo() ? null : 'Nothing to do';
  });
  protected readonly blocked = computed(() => this.hint() !== null);

  constructor() {
    afterRenderEffect(() => {
      const q = this.confirm.open(), dlg = this.dialog().nativeElement;
      untracked(() => { if (q && !dlg.open) dlg.showModal(); else if (!q && dlg.open) dlg.close(); });
    });
  }

  protected set(key: string, value: unknown, into = this.values) { into.update(v => ({ ...v, [key]: value })); }
  protected ok() { if (!this.blocked()) this.confirm.finish({ ...this.values() }); }
}
