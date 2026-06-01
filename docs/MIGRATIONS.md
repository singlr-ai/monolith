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
  Migrator.Outcome outcome = Migrator.migrate(conn, migrations).getOrThrow(); // pending ones, in order
  System.out.println("applied versions: " + outcome.appliedVersions());
} finally {
  pool.release(conn);
}
```

Run it at startup. The first run applies all three; the next run applies nothing (they're recorded);
adding a `version 4` applies only that one. Each migration runs in its own transaction (Postgres DDL
is transactional), so a failure rolls back cleanly and no later migration runs.

The `Migration` is just a record, so load them however you like: inline as above, from classpath
resources, from `V<n>__<name>.sql` files you parse, etc. Monolith doesn't impose a layout.

## It is forward-only, on purpose

There are no "down" migrations, and that is a deliberate choice, not a missing feature. A down script
can't restore data a change dropped (drop a column and the down re-adds the column, but the data is
gone), so it offers false safety for exactly the changes where you'd want it; and down scripts rot,
untested, until the 2 a.m. emergency when you least want to run untested code. This is the same stance
as Flyway's free edition. **Roll forward:** if a deploy is bad, write a new migration that fixes it,
and use the database's point-in-time recovery for true rollback of a destructive mistake.

## Seeing what would run, first

`Migrator.status(conn, migrations)` computes the plan without touching the schema, so you can log it
at startup or gate a deploy in CI:

```java
Migrator.Status status = Migrator.status(conn, migrations).getOrThrow();
status.applied();   // versions already recorded
status.pending();   // the Migrations that migrate() would apply next, in order
status.problems();  // human-readable strings: edited or out-of-order migrations
if (!status.isClean()) throw new IllegalStateException(String.join("; ", status.problems()));
```

## Repeatable migrations (views, functions, procedures)

Some objects you *replace* rather than version: a view, a function, a stored procedure. A
`RepeatableMigration` is re-applied automatically whenever its SQL changes (by checksum), after all
versioned migrations, in name order. You keep one canonical `CREATE OR REPLACE`, and edits just take:

```java
var repeatables = List.of(
    new Migrator.RepeatableMigration("active_orders",
        "CREATE OR REPLACE VIEW active_orders AS SELECT * FROM orders WHERE status = 'open'"));

Migrator.migrate(conn, migrations, repeatables).getOrThrow();
// outcome.appliedRepeatables() lists the ones that ran this time (empty when nothing changed)
```

Repeatables are tracked in their own `monolith_repeatable` table (name, checksum, applied_at).

## Adopting an existing database (baseline)

To bring Monolith to a database that already has tables, `baseline` marks a starting version so
`migrate` ignores everything at or below it and only applies what comes after:

```java
Migrator.baseline(conn, 7, "existing_schema").getOrThrow(); // versions <= 7 are considered handled
Migrator.migrate(conn, migrations).getOrThrow();            // applies only versions 8+
```

## What it records and guards

`migrate` creates and maintains a `monolith_migrations` table:

| column | meaning |
|---|---|
| `version` | the applied version (primary key) |
| `name` | the migration's name |
| `checksum` | a CRC32 of the SQL, to detect later edits |
| `applied_at` | when it ran |

Repeatable migrations are tracked separately in `monolith_repeatable` (name, checksum, applied_at).

It refuses to proceed, with a clear `Result.Failure`, when:

- an already-applied migration's **SQL has changed** since it ran (someone edited history);
- a migration is **older than the latest applied version** (a gap was filled out of order);
- a migration **fails** (its transaction is rolled back, the run stops, earlier committed ones stay).

The same checks are what `status` reports as `problems` before you run anything.

## The `schema.lock` connection

When you change a `@PgType`'s shape, the processor's `schema.lock` changes, and the CI check fails
the build, because the binary wire layout moved. That's your signal: write the migration that brings
the database in line (`ALTER TABLE ...`) and bump the version here. `schema.lock` catches the drift
at build time; `Migrator` applies the fix at run time.
