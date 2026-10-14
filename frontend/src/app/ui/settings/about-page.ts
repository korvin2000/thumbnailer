import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AlbumStore } from '../../core/store';

/** Facts from `libraryInfo.about` plus the active locations. */
@Component({
  selector: 'app-about-page',
  template: `
    <h3>Thumbnailer</h3>
    <p class="hint">A local, album-centric organizer for studio photo shoots: folders and zips on disk become browsable, searchable albums.</p>
    @let lib = store.library();
    <dl>
      <dt>Version</dt><dd>{{ lib?.about?.version ?? '…' }}</dd>
      <dt>Java</dt><dd>{{ lib?.about?.java ?? '…' }}</dd>
      <dt>Operating system</dt><dd>{{ lib?.about?.os ?? '…' }}</dd>
      <dt>Home</dt><dd class="mono">{{ lib?.home ?? '…' }}</dd>
      <dt>Library</dt><dd class="mono">{{ lib?.dir ?? store.settings()?.dataDir ?? '…' }}</dd>
      <dt>Stores</dt><dd>H2 (SQL) + TuplDB (covers)</dd>
      <dt>Network</dt><dd>Local only — binds 127.0.0.1, no authentication</dd>
    </dl>
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 18px; max-width: 560px; }
    dl { display: grid; grid-template-columns: 160px 1fr; gap: 10px 16px; margin: 0; font-size: 13px; }
    dt { color: var(--text-3); font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; line-height: 20px; }
    dd { margin: 0; word-break: break-all; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutPage {
  protected readonly store = inject(AlbumStore);
  constructor() { if (!this.store.library()) this.store.loadLibrary(); }
}
