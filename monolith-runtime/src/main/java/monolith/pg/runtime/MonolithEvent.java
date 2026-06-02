/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.time.Duration;

/**
 * Something the runtime did that an observer may care about. A sealed set of records so an adapter can
 * handle them with an exhaustive {@code switch}, mapping each to its own counters, spans, or log
 * lines, and get a compile error when a new event is added.
 */
public sealed interface MonolithEvent {

  /** A transaction committed, on its {@code attempts}-th attempt (1 when it never had to retry). */
  record TransactionCommitted(int attempts) implements MonolithEvent {}

  /** A transaction is about to be retried after a transient conflict with the given {@code sqlState}. */
  record TransactionRetried(int attempt, String sqlState) implements MonolithEvent {}

  /**
   * A transaction was rolled back and not retried, after {@code attempts}. {@code sqlState} is the
   * Postgres code when a statement failed, or {@code ""} for an application-level failure.
   */
  record TransactionRolledBack(int attempts, String sqlState) implements MonolithEvent {}

  /** A connection was leased from the pool after waiting {@code waitNanos} for one to free up. */
  record ConnectionLeased(long waitNanos) implements MonolithEvent {}

  /** No connection freed up within {@code waited}, so the lease gave up. */
  record PoolExhausted(Duration waited) implements MonolithEvent {}
}
