import { Injectable, signal } from '@angular/core';

export type SettingsPage = 'library' | 'folders' | 'thumbnails' | 'operations' | 'appearance' | 'keyboard' | 'about';

/** Opens the settings dialog from anywhere (the brand button, the viewer's "Edit targets…", the `thumbnailer:settings` event) without a reference to it: the dialog is loaded lazily and shows the requested page once it exists. */
@Injectable({ providedIn: 'root' })
export class SettingsLink {
  /** The dialog is loaded on the first request and stays mounted. */
  readonly wanted = signal(false);
  /** The page asked for; the dialog takes it and clears it. */
  readonly request = signal<SettingsPage | null>(null);

  open(page: SettingsPage = 'library') { this.wanted.set(true); this.request.set(page); }
}
