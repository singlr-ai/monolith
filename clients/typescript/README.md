# @standardapplied/monolith-client

A tiny TypeScript client for Monolith live queries over WebSocket. It opens a socket, subscribes to
a query, and decodes each pushed frame into typed rows using the readers Monolith's codegen emits.

The decoding is end to end: the same annotation processor that generates the Java reader emits a
`<Name>Reader.ts` over the identical binary layout (run codegen with `-Amonolith.tsDir=...`). This
package supplies the runtime around those readers: the WebSocket and the frame envelope.

## Use it

```ts
import { MonolithLive } from '@standardapplied/monolith-client';
import { OrderSummaryReader } from './generated/order_summary.ts'; // emitted by codegen

const live = new MonolithLive('ws://localhost:8080/live-query');

const sub = live.subscribe('OrderSummary', 'EU', OrderSummaryReader, (rows) => {
  for (const row of rows) {
    console.log(row.id(), row.customer(), row.total());
  }
});

// later
sub.unsubscribe();
```

`subscribe` opens its own socket (the server allows one subscription per connection), sends
`"<QueryName>:<param>"`, and calls your callback with the full current result on every relevant
change. A dropped connection reconnects automatically; pass `{ reconnectMs: 0 }` to disable that, or
`{ webSocket }` to supply a WebSocket implementation (the global is used by default, so it works in
browsers and Node 22+).

`parseFrame(bytes)` is exported too if you want to decode the `[int32 rowCount] ([int32 len] [row])*`
envelope yourself.

## Develop

```bash
npm test       # node --test "src/*.test.ts" (Node 22+, no build step: types are stripped)
npm run build  # emit dist/ (.js + .d.ts) via tsc
```

Requires Node 22+ (global `WebSocket`, native TypeScript type stripping).
