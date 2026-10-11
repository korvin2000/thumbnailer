import { Routes } from '@angular/router';
import { AlbumViewer } from './ui/album-viewer';

export const routes: Routes = [
  { path: '', children: [] },
  { path: 'album/:id', component: AlbumViewer },
  { path: '**', redirectTo: '' },
];
