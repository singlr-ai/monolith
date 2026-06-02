# Changelog

All notable changes to Monolith are documented here. Versions follow [SemVer](https://semver.org/).
This is **v0.x: experimental, and the API will change.**

## [0.2.0] - 2026-06-02

The production-hardening release: the reactive-relational core from 0.1.0 grows the operational,
compliance, and integration capabilities a real backend needs. Every gated class is at 100% line and
branch coverage, proven against real Postgres.

### Added

- **Durable transactional queue** (`monolith-queue`): a durable, ordered, at-least-once message queue
  you enqueue to inside a transaction, the one primitive under the transactional outbox and durable
  job patterns. Per-key ordering, `SKIP LOCKED` claiming across many workers, retries with backoff,
  dead-letter inspect/replay, `LISTEN`/`NOTIFY` wakeup, lease-based crash recovery, and optional
  exactly-once-within-Postgres for database handlers.
- **Schema migrations** (`Migrator`): ordered, versioned, idempotent migrations in their own
  transactions, CRC32-guarded, with `status` to preview the plan, repeatable migrations, and
  `baseline` to adopt an existing database.
- **Transactions with automatic retry** (`Tx`): commits a success, rolls back a failure, and retries
  the transient serialization and deadlock conflicts, keyed on the Postgres `SQLSTATE`.
- **Observability seam**: the runtime emits its transactions, pool, and queue activity as a sealed set
  of `MonolithEvent`s through a one-method `MonolithObserver`, with a `MonolithEvent.Extension` escape
  hatch so modules emit through one seam. Zero cost when no observer is installed; the core stays pure
  JDK and an adapter lives in its own module.
- **Binary array and enum parameters**, so set membership is `WHERE id = ANY($1)`, plus connection-
  scoped `Prepared` statements parsed and planned once.
- **Grant-based access control** (`@AccessControlled`, `Grants`): a unified RBAC / ACL / ownership /
  deny-wins-consent grant model generated into forced row-level security, composing with `@Tenant`.
  `PgSession.set` hydrates arbitrary session attributes for attribute checks without a join.
- **Compliance primitives**: `@Tenant` forced row-level security and `@Audited` append-only,
  attributed audit trails.
- **Scale-out routing**: `PgReplicaSet` (primary plus read replicas) and `ShardRouter` (tenant
  sharding) over a common `ConnectionSource`.
- **TypeScript client** for live queries, decoding pushed frames (including `jsonb`, `numeric`, and
  arrays) through the generated reader, gated at 100% coverage.
- **A documentation site** (Astro Starlight) with guides, concepts, and design notes, plus an
  `llms.txt` / `llms-full.txt` so coding agents can ingest the docs as context.
- A **100% line and branch coverage gate** and CI against a real Postgres.

### Changed

- **`@Encrypted` now uses envelope encryption**: each value gets its own AES-256-GCM data key, wrapped
  by a key-encryption key held behind a pure-JDK `KeyProvider` SPI (the default `LocalKeyProvider`
  supports key rotation; a KMS adapter plugs in). The key never reaches Postgres, and the on-disk wire
  form is versioned. This replaces the 0.1.0 single in-process key that encrypted values directly.
- **The reactive layer decodes the WAL with `pgoutput`**, Postgres' built-in, versioned binary
  logical-replication protocol, instead of the unstable `test_decoding` text format.

## [0.1.0]

Initial experimental cut: live, typed queries on real Postgres. A `@PgType` / `@PgQuery` record
generates the table DDL, a binary reader and builder over the Postgres wire layout, a TypeScript
reader, and a reactive invalidation rule, at compile time with no runtime reflection. Live queries via
`ReactiveHub` and a WAL-tailing `Invalidator`; libpq over the Panama FFM API with a connection pool;
`@Encrypted` field encryption; and the runnable `examples/collab` task board.
