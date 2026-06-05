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
 * current result. A dropped connection reconnects automatically unless `reconnectMs` is `0`, except a
 * deliberate policy rejection (close code `1008`: unauthorized, unknown, malformed, or overlong
 * subscription), which is permanent and is not retried — reconnecting would just re-send the rejected
 * frame in a loop and hammer the server's auth path.
 */
export class MonolithLive {
  /** WebSocket close code the server uses to reject a subscription (RFC 6455 policy violation). */
  private static readonly POLICY_CLOSE = 1008;

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
      socket.onclose = (event?: CloseEvent) => {
        // A policy rejection (1008) is permanent: do not reconnect, or we would resend the rejected
        // subscription forever. A network/service close (no code, or any other code) does reconnect.
        const policyRejected = event?.code === MonolithLive.POLICY_CLOSE;
        if (!unsubscribed && this.reconnectMs > 0 && !policyRejected) {
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
