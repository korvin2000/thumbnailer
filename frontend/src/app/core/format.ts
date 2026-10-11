import { Pipe, PipeTransform } from '@angular/core';

export function formatBytes(n: number | null | undefined, digits = 1): string {
  if (n == null) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i === 0 ? 0 : digits)} ${units[i]}`;
}

export function formatMegapixels(pixels: number | null | undefined): string {
  if (!pixels) return '';
  const mp = pixels / 1e6;
  return `${mp >= 10 ? mp.toFixed(0) : mp.toFixed(1)} MP`;
}

@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform { transform = formatBytes; }

@Pipe({ name: 'mp' })
export class MegapixelsPipe implements PipeTransform { transform = formatMegapixels; }

/** "D:/Photos/2019/Album.zip" → "Album.zip"; keeps the last two segments when `depth` is 2. */
@Pipe({ name: 'basename' })
export class BasenamePipe implements PipeTransform {
  transform(path: string | null | undefined, depth = 1): string {
    return (path ?? '').split('/').filter(Boolean).slice(-depth).join('/');
  }
}
