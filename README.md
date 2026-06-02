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
// @Encrypted fields use envelope encryption (a per-value AES-256-GCM data key); Postgres stores only ciphertext.
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
// Reactive: wake when a row that affects the result changes, even a line item two joins away.
var hub  = new ReactiveHub(pool, List.of(new OrderSummaryInvalidation()));  // rule is generated
var feed = new Invalidator("host=localhost dbname=app", hub, "app_feed");   // tails the WAL
hub.subscribe("OrderSummary", "EU", () -> pushFreshResultToClients());
```

## What it does

- **One declaration, generated outputs**: DDL, a binary reader/builder over the Postgres wire layout, a
  TypeScript reader, and a reactive invalidation rule, at compile time, no runtime reflection.
- **Live queries over joined tables**: precise re-execution, not incremental view maintenance; the
  generated rule maps a change back to the affected subscribers, walking joins where needed.
- **libpq, not JDBC**: the binary protocol via the Java FFM API; TLS and SCRAM are libpq's.
- **Transactions with automatic retry**, **schema migrations**, **binary parameters** (arrays, enums,
  prepared statements), and a **durable transactional queue** (outbox + jobs in one primitive).
- **Compliance in the database**: `@Encrypted` envelope encryption, `@Tenant` and `@AccessControlled`
  forced row-level security (a unified RBAC/ACL/consent grant model), `@Audited` trails.
- **Scale-out routing**: read replicas and tenant sharding over a common `ConnectionSource`.
- **A library, not a platform**: pure-JDK core, no web framework; bring your own `main` and routes.

## Documentation

Full guides, concepts, and design notes: **<https://monolith.standardapplied.com>**. The docs site also
publishes an [`llms.txt`](https://monolith.standardapplied.com/llms.txt) and `llms-full.txt` so coding
agents can ingest the documentation as context. A complete, runnable example app (a live, multi-client
task board) is in [`examples/collab`](examples/collab).

## Modules

| Module | What it is |
|---|---|
| `monolith-api` | Declaration annotations: `@PgType`, `@PgQuery`, `@PgProjection`, `@PgNull`, `@Encrypted`, `@Tenant`, `@Audited`, `@AccessControlled`, `Json`. |
| `monolith-codegen` | The `javac` annotation processor. Generates DDL, readers/builders, TypeScript readers, and invalidation rules. |
| `monolith-runtime` | libpq via Panama FFM, a connection pool, the binary tuple bridge and codecs, transactions, field encryption, and the WAL change-feed primitives. Pure JDK. |
| `monolith-reactive` | Live queries: `ReactiveHub` plus the WAL-tailing `Invalidator`. No web dependency. |
| `monolith-queue` | A durable, transactional, at-least-once message queue (outbox + jobs) over the same Postgres. Pure JDK. |
| `monolith-helidon` | Optional adapter: a Helidon SE `WsListener` that serves live queries over WebSockets. |

Every module except `monolith-helidon` has no web dependency, and nothing in the core depends on it.

## Status & requirements

**v0.1: experimental; APIs will change.** Requires **JDK 25+** (Panama FFM, virtual threads) and
**PostgreSQL 14+** (`wal_level = logical` for the reactive layer). **macOS and Linux** (libpq is loaded
via FFM); Windows via WSL2.

**Goals.** A live-subscription developer experience on a real relational database, for Java teams; type
safety carried from the database row to the client; libraries you embed, not a platform you adopt.
**Non-goals.** Not an incremental-view-maintenance engine, not an ORM (you write SQL), not a managed
service.

## License

MIT, Standard Applied Intelligence Labs. See [LICENSE](LICENSE).
