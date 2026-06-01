# Schema migrations

The codegen emits the DDL for a `@PgType`, but a real schema evolves: you add columns, tables,
indexes, constraints. `Migrator` applies those changes in order, once each, and records them, so the
same code can run against a fresh database or one that's three versions in.

## Writing and applying migrations

A migration is a strictly-increasing version, a name (for diagnostics), and its SQL:

```java
var migrations = List.of(
    new Migrator.Migration(1, "create_widgets",
        "CREATE TABLE widgets (id uuid PRIMARY KEY, name text NOT NULL)"),
    new Migrator.Migration(2, "add_color",
        "ALTER TABLE widgets ADD COLUMN color text"),
    new Migrator.Migration(3, "index_name",
        "CREATE INDEX widgets_name_idx ON widgets (name)"));

var conn = pool.lease().getOrThrow();
try {
  Migrator.migrate(conn, migrations).getOrThrow(); // applies pending ones, in order
} finally {
  pool.release(conn);
}
```

Run it at startup. The first run applies all three; the next run applies nothing (they're recorded);
adding a `version 4` applies only that one. Each migration runs in its own transaction (Postgres DDL
is transactional), so a failure rolls back cleanly and no later migration runs.

The `Migration` is just a record, so load them however you like: inline as above, from classpath
resources, from `V<n>__<name>.sql` files you parse, etc. Monolith doesn't impose a layout.

## What it records and guards

`migrate` creates and maintains a `monolith_migrations` table:

| column | meaning |
|---|---|
| `version` | the applied version (primary key) |
| `name` | the migration's name |
| `checksum` | a CRC32 of the SQL, to detect later edits |
| `applied_at` | when it ran |

It refuses to proceed, with a clear `Result.Failure`, when:

- an already-applied migration's **SQL has changed** since it ran (someone edited history);
- a migration is **older than the latest applied version** (a gap was filled out of order);
- a migration **fails** (its transaction is rolled back, the run stops, earlier committed ones stay).

## The `schema.lock` connection

When you change a `@PgType`'s shape, the processor's `schema.lock` changes, and the CI check fails
the build, because the binary wire layout moved. That's your signal: write the migration that brings
the database in line (`ALTER TABLE ...`) and bump the version here. `schema.lock` catches the drift
at build time; `Migrator` applies the fix at run time.
