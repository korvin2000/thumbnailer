import { ChangeDetectionStrategy, Component } from '@angular/core';

const LIBRARY: [string[], string][] = [
  [['/'], 'Focus the search box'], [['E'], 'Browse tab (sidebar)'], [['F'], 'Refine tab (sidebar)'], [['I'], 'Details panel'], [['T'], 'Light / dark theme'],
  [['[', ']'], 'Fewer / more albums per row'], [['↑', '↓', '←', '→'], 'Move the focus (selects that album)'], [['Home', 'End'], 'First / last album'],
  [['Shift', '↑↓←→'], 'Extend the selection'], [['Ctrl', 'click'], 'Add to / remove from the selection'], [['Shift', 'click'], 'Select the range'],
  [['Ctrl', 'Space'], 'Toggle the focused album'], [['Ctrl', 'A'], 'Select all loaded albums (up to 500)'], [['Shift', 'F10'], 'Menu for the focused album'],
  [['H'], 'Hide the selected albums (unhide when all are hidden)'], [['Delete'], 'Delete the selected albums…'],
  [['Enter'], 'Open the focused album'], [['Esc'], 'Clear the selection; then close the overlaying sidebar (narrow windows), else drop the focus'],
];
const VIEWER: [string[], string][] = [
  [['↑', '↓', '←', '→'], 'Move between photos'], [['Enter'], 'Open the photo full size'], [['Ctrl', 'Shift', 'click'], 'Select several photos'], [['Ctrl', 'A'], 'Select all photos'],
  [['C'], 'Use the photo as cover'], [['[', ']'], 'Fewer / more photos per row'], [['F11'], 'Full screen'], [['Space'], 'Slideshow: start / pause'], [['S'], 'Next scale mode (fit, fit large, 1:1, width, height)'],
  [['<', '>'], 'Rotate the photo left / right (view only, kept while the album is open)'],
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
