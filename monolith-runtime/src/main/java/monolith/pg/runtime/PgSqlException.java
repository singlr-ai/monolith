/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

/**
 * Carries the Postgres {@code SQLSTATE} of a failed statement, set as the {@code cause} of the
 * {@link Result.Failure} that {@link Pg} returns. The five-character code (e.g. {@code 40001}
 * serialization failure, {@code 40P01} deadlock detected, {@code 23505} unique violation) is stable
 * and locale-independent, unlike the error message, so callers like {@link Tx} can classify a failure
 * reliably instead of matching English text.
 *
 * @see <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">Postgres error codes</a>
 */
public final class PgSqlException extends RuntimeException {

  private final String sqlState;

  public PgSqlException(String sqlState, String message) {
    super(message);
    this.sqlState = sqlState == null ? "" : sqlState;
  }

  /** The five-character {@code SQLSTATE}, or {@code ""} when the driver reported none. */
  public String sqlState() {
    return sqlState;
  }
}
