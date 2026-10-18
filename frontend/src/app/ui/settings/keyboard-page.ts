import { ChangeDetectionStrategy, Component } from '@angular/core';

const LIBRARY: [string[], string][] = [
  [['/'], 'Focus the search box'], [['E'], 'Folders tab (sidebar)'], [['F'], 'Refine tab (sidebar)'], [['I'], 'Details panel'], [['T'], 'Light / dark theme'],
  [['[', ']'], 'Fewer / more albums per row'], [['↑', '↓', '←', '→'], 'Move the selection'], [['Home', 'End'], 'First / last album'],
  [['Enter'], 'Open the selected album'], [['Esc'], 'Close the overlaying sidebar (narrow windows), else clear the selection'],
];
const VIEWER: [string[], string][] = [
  [['↑', '↓', '←', '→'], 'Move between photos'], [['Enter'], 'Open the photo full size'], [['Ctrl', 'Shift', 'click'], 'Select several photos'], [['Ctrl', 'A'], 'Select all photos'],
  [['C'], 'Use the photo as cover'], [['F11'], 'Full screen'], [['Space'], 'Slideshow: start / pause'], [['S'], 'Next scale mode (fit, fit large, 1:1, width, height)'],
  [['R'], 'Random order, reshuffled'], [['1', '…', '9', '0'], 'Copy to target 1–10 (Settings → Operations)'], [['Ctrl', '1', '…', '0'], 'Rate 1–10'],
  [['F'], 'Favorites lists'], [['M'], 'Metadata'], [['B'], 'Bio text'], [['Esc'], 'Close the lightbox, clear the selection, then the album'],
];

/** Shortcut sheet; the handlers live in App.onKey, TileGrid.onKey and AlbumViewer.onKey. */
@Component({
  selector: 'app-keyboard-page',
  template: `
    <h3>Keyboard</h3>
    <p class="hint">Shortcuts work when no text field has the focus.</p>
    <section class="card"><h4>Library</h4><table>@for (k of library; track k[1]) { <tr><td>@for (key of k[0]; track key) { <kbd>{{ key }}</kbd> }</td><td>{{ k[1] }}</td></tr> }</table></section>
    <section class="card"><h4>Album viewer</h4><table>@for (k of viewer; track k[1]) { <tr><td>@for (key of k[0]; track key) { <kbd>{{ key }}</kbd> }</td><td>{{ k[1] }}</td></tr> }</table></section>
  `,
  styles: `
    :host { display: block; }
    h3 { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
    :host > .hint { margin-bottom: 14px; }
    table { border-collapse: collapse; margin-top: 6px; font-size: 12.5px; }
    td { padding: 3px 14px 3px 0; vertical-align: middle; color: var(--text-2); }
    td:first-child { width: 120px; white-space: nowrap; }
    kbd { margin-right: 3px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KeyboardPage {
  protected readonly library = LIBRARY;
  protected readonly viewer = VIEWER;
}
