/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs a unit of work inside a single database transaction, committing on success and rolling back on
 * failure, and automatically retrying the whole unit when Postgres reports a transient conflict
 * (serialization failure or deadlock). These conflicts are not bugs: under {@code SERIALIZABLE} or
 * {@code REPEATABLE READ}, the correct response is to roll back and run the transaction again, which is
 * exactly what every transaction at those levels must be prepared to do.
 *
 * <pre>{@code
 * var result = Tx.tx(conn, c -> {
 *   var p = PgParam.bind(arena, fromId, amount);
 *   return Pg.exec(...).flatMap(ignored -> ...); // any fallible work, returning a Result
 * });
 * }</pre>
 *
 * <p>Retrying is keyed on the {@code SQLSTATE} ({@code 40001} serialization failure, {@code 40P01}
 * deadlock detected) that {@link Pg} attaches to a failure as a {@link PgSqlException}, so the
 * decision does not depend on the wording of an error message. Any other failure (a constraint
 * violation, a syntax error, an application-level {@code Result.failure}) is returned as-is, rolled
 * back, with no retry. A failed {@code COMMIT} (a serialization conflict can surface only then) is
 * treated the same as a failure during the work and is retried if transient.
 */
public final class Tx {

  /** {@code SQLSTATE}s that warrant retrying the whole transaction. */
  private static final Set<String> RETRYABLE = Set.of("40001", "40P01");

  /** A fallible unit of work to run inside a transaction, given the same connection. */
  @FunctionalInterface
  public interface Work<T> {
    Result<T> run(MemorySegment conn);
  }

  /** How many attempts to make, and the base backoff between them (grown linearly per attempt). */
  public record Retry(int maxAttempts, Duration backoff) {
    public Retry {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
      }
      Objects.requireNonNull(backoff, "backoff");
      if (backoff.isNegative()) {
        throw new IllegalArgumentException("backoff must not be negative: " + backoff);
      }
    }

    /** Three attempts, 25ms base backoff: a sensible default for contended writes. */
    public static final Retry DEFAULT = new Retry(3, Duration.ofMillis(25));
  }

  /** Run {@code work} in a transaction with the {@link Retry#DEFAULT default} retry policy. */
  public static <T> Result<T> tx(MemorySegment conn, Work<T> work) {
    return tx(conn, Retry.DEFAULT, work);
  }

  /** Run {@code work} in a transaction, retrying a transient conflict per {@code retry}. */
  public static <T> Result<T> tx(MemorySegment conn, Retry retry, Work<T> work) {
    Result<T> result = runOnce(conn, work);
    int attempt = 1;
    while (attempt < retry.maxAttempts() && isRetryable(result)) {
      LockSupport.parkNanos(retry.backoff().multipliedBy(attempt).toNanos());
      attempt++;
      result = runOnce(conn, work);
    }
    return result;
  }

  private static <T> Result<T> runOnce(MemorySegment conn, Work<T> work) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "BEGIN").getOrThrow();
      Result<T> outcome = work.run(conn);
      // Commit a success, roll back a failure. A failed COMMIT (Postgres has already rolled the
      // transaction back) replaces the outcome, so a conflict raised only at commit is still retried.
      Result<Void> end = Pg.exec(a, conn, outcome.isSuccess() ? "COMMIT" : "ROLLBACK");
      return outcome.isSuccess() && end instanceof Result.Failure<Void> failed
          ? Result.failure(failed.error(), failed.cause())
          : outcome;
    }
  }

  private static boolean isRetryable(Result<?> result) {
    return result instanceof Result.Failure<?> failed
        && failed.cause() instanceof PgSqlException sql
        && RETRYABLE.contains(sql.sqlState());
  }

  private Tx() {}
}
