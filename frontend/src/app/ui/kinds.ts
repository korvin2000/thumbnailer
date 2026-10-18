import { EntityKind } from '../core/model';
import type { IconName } from './shared/icon';

/** How the UI names and draws each catalog kind (trees, cards, menus, chips). */
export const KIND_ICON: Record<EntityKind, IconName> = { MODEL: 'user', STUDIO: 'building', PHOTOGRAPHER: 'camera', GENRE: 'sparkles', TAG: 'hash', LIST: 'heart' };
export const KIND_LABEL: Record<EntityKind, string> = { MODEL: 'Models', STUDIO: 'Studios', PHOTOGRAPHER: 'Photographers', GENRE: 'Genres', TAG: 'Hashtags', LIST: 'Favorites' };
export const NOUN: Record<EntityKind, string> = { MODEL: 'model', STUDIO: 'studio', PHOTOGRAPHER: 'photographer', GENRE: 'genre', TAG: 'tag', LIST: 'list' };
