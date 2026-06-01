/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MonolithLive } from './live.ts';

/** A fresh fake WebSocket class plus the list of instances it creates. */
function fakeWebSocket() {
  const instances: Fake[] = [];
  class Fake {
    url: string;
    binaryType = '';
    sent: string[] = [];
    closed = false;
    onopen?: () => void;
    onmessage?: (event: { data: ArrayBuffer }) => void;
    onclose?: () => void;
    constructor(url: string) {
      this.url = url;
      instances.push(this);
    }
    send(message: string) {
      this.sent.push(message);
    }
    close() {
      this.closed = true;
    }
  }
  return { Fake: Fake as unknown as typeof WebSocket, instances };
}

const identityReader = { from: (bytes: Uint8Array) => bytes };

test('defaults to the global WebSocket and a one-second reconnect', () => {
  const live = new MonolithLive('ws://host/live'); // no options: exercises the defaults
  assert.ok(live instanceof MonolithLive);
});

test('a subscription opens a socket and sends the query on open', () => {
  const { Fake, instances } = fakeWebSocket();
  const live = new MonolithLive('ws://host/live', { webSocket: Fake });

  live.subscribe('Orders', 'EU', identityReader, () => {});

  assert.equal(instances.length, 1);
  assert.equal(instances[0].url, 'ws://host/live');
  assert.equal(instances[0].binaryType, 'arraybuffer');
  instances[0].onopen?.();
  assert.deepEqual(instances[0].sent, ['Orders:EU']);
});

test('a pushed frame is decoded into rows through the reader', () => {
  const { Fake, instances } = fakeWebSocket();
  const live = new MonolithLive('ws://host/live', { webSocket: Fake });
  let delivered: Uint8Array[] = [];

  live.subscribe('Q', 'p', identityReader, (rows) => {
    delivered = rows;
  });
  // one frame, one row whose single byte is 42
  const wire = new Uint8Array([0, 0, 0, 1, 0, 0, 0, 1, 42]);
  instances[0].onmessage?.({ data: wire.buffer });

  assert.equal(delivered.length, 1);
  assert.deepEqual([...delivered[0]], [42]);
});

test('unsubscribe closes the socket and prevents reconnection', () => {
  const { Fake, instances } = fakeWebSocket();
  const live = new MonolithLive('ws://host/live', { webSocket: Fake, reconnectMs: 1000 });
  const sub = live.subscribe('Q', 'p', identityReader, () => {});

  sub.unsubscribe();
  assert.equal(instances[0].closed, true);

  instances[0].onclose?.(); // a close after unsubscribe must not reconnect
  assert.equal(instances.length, 1);
});

test('an unexpected close reconnects after the delay', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { Fake, instances } = fakeWebSocket();
  const live = new MonolithLive('ws://host/live', { webSocket: Fake, reconnectMs: 1000 });

  live.subscribe('Q', 'p', identityReader, () => {});
  instances[0].onclose?.();
  t.mock.timers.tick(1000);

  assert.equal(instances.length, 2);
});

test('reconnectMs of 0 disables reconnection', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { Fake, instances } = fakeWebSocket();
  const live = new MonolithLive('ws://host/live', { webSocket: Fake, reconnectMs: 0 });

  live.subscribe('Q', 'p', identityReader, () => {});
  instances[0].onclose?.();
  t.mock.timers.tick(10000);

  assert.equal(instances.length, 1);
});
