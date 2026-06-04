/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Tx;
import monolith.pg.runtime.Tx.Retry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the transaction helper against real Postgres. Skips when no database is reachable. */
@DisplayName("Tx against real Postgres")
class TxIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

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
  void clean() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    exec("DROP TABLE IF EXISTS tx_ok, tx_rb, tx_retry, tx_deferred, tx_throw");
  }

  @Test
  @DisplayName("commits on success and returns the work's value")
  void commitsAndReturnsValue() {
    var result = Tx.tx(conn, c -> execIn(c, "CREATE TABLE tx_ok (id int)").map(v -> "created"));

    assertEquals("created", result.getOrThrow());
    assertTrue(tableExists("tx_ok"));
  }

  @Test
  @DisplayName("rolls back when the work returns an application failure")
  void rollsBackOnApplicationFailure() {
    var result = Tx.tx(conn, c -> {
      execIn(c, "CREATE TABLE tx_rb (id int)").getOrThrow(); // happens inside the transaction
      return Result.<String>failure("the application said no");
    });

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("said no"));
    assertFalse(tableExists("tx_rb"), "the work's writes were rolled back");
  }

  @Test
  @DisplayName("rolls back and propagates when the work throws, leaving no open transaction")
  void rollsBackWhenWorkThrows() {
    var boom = new RuntimeException("the handler blew up");
    RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> Tx.tx(conn, c -> {
          execIn(c, "CREATE TABLE tx_throw (id int)").getOrThrow(); // happens inside the transaction
          throw boom;
        }));

    org.junit.jupiter.api.Assertions.assertSame(boom, thrown, "the original exception propagates");
    try (Arena a = Arena.ofConfined()) {
      assertEquals(Pg.PQTRANS_IDLE, Pg.transactionStatus(conn),
          "a thrown unit of work must be rolled back, leaving the connection idle and reusable");
    }
    assertFalse(tableExists("tx_throw"), "the thrown work's writes were rolled back");
  }

  @Test
  @DisplayName("retries a serialization failure and then succeeds")
  void retriesSerializationFailureThenSucceeds() {
    int[] attempts = {0};
    var result = Tx.tx(conn, new Retry(3, Duration.ofMillis(5)), c -> {
      attempts[0]++;
      return attempts[0] == 1
          ? execIn(c, "DO $$ BEGIN RAISE EXCEPTION 'conflict' USING ERRCODE = '40001'; END $$")
          : execIn(c, "CREATE TABLE tx_retry (id int)");
    });

    assertTrue(result.isSuccess());
    assertEquals(2, attempts[0], "the first attempt conflicted, the second committed");
    assertTrue(tableExists("tx_retry"));
  }

  @Test
  @DisplayName("does not retry a non-transient failure (a constraint or syntax error)")
  void doesNotRetryNonTransient() {
    int[] attempts = {0};
    var result = Tx.tx(conn, c -> {
      attempts[0]++;
      return execIn(c, "INSERT INTO this_table_does_not_exist VALUES (1)");
    });

    assertTrue(result.isFailure());
    assertEquals(1, attempts[0], "a non-transient failure is returned at once, without retrying");
  }

  @Test
  @DisplayName("gives up after the configured number of attempts")
  void stopsAfterMaxAttempts() {
    int[] attempts = {0};
    var result = Tx.tx(conn, new Retry(2, Duration.ZERO), c -> {
      attempts[0]++;
      return execIn(c, "DO $$ BEGIN RAISE EXCEPTION 'still conflicting' USING ERRCODE = '40001'; END $$");
    });

    assertTrue(result.isFailure());
    assertEquals(2, attempts[0], "it stopped at the attempt limit");
  }

  @Test
  @DisplayName("surfaces a failure that only the COMMIT raises (a deferred constraint)")
  void surfacesCommitFailure() {
    exec("CREATE TABLE tx_deferred (id int,"
        + " CONSTRAINT tx_deferred_uq UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)");
    exec("INSERT INTO tx_deferred VALUES (1)");

    // The duplicate insert succeeds (the check is deferred) and the conflict is raised at COMMIT.
    var result = Tx.tx(conn, c -> execIn(c, "INSERT INTO tx_deferred VALUES (1)"));

    assertTrue(result.isFailure());
    assertEquals(1, count("SELECT count(*) FROM tx_deferred"), "the conflicting insert did not persist");
  }

  // ---- helpers ----

  private static Result<Void> execIn(MemorySegment c, String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, c, sql);
    }
  }

  private static boolean tableExists(String table) {
    return "t".equals(text("SELECT to_regclass('public." + table + "') IS NOT NULL"));
  }

  private static int count(String sql) {
    return Integer.parseInt(text(sql));
  }

  private static String text(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, conn, sql).getOrThrow().get(0);
    }
  }

  private static void exec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    }
  }
}
