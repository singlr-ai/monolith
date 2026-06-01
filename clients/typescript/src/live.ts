/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

import { parseFrame } from './frame.ts';

/** A row decoder: the generated `<Name>Reader` class exposes a static `from(bytes)`. */
export interface RowReader<R> {
  from(bytes: Uint8Array): R;
}

/** A live subscription; call {@link unsubscribe} to close it and stop reconnecting. */
export interface Subscription {
  unsubscribe(): void;
}

export interface LiveOptions {
  /** WebSocket implementation to use; defaults to the global `WebSocket`. */
  webSocket?: typeof WebSocket;
  /** Reconnect delay in milliseconds after an unexpected close; `0` disables reconnection. */
  reconnectMs?: number;
}

/**
 * Client for Monolith live queries over WebSocket. Each {@link subscribe} opens its own socket
 * (the server allows one subscription per connection), sends `"<QueryName>:<param>"`, and on every
 * pushed frame decodes the rows through the generated reader and invokes the callback with the full
 * current result. A dropped connection reconnects automatically unless `reconnectMs` is `0`.
 */
export class MonolithLive {
  private readonly url: string;
  private readonly webSocket: typeof WebSocket;
  private readonly reconnectMs: number;

  constructor(url: string, options: LiveOptions = {}) {
    this.url = url;
    this.webSocket = options.webSocket ?? WebSocket;
    this.reconnectMs = options.reconnectMs ?? 1000;
  }

  subscribe<R>(
    query: string,
    param: string,
    reader: RowReader<R>,
    onRows: (rows: R[]) => void,
  ): Subscription {
    let unsubscribed = false;
    let socket: WebSocket;

    const open = () => {
      socket = new this.webSocket(this.url);
      socket.binaryType = 'arraybuffer';
      socket.onopen = () => socket.send(`${query}:${param}`);
      socket.onmessage = (event: MessageEvent) => {
        const bytes = new Uint8Array(event.data as ArrayBuffer);
        onRows(parseFrame(bytes).map((row) => reader.from(row)));
      };
      socket.onclose = () => {
        if (!unsubscribed && this.reconnectMs > 0) {
          setTimeout(open, this.reconnectMs);
        }
      };
    };

    open();

    return {
      unsubscribe() {
        unsubscribed = true;
        socket.close();
      },
    };
  }
}
