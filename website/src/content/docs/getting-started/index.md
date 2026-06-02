---
title: Getting started
description: Install Monolith, declare your first type, run a typed query, and subscribe to a live one.
---

This walks from nothing to a typed query and a live subscription. It assumes **JDK 25+**,
**PostgreSQL 14+** (with `wal_level = logical` for the reactive layer), and **libpq** on the machine
(macOS/Linux; Windows via WSL2).

## 1. Add the modules

Monolith is a set of small libraries. At minimum you need the annotation processor and the runtime:

```xml
<dependency>
  <groupId>com.standardapplied</groupId>
  <artifactId>monolith-runtime</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>com.standardapplied</groupId>
  <artifactId>monolith-codegen</artifactId>
  <version>0.1.0</version>
  <scope>provided</scope> <!-- annotation processor, compile time only -->
</dependency>
```

Point the processor at where it should write DDL, the TypeScript reader, and the schema lock with
`-Amonolith.sqlDir=...`, `-Amonolith.tsDir=...`, and `-Amonolith.lockDir=...` compiler args. Add
`monolith-reactive` for live queries and `monolith-queue` for the durable queue when you need them.

## 2. Declare a type

A `@PgType` record is the single declaration the rest is generated from:

```java
@PgType
public record Customer(UUID id, String name, String region) {}
```

At compile time this generates the table DDL, a binary reader over the Postgres wire layout, a builder,
and a TypeScript reader for the same layout. Apply the generated `customer.sql` to your database.

## 3. Run a typed query

A `@PgQuery` record carries its SQL; the processor generates a typed `run`:

```java
@PgQuery("""
    SELECT id, name, region FROM customer WHERE region = $1""")
public record CustomersInRegion(UUID id, String name, String region) {}
```

```java
var pool = new PgPool("host=localhost dbname=app", 16);
var conn = pool.lease().getOrThrow();          // fallible calls return a Result
try (var arena = Arena.ofConfined()) {
  List<CustomersInRegionReader> rows = CustomersInRegionQuery.run(arena, conn, "EU");
  for (var r : rows) {
    System.out.println(r.name());
  }
} finally {
  pool.release(conn);
}
```

## 4. Subscribe to a live query

The processor also generates a reactive invalidation rule for a `@PgQuery`. Wire it into a
`ReactiveHub` fed by a WAL-tailing `Invalidator`, and your callback fires when a row that affects the
result changes:

```java
var hub  = new ReactiveHub(pool, List.of(new CustomersInRegionInvalidation()));
var feed = new Invalidator("host=localhost dbname=app", hub, "app_feed"); // tails the WAL
hub.subscribe("CustomersInRegion", "EU", () -> pushFreshResultToClients());
```

## Next steps

- [Queries and parameters](/guides/parameters/), including `WHERE id = ANY($1)` and enums.
- [Transactions](/guides/transactions/) with automatic retry.
- [Live queries and the reactive model](/guides/) for how invalidation maps changes back to subscribers.
- [Access control](/guides/access/), [encryption](/guides/encryption/), and the rest under Guides.
