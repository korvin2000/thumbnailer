import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, linkedSignal, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BytesPipe, MegapixelsPipe } from '../core/format';
import { Gateway } from '../core/gateway';
import { Album, AlbumPatch } from '../core/model';
import { AlbumStore } from '../core/store';
import { Toasts } from '../core/toasts';
import { Icon } from './shared/icon';
import { Rating } from './shared/rating';

type TextField = 'name' | 'model' | 'studio' | 'photographer' | 'description';

/** Floating panel with everything about one album; every field saves itself on change. */
@Component({
  selector: 'app-details-panel',
  imports: [Icon, Rating, RouterLink, BytesPipe, MegapixelsPipe, DatePipe, DecimalPipe],
  template: `
    <header>
      <span class="label">{{ hovering() ? 'Hovering' : 'Selected' }}</span>
      <span class="state">
        @if (saving()) { <span class="spinner"></span> Saving }
        @else if (saved()) { <app-icon name="check" [size]="13" /> Saved }
      </span>
      <button class="btn icon-btn sm ghost" title="Close (I)" (click)="close.emit()"><app-icon name="x" /></button>
    </header>

    @if (album(); as a) {
      <div class="body">
        <a class="cover" [class.portrait]="a.coverPortrait" [routerLink]="['/album', a.id]" title="Open album">
          <img [src]="gw.coverUrl(a)" [alt]="a.name" />
          <span class="open"><app-icon name="maximize" [size]="14" /> Open album</span>
        </a>

        <input class="input title" [value]="a.name" (change)="save('name', $any($event.target).value)" title="Album name" />

        <div class="path" [title]="a.path"><app-icon [name]="a.kind === 'DIRECTORY' ? 'folder' : 'archive'" [size]="13" /><span>{{ a.path }}</span></div>
        <div class="actions">
          <button class="btn sm" (click)="store.reveal(a.id)"><app-icon name="externalLink" [size]="13" /> Show in folder</button>
          <button class="btn sm" (click)="copy(a.path)"><app-icon name="copy" [size]="13" /> Copy path</button>
        </div>

        <dl class="stats">
          <div><dt>Photos</dt><dd>{{ a.imageCount | number }}</dd></div>
          <div><dt>Size</dt><dd>{{ a.bytes | bytes }}</dd></div>
          <div><dt>Quality</dt><dd>{{ a.avgPixels | mp }}</dd></div>
          <div><dt>Largest</dt><dd>{{ a.maxWidth }}×{{ a.maxHeight }}</dd></div>
          <div><dt>Shot</dt><dd>{{ a.shotDate ? (a.shotDate | date: 'mediumDate') : '—' }}</dd></div>
          <div><dt>Modified</dt><dd>{{ a.modifiedAt | date: 'mediumDate' }}</dd></div>
          <div><dt>Indexed</dt><dd>{{ a.indexedAt | date: 'short' }}</dd></div>
          <div><dt>Cover</dt><dd class="cover-name"><span>{{ a.cover || '—' }}</span>@if (a.coverPinned) { <app-icon name="pin" [size]="11" /> }</dd></div>
        </dl>

        <section>
          <h3>Rating</h3>
          <app-rating [value]="a.rating" (valueChange)="patch({ rating: $event })" />
        </section>

        <section class="fields">
          <label><span><app-icon name="user" [size]="13" /> Model</span><input class="input sm" [value]="a.model ?? ''" (change)="save('model', $any($event.target).value)" list="dl-models" /></label>
          <label><span><app-icon name="building" [size]="13" /> Studio</span><input class="input sm" [value]="a.studio ?? ''" (change)="save('studio', $any($event.target).value)" list="dl-studios" /></label>
          <label><span><app-icon name="camera" [size]="13" /> Photographer</span><input class="input sm" [value]="a.photographer ?? ''" (change)="save('photographer', $any($event.target).value)" list="dl-photographers" /></label>
          <label class="col"><span>Description</span><textarea class="input" rows="3" [value]="a.description ?? ''" (change)="save('description', $any($event.target).value)"></textarea></label>
          <datalist id="dl-models">@for (x of store.facets()?.models; track x.value) { <option [value]="x.value"></option> }</datalist>
          <datalist id="dl-studios">@for (x of store.facets()?.studios; track x.value) { <option [value]="x.value"></option> }</datalist>
          <datalist id="dl-photographers">@for (x of store.facets()?.photographers; track x.value) { <option [value]="x.value"></option> }</datalist>
          <datalist id="dl-tags">@for (x of store.facets()?.tags; track x.value) { <option [value]="x.value"></option> }</datalist>
        </section>

        <section>
          <h3><app-icon name="hash" [size]="13" /> Tags</h3>
          <div class="tags">
            @for (t of tags(); track t) {
              <span class="chip active"><button class="t" (click)="store.toggleTag(t)" title="Filter by this tag">#{{ t }}</button><button (click)="setTags(tags().filter(x => x !== t))" title="Remove tag"><app-icon name="x" [size]="11" /></button></span>
            }
            <input class="input sm tag-input" placeholder="Add tag ⏎" list="dl-tags" (keydown.enter)="addTag($any($event.target)); $event.preventDefault()" />
          </div>
        </section>
      </div>
    } @else {
      <div class="empty"><app-icon name="info" [size]="26" /><p>Select or hover an album to see its details.</p></div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; border-radius: var(--radius-l); overflow: hidden; animation: fade-up 0.2s var(--ease); }
    header { display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 8px 0 14px; border-bottom: 1px solid var(--border); }
    .state { flex: 1; display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: var(--text-3); }
    .body { flex: 1; overflow: auto; padding: 12px 14px 18px; display: flex; flex-direction: column; gap: 10px; }
    .cover { position: relative; display: block; aspect-ratio: 16 / 9; border-radius: var(--radius); overflow: hidden; background: var(--surface-2); }
    .cover.portrait { aspect-ratio: 16 / 9; }
    .cover img { width: 100%; height: 100%; object-fit: contain; background: oklch(10% 0.01 260); }
    .cover .open { position: absolute; right: 8px; bottom: 8px; display: inline-flex; align-items: center; gap: 5px; padding: 4px 8px; border-radius: 6px; background: oklch(10% 0.01 260 / 0.6); color: #fff; font-size: 11px; font-weight: 600; opacity: 0; transition: opacity 0.15s; }
    .cover:hover .open { opacity: 1; }
    .title { font-weight: 600; font-size: 13.5px; }
    .path { display: flex; align-items: center; gap: 6px; color: var(--text-2); font-family: var(--mono); font-size: 11.5px; }
    .path span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
    .actions { display: flex; gap: 6px; }
    .stats { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin: 2px 0; padding: 10px 12px; border-radius: var(--radius); background: var(--surface); }
    .stats div { display: flex; justify-content: space-between; gap: 8px; min-width: 0; }
    dt { color: var(--text-3); font-size: 11.5px; }
    dd { margin: 0; font-weight: 500; font-variant-numeric: tabular-nums; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .cover-name { display: inline-flex; align-items: center; gap: 4px; color: var(--accent); }
    section h3 { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--text-3); }
    .fields { display: flex; flex-direction: column; gap: 6px; }
    .fields label { display: grid; grid-template-columns: 108px 1fr; align-items: center; gap: 8px; font-size: 12px; color: var(--text-2); }
    .fields label.col { grid-template-columns: 1fr; gap: 4px; }
    .fields label > span { display: inline-flex; align-items: center; gap: 6px; }
    .tags { display: flex; flex-wrap: wrap; gap: 5px; align-items: center; }
    .tags .t { font: inherit; color: inherit; }
    .tag-input { width: 110px; }
    .empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; color: var(--text-3); text-align: center; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DetailsPanel {
  readonly album = input<Album | null>(null);
  readonly hovering = input(false);
  readonly close = output<void>();

  protected readonly gw = inject(Gateway);
  protected readonly store = inject(AlbumStore);
  private readonly toasts = inject(Toasts);
  protected readonly saving = signal(false);
  protected readonly saved = linkedSignal<Album | null, boolean>({ source: this.album, computation: () => false });
  /** Local tag list so quick successive edits build on each other instead of on the last acknowledged album. */
  protected readonly tags = linkedSignal(() => this.album()?.tags ?? []);

  protected save(field: TextField, value: string) {
    const a = this.album();
    if (a && (a[field] ?? '') !== value.trim()) this.patch({ [field]: value.trim() });
  }

  protected setTags(tags: string[]) { this.tags.set(tags); this.patch({ tags }); }

  protected addTag(input: HTMLInputElement) {
    const tag = input.value.trim().replace(/^#/, '').toLowerCase();
    input.value = '';
    if (tag && !this.tags().includes(tag)) this.setTags([...this.tags(), tag]);
  }

  protected async patch(patch: AlbumPatch) {
    const a = this.album();
    if (!a) return;
    this.saving.set(true);
    const ok = await this.store.patch(a.id, patch);
    this.saving.set(false);
    if (ok) { this.saved.set(true); setTimeout(() => this.saved.set(false), 2000); }
  }

  protected async copy(text: string) {
    try { await navigator.clipboard.writeText(text); this.toasts.show('Path copied'); }
    catch { this.toasts.show(text, 'info', 6000); }
  }
}
