/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.PreparedCache;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@link PreparedCache} against real Postgres: a plan is prepared once per connection and
 * reused, the reuse survives a pool checkout (the whole point of the gentle reset), and session
 * isolation still holds. Skips when no database is reachable.
 */
@DisplayName("PreparedCache against real Postgres")
class PreparedCacheIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final String SELECT = "SELECT name FROM pc_widgets WHERE id = $1";

  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment conn;
  private static boolean available;

  @BeforeAll
  static void connect() {
    try {
      Result<MemorySegment> c = Pg.connect(ARENA, CONNINFO);
      if (c.isFailure()) return;
      conn = c.getOrThrow();
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (conn != null) Pg.finish(conn);
    ARENA.close();
  }

  @BeforeEach
  void freshState() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    exec(conn, "DEALLOCATE ALL");      // clear server-side prepared statements from a prior test
    PreparedCache.forget(conn);        // and the client-side cache for this connection
    exec(conn, "DROP TABLE IF EXISTS pc_widgets");
    exec(conn, "CREATE TABLE pc_widgets (id int PRIMARY KEY, name text NOT NULL)");
    exec(conn, "INSERT INTO pc_widgets VALUES (1, 'one'), (2, 'two')");
  }

  @Test
  @DisplayName("prepares once on a connection and reuses the plan")
  void preparesOnceAndReuses() {
    assertEquals("one", run(conn, 1));
    assertEquals(1, preparedCount(conn), "the first run should prepare the statement");

    assertEquals("two", run(conn, 2)); // a cache hit: no second prepare
    assertEquals(1, preparedCount(conn), "the second run should reuse the prepared plan");
  }

  @Test
  @DisplayName("a statement that cannot be parsed fails without preparing")
  void propagatesAPrepareFailure() {
    try (Arena a = Arena.ofConfined()) {
      var bound = PgParam.bind(a, 1);
      Result<MemorySegment> result = PreparedCache.execute(a, conn, "SELECT not valid $1 from", bound);
      assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("prepare failed"));
    }
  }

  @Test
  @DisplayName("forgetting a connection forces a re-prepare")
  void forgetForcesARePrepare() {
    run(conn, 1);
    assertEquals(1, preparedCount(conn));

    PreparedCache.forget(conn);
    run(conn, 1); // the client cache is gone, so it prepares again under a new name

    assertEquals(2, preparedCount(conn), "after forget, the statement should be prepared anew");
  }

  @Test
  @DisplayName("a prepared plan survives a pool checkout while session state is reset")
  void survivesAPoolCheckout() {
    PgPool pool = new PgPool(CONNINFO, 1);
    try {
      MemorySegment first = pool.lease().getOrThrow();
      assertEquals("one", run(first, 1));        // prepares on this connection
      assertEquals(1, preparedCount(first));
      exec(first, "CREATE TEMP TABLE iso_check (x int)"); // session state that must NOT survive
      pool.release(first);                        // gentle reset: keep the plan, drop the temp table

      MemorySegment second = pool.lease().getOrThrow(); // the same physical connection
      assertEquals(1, preparedCount(second), "the prepared plan must survive the checkout");
      assertTrue(queryFails(second, "SELECT count(*) FROM iso_check"),
          "the temp table must be gone: session isolation still holds");
      assertEquals("two", run(second, 2));        // a cache hit on the reused connection
      pool.release(second);
    } finally {
      pool.close();
    }
  }

  // ---- helpers ----

  private static String run(MemorySegment c, int id) {
    try (Arena a = Arena.ofConfined()) {
      var bound = PgParam.bind(a, id);
      MemorySegment res = PreparedCache.execute(a, c, SELECT, bound).getOrThrow();
      try {
        return new String(Pg.getbytes(res, 0, 0), StandardCharsets.UTF_8);
      } finally {
        Pg.clear(res);
      }
    }
  }

  private static int preparedCount(MemorySegment c) {
    try (Arena a = Arena.ofConfined()) {
      return Integer.parseInt(
          Pg.textColumn(a, c, "SELECT count(*) FROM pg_prepared_statements").getOrThrow().get(0));
    }
  }

  private static boolean queryFails(MemorySegment c, String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, c, sql).isFailure();
    }
  }

  private static void exec(MemorySegment c, String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, c, sql).getOrThrow();
    }
  }
}
