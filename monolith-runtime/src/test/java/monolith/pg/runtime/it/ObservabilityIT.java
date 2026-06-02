/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import monolith.pg.runtime.MonolithEvent;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Tx;
import monolith.pg.runtime.Tx.Retry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves Tx and PgPool emit the right observability events. Skips when no database is reachable. */
@DisplayName("Observability wiring against real Postgres")
class ObservabilityIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment conn;
  private static boolean available;

  private final List<MonolithEvent> events = Collections.synchronizedList(new ArrayList<>());

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
  void installObserver() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    exec("DROP TABLE IF EXISTS obs_ok, obs_retry");
    Observability.use(events::add);
  }

  @AfterEach
  void removeObserver() {
    Observability.reset();
  }

  @Test
  @DisplayName("a committed transaction emits TransactionCommitted")
  void emitsCommitted() {
    Tx.tx(conn, c -> execIn(c, "CREATE TABLE obs_ok (id int)")).getOrThrow();

    var committed = only(MonolithEvent.TransactionCommitted.class);
    assertEquals(1, committed.attempts());
  }

  @Test
  @DisplayName("a retried transaction emits TransactionRetried then TransactionCommitted")
  void emitsRetriedThenCommitted() {
    int[] attempts = {0};
    Tx.tx(conn, new Retry(3, Duration.ZERO), c -> {
      attempts[0]++;
      return attempts[0] == 1
          ? execIn(c, "DO $$ BEGIN RAISE EXCEPTION 'conflict' USING ERRCODE = '40001'; END $$")
          : execIn(c, "CREATE TABLE obs_retry (id int)");
    }).getOrThrow();

    var retried = only(MonolithEvent.TransactionRetried.class);
    assertEquals(1, retried.attempt());
    assertEquals("40001", retried.sqlState());
    assertEquals(2, only(MonolithEvent.TransactionCommitted.class).attempts());
  }

  @Test
  @DisplayName("a non-transient failure emits TransactionRolledBack with the SQLSTATE")
  void emitsRolledBackWithSqlState() {
    Tx.tx(conn, c -> execIn(c, "INSERT INTO this_table_does_not_exist VALUES (1)"));

    var rolledBack = only(MonolithEvent.TransactionRolledBack.class);
    assertEquals(1, rolledBack.attempts());
    assertEquals("42P01", rolledBack.sqlState(), "undefined_table");
  }

  @Test
  @DisplayName("an application failure emits TransactionRolledBack with no SQLSTATE")
  void emitsRolledBackForApplicationFailure() {
    Tx.tx(conn, c -> Result.<String>failure("the application said no"));

    assertEquals("", only(MonolithEvent.TransactionRolledBack.class).sqlState());
  }

  @Test
  @DisplayName("leasing a connection emits ConnectionLeased")
  void emitsConnectionLeased() {
    try (PgPool pool = new PgPool(CONNINFO, 1)) {
      MemorySegment c = pool.lease().getOrThrow();
      pool.release(c);
    }
    assertTrue(only(MonolithEvent.ConnectionLeased.class).waitNanos() >= 0);
  }

  @Test
  @DisplayName("an exhausted pool emits PoolExhausted")
  void emitsPoolExhausted() {
    try (PgPool pool = new PgPool(CONNINFO, 1, Duration.ofMillis(50))) {
      MemorySegment held = pool.lease().getOrThrow();
      pool.lease(); // no connection free: exhausted
      pool.release(held);
    }
    assertEquals(Duration.ofMillis(50), only(MonolithEvent.PoolExhausted.class).waited());
  }

  // ---- helpers ----

  private <E extends MonolithEvent> E only(Class<E> type) {
    var matches = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matches.size(), () -> "expected exactly one " + type.getSimpleName() + " in " + events);
    return matches.get(0);
  }

  private static Result<Void> execIn(MemorySegment c, String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, c, sql);
    }
  }

  private static void exec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    }
  }
}
