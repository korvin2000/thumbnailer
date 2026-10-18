import { Routes } from '@angular/router';

/** The viewer and the photo list are overlays loaded on first use (they stay out of the initial bundle). */
export const routes: Routes = [
  { path: '', children: [] },
  { path: 'album/:id', loadComponent: () => import('./ui/album-viewer').then(m => m.AlbumViewer) },
  { path: 'list/:id', loadComponent: () => import('./ui/photo-list').then(m => m.PhotoListView) },
  { path: '**', redirectTo: '' },
];
