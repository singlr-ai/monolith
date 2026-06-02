---
title: Live queries
description: Subscribe to a query and a parameter; when a row that affects the result changes, even across joins, the query re-runs for just the affected subscribers.
---

The headline feature. You subscribe to a `@PgQuery` and a parameter value, and your callback fires when
a row that affects that result changes, including a row reached through a join. It is precise
re-execution over a real relational schema, not incremental view maintenance and not polling.

## Declare the query

A `@PgQuery` record carries its SQL. The processor generates a typed `run`, a reader, and, crucially, a
**reactive invalidation rule**:

```java
@PgQuery("""
    SELECT o.id, c.name AS customer, o.status,
           coalesce(sum(li.qty * li.unit_price), 0) AS total
      FROM orders o
      JOIN customers c        ON c.id = o.customer_id
      LEFT JOIN line_items li ON li.order_id = o.id
     WHERE c.region = $1
     GROUP BY o.id, c.name, o.status""")
public record OrderSummary(UUID id, String customer, String status, BigDecimal total) {}
```

## Subscribe

Wire the generated rule into a `ReactiveHub`, fed by a WAL-tailing `Invalidator`, and subscribe to a
parameter value:

```java
var hub  = new ReactiveHub(pool, List.of(new OrderSummaryInvalidation())); // the rule is generated
var feed = new Invalidator("host=localhost dbname=app", hub, "app_feed");   // tails the WAL

hub.subscribe("OrderSummary", "EU", () -> pushFreshResultToClients());
```

Now a change to an `orders` row in the EU region wakes that subscriber, **and so does a change to a
`line_items` row two joins away**, because the generated rule resolves the change back up to the
`region` it rolls into.

## How invalidation works

A logical replication slot feeds the `Invalidator`, which tails the write-ahead log. The feed is
decoded with **`pgoutput`**, Postgres' built-in, versioned binary logical-replication protocol (not the
unstable `test_decoding` text format). For each change:

1. The change (table + changed columns) is matched against every `@PgQuery`'s generated
   `PgInvalidationRule`.
2. A matching rule yields the **affected parameter values**. When the changed table is not the one the
   parameter lives on, the rule runs the join walk, real SQL the processor derived from the query, to
   resolve the change up to the parameter (a `line_items` change resolves to the `region` it belongs
   to).
3. Subscribers registered on those parameter values are woken, and the query re-runs fresh for them.

Because re-execution always runs the query again, delivery is at-least-once and the result a subscriber
sees is always a consistent, current snapshot, never a partial diff. There is no dataflow graph to keep
correct; the cost is re-running the query, which for the parameter-scoped result sets this targets is
cheap. If you need true incremental view maintenance over enormous results, use a dedicated IVM engine;
this is deliberately simpler.

## Serving it to clients

`monolith-reactive` is transport-agnostic: it wakes your callback, and you push however you like (a
WebSocket, SSE, a gRPC stream). The optional `monolith-helidon` adapter ships a Helidon SE `WsListener`
that serves live queries over WebSockets out of the box, and on the client side the
[`clients/typescript`](https://github.com/singlr-ai/monolith/tree/main/clients/typescript) package
decodes each pushed frame through the generated `<Name>Reader`, so the same types reach the browser.

## Requirements

The reactive layer needs `wal_level = logical` and a replication-capable connection (the `Invalidator`
opens its own). Subscriptions are per parameter value, so key them by the parameter your clients care
about (a region, a board, a tenant).
