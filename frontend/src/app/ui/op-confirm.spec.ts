import { describe, expect, it } from 'vitest';
import { Op, OpKind, Preflight } from '../core/model';
import { CONFLICT_CHOICES, confirmFor, finalize, opNoun, opVerb, passwordRequest } from './op-confirm';

const pre: Preflight = { items: [{ path: 'D:/P/a', skip: false }, { path: 'D:/P/b.zip', skip: true, reason: 'already packed' }, { path: 'D:/P/c', skip: false }], bytes: 10, free: 100, warnings: [], consequences: [] };
const op = (kind: OpKind, extra: Partial<Op> = {}): Op => ({ kind, albumIds: ['a', 'b', 'c'], conflict: 'RENAME', ...extra });

describe('op → confirm dialog', () => {
  it('titles name the target, the format and the destination; the conflict policy is offered where a name can collide', () => {
    expect(confirmFor(op('PACK', { format: 'AUTO' }), pre, '3 albums')).toMatchObject({ title: 'Pack 3 albums as zip (auto)', okLabel: 'Pack', summary: pre });
    expect(confirmFor(op('PACK', { format: 'SEVEN_ZIP' }), pre, '3 albums').title).toBe('Pack 3 albums as 7z');
    expect(confirmFor(op('REPACK', { format: 'ZIP' }), pre, 'x').title).toBe('Repack x as zip');
    expect(confirmFor(op('MOVE', { target: 'E:/out' }), pre, '3 albums').title).toBe('Move 3 albums to E:/out');
    expect(confirmFor(op('FOLDER_RENAME', { paths: ['D:/P/2019'], target: '2020' }), pre, 'folder 2019')).toMatchObject({ title: 'Rename folder 2019 to 2020', okLabel: 'Rename' });
    for (const k of ['PACK', 'UNPACK', 'REPACK', 'MOVE', 'COPY', 'FOLDER_MOVE', 'FOLDER_COPY'] as OpKind[]) expect(confirmFor(op(k), pre, 'x').options?.map(o => o.key)).toEqual(['conflict']);
    for (const k of ['DELETE', 'CRYPT', 'FOLDER_RENAME', 'FOLDER_DELETE'] as OpKind[]) expect(confirmFor(op(k), pre, 'x').options).toBeUndefined();
    expect(confirmFor(op('MOVE', { conflict: 'SKIP' }), pre, 'x').options?.[0].value).toBe(CONFLICT_CHOICES[1]);
  });

  it('deletions are danger dialogs listing every item to do (the skipped ones stay in the summary)', () => {
    const d = confirmFor(op('DELETE'), pre, '3 albums');
    expect(d).toMatchObject({ title: 'Delete 3 albums?', danger: true, showAll: true, okLabel: 'Delete', items: ['D:/P/a', 'D:/P/c'] });
    expect(d.message).toContain('no Recycle Bin');
    expect(confirmFor(op('FOLDER_DELETE', { paths: ['D:/P/2019'] }), pre, 'folder 2019')).toMatchObject({ danger: true, items: ['D:/P/a', 'D:/P/c'] });
  });

  it('encryption names the session key when there is one, and asks for the password twice before the preflight without it', () => {
    expect(confirmFor(op('CRYPT', { encrypt: true }), pre, 'x', { fingerprint: 'ab12cd34' })).toMatchObject({ title: 'Encrypt x', okLabel: 'Encrypt', message: 'Using the session key (fingerprint ab12cd34).' });
    expect(confirmFor(op('CRYPT', { encrypt: false }), pre, 'x').message).toBeUndefined();
    expect(confirmFor(op('CRYPT', { encrypt: false }), pre, 'x').title).toBe('Decrypt x');
    expect(passwordRequest(op('CRYPT', { encrypt: true }), 'x')).toMatchObject({ title: 'Encrypt x', okLabel: 'Continue', options: [{ key: 'password', kind: 'password2' }] });
  });

  it('finalize applies the chosen conflict policy and a typed password', () => {
    expect(finalize(op('MOVE'), { conflict: CONFLICT_CHOICES[1] }).conflict).toBe('SKIP');
    expect(finalize(op('MOVE', { conflict: 'SKIP' }), { conflict: CONFLICT_CHOICES[0] }).conflict).toBe('RENAME');
    expect(finalize(op('DELETE', { conflict: 'SKIP' }), {}).conflict).toBe('SKIP');
    expect(finalize(op('CRYPT'), { password: 'pw' }).password).toBe('pw');
    expect('password' in finalize(op('CRYPT'), { password: '' })).toBe(false);
  });

  it('words every kind for the status bar and the result', () => {
    expect(opVerb({ kind: 'PACK' })).toBe('Packing');
    expect(opVerb({ kind: 'CRYPT', encrypt: false })).toBe('Decrypting');
    expect(opVerb({ kind: 'CRYPT' })).toBe('Encrypting');
    expect(opNoun({ kind: 'FOLDER_DELETE' })).toBe('Delete folder');
    expect(opNoun({ kind: 'COPY_PHOTOS' })).toBe('Copy photos');
  });
});
