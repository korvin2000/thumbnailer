import { Kind } from './model';

/** Structured part of a search box entry: `#wedding model:"Anna K" studio:lumen by:ivanov kind:zip soft light`. */
export interface ParsedSearch {
  text?: string;
  tags: string[];
  model?: string;
  studio?: string;
  photographer?: string;
  kind?: Kind;
}

const KEYS: Record<string, 'model' | 'studio' | 'photographer' | 'kind'> = {
  model: 'model', m: 'model', studio: 'studio', s: 'studio', by: 'photographer', photographer: 'photographer', p: 'photographer', kind: 'kind',
};

const KINDS: Record<string, Kind> = { zip: 'ZIP', archive: 'ZIP', dir: 'DIRECTORY', folder: 'DIRECTORY', directory: 'DIRECTORY' };

export function parseSearch(input: string): ParsedSearch {
  const out: ParsedSearch = { tags: [] };
  const words: string[] = [];
  for (const m of input.matchAll(/(\w+):("[^"]*"|\S+)|#([\w-]+)|("[^"]*"|\S+)/g)) {
    const [, key, value, tag, word] = m;
    if (tag) out.tags.push(tag.toLowerCase());
    else if (key && KEYS[key.toLowerCase()]) {
      const k = KEYS[key.toLowerCase()], v = unquote(value);
      if (k === 'kind') { const kind = KINDS[v.toLowerCase()]; if (kind) out.kind = kind; }
      else if (v) out[k] = v;
    } else words.push(unquote(word ?? m[0]));
  }
  if (words.length) out.text = words.join(' ');
  return out;
}

const unquote = (s: string) => s.replace(/^"(.*)"$/, '$1');
