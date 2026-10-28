import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GatewayEvent } from './model';
import { WsGateway } from './ws-gateway';

/** A `WebSocket` the test drives: `open()`, `hello(library)`, `reply(id, message)`, `close()`; everything sent is parsed into `sent`. */
class FakeSocket {
  static readonly CONNECTING = 0; static readonly OPEN = 1; static readonly CLOSED = 3;
  static all: FakeSocket[] = [];
  readyState = FakeSocket.CONNECTING;
  sent: Record<string, unknown>[] = [];
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  constructor(readonly url: string) { FakeSocket.all.push(this); }
  send(s: string) { this.sent.push(JSON.parse(s)); }
  open() { this.readyState = FakeSocket.OPEN; }
  hello(library: string, more: object = {}) { this.onmessage!({ data: JSON.stringify({ type: 'hello', library, scan: null, op: null, opResult: null, ...more }) }); }
  reply(id: string, m: object) { this.onmessage!({ data: JSON.stringify({ id, ...m }) }); }
  close() { this.readyState = FakeSocket.CLOSED; this.onclose!(); }
}

const last = () => FakeSocket.all[FakeSocket.all.length - 1];

describe('WsGateway', () => {
  let gw: WsGateway, events: GatewayEvent[];
  beforeEach(() => { vi.useFakeTimers(); vi.stubGlobal('WebSocket', FakeSocket); FakeSocket.all = []; gw = new WsGateway('ws://test/ws'); events = []; gw.events.subscribe(e => events.push(e)); });
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

  it('sends nothing before the server\'s hello, then flushes the queue and announces the connection with it', async () => {
    const s = last(), r = gw.library();
    s.open();
    const r2 = gw.getSettings(); // between open and hello: still held
    expect(s.sent).toEqual([]);
    expect(events).toEqual([]);
    s.hello('C:/lib', { scan: { running: true } });
    expect(s.sent.map(m => m['type'])).toEqual(['library', 'getSettings']);
    expect(events).toEqual([{ type: 'connection', connected: true, hello: { library: 'C:/lib', scan: { running: true }, op: null, opResult: null } }]);
    s.reply('1', { type: 'libraryInfo', home: 'h' }); s.reply('2', { type: 'settings', settings: {}, dataDir: 'C:/lib', roots: [], renderEpoch: '' });
    expect(await r).toMatchObject({ home: 'h' });
    expect((await r2).dataDir).toBe('C:/lib');
  });

  it('after a reconnect the same library gets the queued requests, another one rejects them with "Library changed" and sends none', async () => {
    const s1 = last();
    s1.open(); s1.hello('C:/lib');
    const lost = gw.library();
    s1.close();
    await expect(lost).rejects.toThrow('Connection lost');
    expect(events[1]).toEqual({ type: 'connection', connected: false });
    const queued = gw.deleteEntities([7]);
    vi.advanceTimersByTime(500);
    const s2 = last();
    expect(s2).not.toBe(s1);
    s2.open(); s2.hello('C:/lib');
    expect(s2.sent).toMatchObject([{ type: 'deleteEntities', ids: [7] }]);
    s2.reply(s2.sent[0]['id'] as string, { type: 'ok' });
    await queued;
    s2.close();
    const crossing = gw.deleteEntities([8]);
    vi.advanceTimersByTime(10_000);
    const s3 = last();
    s3.open(); s3.hello('D:/other');
    await expect(crossing).rejects.toThrow('Library changed');
    expect(s3.sent).toEqual([]);
    expect(events[events.length - 1]).toMatchObject({ type: 'connection', connected: true, hello: { library: 'D:/other' } });
    const after = gw.library(); // the new library's requests go out at once
    expect(s3.sent.map(m => m['type'])).toEqual(['library']);
    s3.reply(s3.sent[0]['id'] as string, { type: 'libraryInfo' });
    await after;
  });
});
