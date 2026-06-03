/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-connection cache of server-prepared statements, so a hot query is parsed and planned once per
 * connection instead of on every call. The first time a SQL string runs on a connection it is
 * {@code PREPARE}d under a generated name; later runs reuse the plan with no re-parse, which is what
 * closes the gap to a prepared JDBC driver (the generated {@code @PgQuery} path runs through here).
 *
 * <p>The cache is keyed by connection address and stays valid across {@link PgPool} checkouts because
 * the pool's reset preserves prepared statements: it clears session state but not prepared plans. When
 * the pool permanently discards a connection it calls {@link #forget}, so a reused address never
 * inherits a stale plan. The cache is therefore correct for connections handed out by a {@link PgPool};
 * a connection that is reset or closed outside the pool can leave a stale entry behind.
 *
 * <p>Names are bounded: one prepared statement per distinct SQL per connection, not one per call, so the
 * server-side set of prepared statements cannot grow without bound.
 */
public final class PreparedCache {

  private static final AtomicLong COUNTER = new AtomicLong();
  private static final Map<Long, Map<String, String>> NAMES_BY_CONNECTION = new ConcurrentHashMap<>();

  private PreparedCache() {}

  /**
   * Runs {@code sql} on {@code conn} through a cached prepared statement, preparing it the first time it
   * is seen on that connection. A {@link Result.Success} carries the binary result handle (the caller
   * must {@link Pg#clear} it); a SQL error is a {@link Result.Failure} carrying its SQLSTATE, so it
   * composes with {@link Tx}'s retry of transient conflicts exactly like {@link Pg#execParamsBinary}.
   */
  public static Result<MemorySegment> execute(
      Arena arena, MemorySegment conn, String sql, PgParam.Bound bound) {
    return prepareOn(arena, conn, sql).flatMap(name ->
        Pg.execPrepared(arena, conn, name, bound.values(), bound.lengths(), bound.formats()));
  }

  /** The cached statement name for {@code sql} on {@code conn}, preparing it once on the first call. */
  static Result<String> prepareOn(Arena arena, MemorySegment conn, String sql) {
    var names = NAMES_BY_CONNECTION.computeIfAbsent(conn.address(), key -> new ConcurrentHashMap<>());
    var existing = names.get(sql);
    if (existing != null) {
      return Result.success(existing);
    }
    var name = "monolith_p" + COUNTER.incrementAndGet();
    return Pg.prepare(arena, conn, name, sql).map(ignored -> {
      names.put(sql, name);
      return name;
    });
  }

  /** Drop a connection's cached statements; the pool calls this when it permanently discards it. */
  public static void forget(MemorySegment conn) {
    NAMES_BY_CONNECTION.remove(conn.address());
  }
}
