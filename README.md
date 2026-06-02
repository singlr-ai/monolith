# Monolith

*Live, typed queries on real Postgres.*

Monolith is a set of small Java libraries for building applications on PostgreSQL where the schema,
the typed data access, the client reader, and the live (reactive) queries all come from a single
declaration. You write a record, sometimes carrying its SQL, and an annotation processor generates
the rest at compile time. It runs on ordinary relational tables: you keep SQL, JOINs, transactions,
and foreign keys.

It started from one question: can a Java team get the "subscribe, and the UI updates when the data
changes" experience of Firebase or InstantDB *without* giving up a real relational database? This is
**v0.1: experimental, and the API will change.**

```java
// One declaration → Postgres DDL, a binary reader/builder, and a TypeScript reader for the same layout.
// @Encrypted fields are AES-GCM encrypted in the JVM; Postgres only ever stores ciphertext.
@PgType
public record Patient(UUID id, String name, @Encrypted String ssn) {}

// A @PgQuery record carries its SQL. The processor also generates a reader, a typed run(), and the
// reactive invalidation rule for this query.
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

```java
var pool = new PgPool("host=localhost dbname=app", 16);
PgCrypto.setKey(kms.fetchFieldKey());            // key stays in your process, never in Postgres

var conn = pool.lease().getOrThrow();            // fallible calls return a Result
try (var arena = Arena.ofConfined()) {
    List<OrderSummaryReader> rows = OrderSummaryQuery.run(arena, conn, "EU");  // binary, over libpq
} finally {
    pool.release(conn);
}

// Reactive: wake when a row that affects the result changes, even a line item two joins away.
var hub  = new ReactiveHub(pool, List.of(new OrderSummaryInvalidation()));  // rule is generated
var feed = new Invalidator("host=localhost dbname=app", hub, "app_feed");   // tails the WAL
hub.subscribe("OrderSummary", "EU", () -> pushFreshResultToClients());
```

## What it does today

- **One declaration, generated outputs.** A `@PgType` / `@PgQuery` record generates the DDL, a
  binary reader over the Postgres wire layout (a `MemorySegment`), a builder, a TypeScript reader for
  the identical layout, and, for `@PgQuery`, the reactive invalidation rule. All at compile time, with
  no runtime reflection.
- **Schema migrations.** `Migrator` applies ordered, versioned migrations once each, in their own
  transactions, recorded in a tracking table, and guarded by CRC32 checksums (idempotent, refuses
  edited or out-of-order migrations). It is forward-only by design, with `status` to preview the plan,
  repeatable migrations for views and functions, and `baseline` to adopt an existing database. Pairs
  with the codegen's `schema.lock` drift detection. See [`docs/MIGRATIONS.md`](docs/MIGRATIONS.md).
- **Live queries over joined tables.** Subscribe to a query and a parameter. When a row that affects
  the result changes, the generated rule maps the change back to the affected parameter values
  (walking joins where needed) and the query is re-run for just those subscribers. This is precise
  re-execution, not incremental view maintenance. There is no dataflow engine, just Postgres and
  generated SQL. If you need true IVM, use [Materialize](https://materialize.com); this is
  deliberately simpler.
- **Real relational Postgres.** Normalized tables, JOINs, transactions, constraints. Not a triple
  store, not schemaless. The data model stays SQL.
- **Transactions with automatic retry.** `Tx.tx(conn, work)` runs a unit of work in one transaction,
  committing a success and rolling back a failure, and retries the transient conflicts that
  `SERIALIZABLE` and `REPEATABLE READ` are expected to retry (serialization failures and deadlocks),
  keyed on the Postgres `SQLSTATE` rather than the error text. See
  [`docs/TRANSACTIONS.md`](docs/TRANSACTIONS.md).
- **Observability seam, dependency-free.** The runtime emits its transactions and pool leases as a
  sealed set of events through a one-method `MonolithObserver`. The core stays pure JDK; an adapter
  for OpenTelemetry or Micrometer lives in its own module and is the only place those dependencies are
  pulled. It costs a single reference comparison when no observer is installed. See
  [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md).
- **A durable queue in the same database.** `monolith-queue` is a durable, ordered, at-least-once
  message queue you enqueue to *inside a transaction*, so the message commits atomically with your
  writes. That is the property that makes a reliable transactional outbox (no dual-write window) and a
  durable job queue out of one primitive: per-key ordering, `SKIP LOCKED` claiming across many
  workers, retries with backoff, dead-letter inspect/replay, `LISTEN`/`NOTIFY` wakeup, and optional
  exactly-once-within-Postgres for database handlers. See [`docs/QUEUE.md`](docs/QUEUE.md).
- **Binary parameters, including arrays and enums.** Parameters bind in Postgres' binary format, with
  no string round-trip. A `List` binds as an array so you write set membership as `WHERE id = ANY($1)`
  (one parameter, fixed SQL, no injection surface) instead of an N-placeholder `IN (...)`, and a Java
  `enum` binds to an `enum` column by its label. A query you run repeatedly on a held connection can be
  a `Prepared` statement, parsed and planned once. See [`docs/PARAMETERS.md`](docs/PARAMETERS.md).
- **libpq, not JDBC.** Queries go through libpq, Postgres's own C client, called directly via the
  Java FFM API (JDK 22+), and results come back in the binary protocol. Because it *is* libpq, TLS and
  authentication (including SCRAM) are libpq's, not something reimplemented here.
- **Compliance building blocks, declared.** `@Encrypted String` is encrypted in the JVM
  (AES-256-GCM), so the database only ever stores ciphertext and the key never leaves your process.
  `@Tenant` generates forced row-level security that confines every row to the current tenant (and
  blocks cross-tenant writes), and `@Audited` generates an append-only, attributed audit trail. The
  app sets the tenant and actor per transaction with `PgSession`.
- **Scale-out routing.** `PgReplicaSet` routes writes to the primary and reads round-robin across
  streaming replicas; `ShardRouter` routes each tenant to its own shard for shared-nothing scale.
  Both route over a common `ConnectionSource` (which `PgPool` implements). See
  [`docs/SCALING.md`](docs/SCALING.md) for the full picture, including read-your-writes and failover.
- **A library, not a platform.** The core has no web framework. Bring your own `main` and routes; an
  optional Helidon WebSocket adapter is included for serving live queries.

## How the reactive part works

A logical replication slot feeds an `Invalidator` that tails the WAL. The feed is decoded with
`pgoutput`, Postgres's built-in, versioned binary logical-replication protocol (not the unstable
`test_decoding` text format). Each change is matched against every `@PgQuery`'s generated rule, which
yields the affected parameter values; subscribers on those values are woken and their query re-runs.
The join walk is real SQL the processor derives from the query. For example, a `line_items` change
resolves up to the `region` it rolls into.

## Modules

| Module | What it is |
|---|---|
| `monolith-api` | Declaration annotations: `@PgType`, `@PgQuery`, `@PgProjection`, `@PgNull`, `@Encrypted`, `@Tenant`, `@Audited`, `Json`. |
| `monolith-codegen` | The `javac` annotation processor. Generates DDL, readers/builders, TypeScript readers, and invalidation rules. |
| `monolith-runtime` | libpq via Panama FFM, a connection pool, the binary tuple bridge and codecs, field encryption, and the WAL change-feed primitives. Pure JDK, no third-party dependencies. |
| `monolith-reactive` | Live queries: `ReactiveHub` plus the WAL-tailing `Invalidator`. No web dependency. |
| `monolith-queue` | A durable, transactional, at-least-once message queue (outbox + jobs) over the same Postgres. Pure JDK. |
| `monolith-helidon` | Optional adapter: a Helidon SE `WsListener` that serves live queries over WebSockets. |

Every module except `monolith-helidon` has no web dependency, and nothing in the core depends on it.
Wire `ReactiveHub` into Spring, Quarkus, a plain `HttpServer`, or anything else the same way.

## Example

[`examples/collab`](examples/collab) is a complete, runnable app: a live, multi-client task board. It
exercises the whole stack (`@PgQuery` codegen, binary writes, WAL-driven invalidation, and the
WebSocket adapter) while registering its own HTTP and WebSocket routes. A write to a board pushes the
fresh list to every subscriber watching that board.

On the client side, [`clients/typescript`](clients/typescript) is a small TypeScript package that
opens the WebSocket and decodes each pushed frame through the generated `<Name>Reader`, so the same
types reach the browser.

## Goals and non-goals

**Goals.** A live-subscription developer experience on a real relational database, for Java teams;
type safety carried from the database row to the client, generated rather than hand-written and kept
in sync; and a set of libraries you embed, not a platform you adopt.

**Non-goals.** It is not an incremental-view-maintenance engine, not an ORM (you write SQL), and not a
managed service.

## Not here yet

Honest about the gaps:

- Some scaling concerns are deployment topology, not library code: the reactive fan-out gateway for
  very large fleets, and automatic failover, are documented in [`docs/SCALING.md`](docs/SCALING.md)
  rather than shipped as code.

## Status & requirements

**v0.1: experimental; APIs will change.** Requires **JDK 25+** (Panama FFM, virtual threads) and
**PostgreSQL 14+** (`wal_level = logical` for the reactive layer). **macOS and Linux** (libpq is loaded
via FFM); Windows via WSL2.

## License

MIT, Standard Applied Intelligence Labs. See [LICENSE](LICENSE).
