# Transactions

`Tx.tx` runs a unit of work inside a single database transaction: it issues `BEGIN`, runs your work,
and then `COMMIT`s a success or `ROLLBACK`s a failure. If Postgres reports a transient conflict, it
rolls back and runs the whole unit again.

```java
var conn = pool.lease().getOrThrow();
try {
  Result<String> result = Tx.tx(conn, c -> {
    try (var arena = Arena.ofConfined()) {
      return Pg.exec(arena, c, "UPDATE accounts SET balance = balance - 100 WHERE id = 1")
          .flatMap(ignored ->
              Pg.exec(arena, c, "UPDATE accounts SET balance = balance + 100 WHERE id = 2"))
          .map(ignored -> "transferred");
    }
  });
  // both updates committed together, or neither did
} finally {
  pool.release(conn);
}
```

The work is a `Tx.Work<T>`: it receives the connection and returns a `Result<T>`. A `Result.success`
commits and the value comes back; a `Result.failure` (or a failed statement) rolls back and the
failure comes back unchanged.

## Why it retries, and only sometimes

Under `SERIALIZABLE` or `REPEATABLE READ`, Postgres can refuse to commit a transaction that would
break isolation, and the documented, correct response is to **run the transaction again**. Deadlocks
are the same: one transaction is chosen as the victim and is expected to retry. These are not bugs in
your code, they are the price of those isolation levels, so `Tx` handles them for you: up to three
attempts by default, with a small linear backoff between them.

It retries **only** these transient conflicts, identified by their Postgres `SQLSTATE`:

| SQLSTATE | meaning |
|---|---|
| `40001` | serialization failure |
| `40P01` | deadlock detected |

Everything else (a unique-constraint violation, a syntax error, an application-level
`Result.failure`) is **not** retried. Retrying a constraint violation would just fail again; the
failure is yours to handle, so it comes straight back.

A conflict can surface only at `COMMIT` time (this is common under `SERIALIZABLE`). `Tx` treats a
failed commit exactly like a failure during the work, so those conflicts are retried too.

The classification is keyed on the `SQLSTATE` code, not the wording of the error message. `Pg`
attaches the code to every failure as a [`PgSqlException`](../monolith-runtime/src/main/java/monolith/pg/runtime/PgSqlException.java)
in the `Result.Failure`'s `cause`, so it stays correct regardless of server locale.

## Tuning the policy

```java
var policy = new Tx.Retry(5, Duration.ofMillis(10)); // up to 5 attempts, 10ms base backoff
Tx.tx(conn, policy, work);
```

The backoff grows linearly with the attempt number (`base`, `2 * base`, ...). Set the backoff to
`Duration.ZERO` to retry immediately. `Tx.Retry.DEFAULT` is three attempts with a 25ms base.

## One connection, one transaction

`Tx` does not lease or pool connections; you hand it the one you want the transaction to run on, and
every statement in the work must use that same connection. This keeps it composable with the pool,
with `PgSession` (which sets the tenant and actor for the transaction), and with sharding: lease,
optionally open a session, run `tx`, release.
