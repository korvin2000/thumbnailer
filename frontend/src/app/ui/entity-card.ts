import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, untracked } from '@angular/core';
import { CatalogStore } from '../core/catalog-store';

const KIND = { MODEL: 'Model', STUDIO: 'Studio', PHOTOGRAPHER: 'Photographer', GENRE: 'Genre', TAG: 'Hashtag', LIST: 'Favorites list' };

/** One catalog entity in the details panel, loaded through `CatalogStore.openCard`. A shell: name, kind, aliases, album count. */
@Component({
  selector: 'app-entity-card',
  template: `
    @if (catalog.card(); as c) {
      <span class="label">{{ kind[c.entity.kind] }}@if (c.entity.auto) { · unconfirmed }</span>
      <h2>{{ c.entity.name }}</h2>
      @if (c.aliases.length) { <div class="aliases">@for (a of c.aliases; track a) { <span class="chip">{{ a }}</span> }</div> }
      @let albums = c.derived?.albums ?? c.entity.albums;
      <p class="count">{{ albums }} {{ albums === 1 ? 'album' : 'albums' }}</p>
    } @else { <div class="loading"><span class="spinner"></span></div> }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 8px; }
    h2 { font-size: 16px; font-weight: 600; }
    .aliases { display: flex; flex-wrap: wrap; gap: 5px; }
    .count { color: var(--text-2); }
    .loading { display: flex; justify-content: center; padding: 24px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EntityCardView {
  readonly entityId = input.required<number>();
  protected readonly catalog = inject(CatalogStore);
  protected readonly kind = KIND;

  constructor() {
    effect(() => { const id = this.entityId(); untracked(() => this.catalog.openCard(id)); });
    inject(DestroyRef).onDestroy(() => this.catalog.closeCard());
  }
}
