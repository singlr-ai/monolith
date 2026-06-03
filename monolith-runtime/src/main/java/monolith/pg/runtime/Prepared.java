/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A statement parsed and planned once on a connection, then run many times with different parameters
 * and no re-parsing. Use it when you hold a connection and run the same SQL repeatedly (a bulk insert,
 * an update loop, a hot query inside a transaction):
 *
 * <pre>{@code
 * var conn = pool.lease().getOrThrow();
 * try {
 *   Prepared insert = Prepared.create(conn,
 *       "INSERT INTO widgets (id, name) VALUES ($1, $2)").getOrThrow();
 *   for (var w : widgets) {
 *     Pg.clear(insert.execute(w.id(), w.name()).getOrThrow()); // bind + run, no re-parse
 *   }
 * } finally {
 *   pool.release(conn); // the statement is freed when the pool resets the connection
 * }
 * }</pre>
 *
 * <p>A prepared statement belongs to the connection it was created on, so a {@code Prepared} carries
 * that connection and runs there. It is a thin handle over the {@link PreparedCache}, so the plan is
 * prepared once per connection and reused: across a {@link PgPool} checkout it survives (the pool's
 * reset keeps prepared statements), and creating a {@code Prepared} for SQL already prepared on the
 * connection reuses the same plan rather than allocating another. Parameters bind exactly as in
 * {@link PgParam} (arrays and enums included).
 */
public final class Prepared {

  private final MemorySegment conn;
  private final String sql;
  private final String name;

  private Prepared(MemorySegment conn, String sql, String name) {
    this.conn = conn;
    this.sql = sql;
    this.name = name;
  }

  /** Prepares {@code sql} on {@code conn}, or a {@link Result.Failure} if it cannot be parsed/planned. */
  public static Result<Prepared> create(MemorySegment conn, String sql) {
    try (Arena arena = Arena.ofConfined()) {
      return PreparedCache.prepareOn(arena, conn, sql).map(name -> new Prepared(conn, sql, name));
    }
  }

  /**
   * Binds {@code params} and runs the statement. A {@link Result.Success} carries the binary result
   * handle, which the caller must {@link Pg#clear}; a SQL error is a {@link Result.Failure} carrying
   * its SQLSTATE, so it composes with {@link Tx}'s retry of transient conflicts.
   */
  public Result<MemorySegment> execute(Object... params) {
    try (Arena arena = Arena.ofConfined()) {
      var bound = PgParam.bind(arena, params);
      return PreparedCache.execute(arena, conn, sql, bound);
    }
  }

  /** The generated statement name, unique within the process. */
  public String name() {
    return name;
  }
}
