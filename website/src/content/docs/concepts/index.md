---
title: Concepts
description: The four mental models behind Monolith, one declaration generates the rest, reactivity over relational, compliance in the database, and scaling that follows the data.
---

Four ideas explain almost everything about how Monolith works. Hold these and the guides fall into
place.

## 1. One declaration, generated outputs

You write a record. An annotation processor (`monolith-codegen`, running inside `javac`) generates the
rest at compile time, with no runtime reflection:

```java
@PgType
public record Customer(UUID id, String name, String region) {}
```

From that single `@PgType` (or `@PgQuery`, which carries its SQL) come the table DDL, a **binary
reader** over the Postgres wire layout (a `MemorySegment`), a **builder**, a **TypeScript reader** for
the identical layout, and, for a `@PgQuery`, a **reactive invalidation rule**. The record is the single
source of truth; the generated outputs cannot drift from it, because they are regenerated from it on
every build. A `schema.lock` file captures the wire layout so a change that would break the binary
contract fails the build instead of corrupting data silently.

## 2. Reactivity over a real relational schema

This is the differentiator. You subscribe to a `@PgQuery` and a parameter; when a row that affects the
result changes, even one two joins away, your callback fires and the query re-runs for just the
affected subscribers.

It is **precise re-execution, not incremental view maintenance**. There is no dataflow engine. A
logical replication slot feeds an `Invalidator` that tails the write-ahead log (decoded with
`pgoutput`, Postgres' own binary protocol). Each change is matched against every query's generated
rule, which maps the change back to the parameter values it affects, walking joins in real SQL where
needed, and the subscribers on those values are woken. The data model stays ordinary SQL: normalized
tables, JOINs, transactions, foreign keys. See [Live queries](/guides/live-queries/).

## 3. Compliance lives in the database

Monolith's position is that a security or correctness rule belongs in Postgres, on every query, where
no application code path can route around it. `@Tenant` generates **forced** row-level security;
`@AccessControlled` generates forced RLS over a grant model; `@Encrypted` is envelope encryption whose
key never reaches Postgres; `@Audited` is an append-only, attributed trail enforced by triggers. The
application sets a small per-transaction context (the tenant, the actor, any attribute) with
`PgSession`; the database does the rest. This is why access control scales without an authorization
tier and why isolation holds even against a forgotten `WHERE`.

## 4. Scaling follows the data

Because enforcement and policy both live in Postgres, the system scales the way the data scales and
inherits the same well-understood trade-offs rather than inventing new ones. App instances are
stateless (nothing to invalidate across nodes); read replicas serve reads with bounded staleness;
tenant shards keep each tenant's data and its policies co-located. The scaling helpers (`PgReplicaSet`,
`ShardRouter`) route over a common `ConnectionSource`. See [Scaling](/guides/scaling/).

## A library, not a platform

The core has no web framework and no third-party runtime dependencies. You bring your own `main`,
routes, and IdP; you embed these libraries rather than adopt a platform. Optional adapters
(`monolith-helidon` for WebSockets, future KMS and OpenTelemetry adapters) live in their own modules so
their dependencies never reach the core.
