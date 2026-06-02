---
title: "Query parameters"
description: "Parameters are bound in Postgres' binary format, the same encodings the generated builders use, so a value goes to the server as bytes with no string round"
---

Parameters are bound in Postgres' binary format, the same encodings the generated builders use, so a
value goes to the server as bytes with no string round-trip. Every generated `<Name>Query.run` takes
`Object...` params and binds them through `PgParam`, so anything `PgParam.encode` understands can be a
query parameter.

## Scalars

`String`, `UUID`, `byte[]`, `Boolean`, `Short`/`Integer`/`Long`, `Float`/`Double`, `BigDecimal`
(`numeric`), `Json` (`jsonb`), and the `java.time` types (`LocalDate`, `LocalTime`, `LocalDateTime`,
`Instant`, `OffsetDateTime`). A `null` becomes SQL `NULL`.

## Arrays, for `= ANY($1)`

A list parameter binds as a Postgres array, which is how you write an `IN`-style query with a single
parameter:

```java
// WHERE id = ANY($1)
OrdersByIdsQuery.run(arena, conn, List.of(id1, id2, id3));
```

`= ANY($1)` is preferred over building an `IN (...)` clause with N parameters: the SQL is fixed
regardless of how many values you pass, so it plans once and there is no injection surface.

Supported element types: `Integer`, `Long`, `String`, `UUID`. A `List`'s element type is inferred
from its first non-null element, so an **empty or all-null `List` is rejected** (its type can't be
known); pass a typed array such as `new UUID[0]` in that case. `String` and `UUID` arrays may contain
`null` elements.

### Lists vs arrays, and a varargs caveat

Both a `List` and a typed array encode to the same Postgres array. Prefer the `List`. The reason is
the `Object...` parameter list: a primitive array (`int[]`, `long[]`) is a single `Object` and binds
as one array parameter, but an **object array (`UUID[]`, `String[]`) is an `Object[]` and the varargs
mechanism spreads it into many parameters**. So:

```java
run(arena, conn, new int[] {1, 2, 3});        // fine: int[] is one parameter
run(arena, conn, List.of(id1, id2));          // fine: a List is one parameter
run(arena, conn, new UUID[] {id1, id2});      // WRONG: spread into two UUID parameters
run(arena, conn, (Object) new UUID[] {id1});  // ok, but a List reads better
```

## Enums

A Java `enum` binds by its name, which is exactly the binary representation a Postgres `enum` value
takes, so it works against an `enum`-typed column (and against a `text` column):

```java
enum Mood { happy, sad, meh }

// WHERE mood = $1   (mood is a Postgres enum type)
PeopleByMoodQuery.run(arena, conn, Mood.sad);
```

The Java constant name must match the Postgres enum label.

## Prepared statements

When you hold a connection and run the same SQL repeatedly (a bulk insert, an update loop, a hot
query inside a transaction), prepare it once so Postgres parses and plans it a single time, then
execute it many times:

```java
var conn = pool.lease().getOrThrow();
try {
  Prepared insert = Prepared.create(conn,
      "INSERT INTO widgets (id, name) VALUES ($1, $2)").getOrThrow();
  for (var w : widgets) {
    Pg.clear(insert.execute(w.id(), w.name()).getOrThrow()); // bind + run, no re-parse
  }
} finally {
  pool.release(conn);
}
```

`execute` binds through the same `PgParam` path, so arrays and enums work as parameters here too. A
success carries the binary result handle, which you `Pg.clear` (or read through a generated
`<Name>Reader` first); a failure carries its SQLSTATE, so a prepared statement run inside
[`Tx`](/guides/transactions/) takes part in the same retry of transient conflicts.

A prepared statement belongs to **one connection**. It is valid while you hold that connection, not
across leases: when the connection returns to the pool, the pool clears its session state and the
statement is deallocated. So prepare inside the scope that reuses the connection (a held lease or a
transaction). Caching a statement across leases would need the pool to preserve plans on reset, which
it does not do today.
