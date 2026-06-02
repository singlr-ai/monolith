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
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgSqlException;
import monolith.pg.runtime.Prepared;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises prepared statements against real Postgres. Skips when no database is reachable. */
@DisplayName("Prepared against real Postgres")
class PreparedIT {

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
    exec("DROP TABLE IF EXISTS pr_widgets, pr_unique");
  }

  @Test
  @DisplayName("prepares once and executes many times with different parameters")
  void preparesAndExecutesRepeatedly() {
    exec("CREATE TABLE pr_widgets (id int, name text)");
    Prepared insert = Prepared.create(conn, "INSERT INTO pr_widgets (id, name) VALUES ($1, $2)").getOrThrow();
    assertTrue(insert.name().startsWith("monolith_p"));

    for (int i = 1; i <= 3; i++) {
      Pg.clear(insert.execute(i, "w" + i).getOrThrow());
    }

    assertEquals(3, count("SELECT count(*) FROM pr_widgets"));
  }

  @Test
  @DisplayName("a statement that cannot be parsed fails to prepare")
  void createFailsOnInvalidSql() {
    var result = Prepared.create(conn, "INSERT INTO this is not valid sql");

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("prepare failed"));
  }

  @Test
  @DisplayName("an execution error carries its SQLSTATE, so it composes with Tx retry")
  void executeCarriesSqlState() {
    exec("CREATE TABLE pr_unique (id int PRIMARY KEY)");
    Prepared insert = Prepared.create(conn, "INSERT INTO pr_unique (id) VALUES ($1)").getOrThrow();
    Pg.clear(insert.execute(1).getOrThrow());

    var duplicate = insert.execute(1); // violates the primary key

    var failure = assertInstanceOf(Result.Failure.class, duplicate);
    var cause = assertInstanceOf(PgSqlException.class, failure.cause());
    assertEquals("23505", cause.sqlState(), "unique_violation");
  }

  // ---- helpers ----

  private static int count(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Integer.parseInt(Pg.textColumn(a, conn, sql).getOrThrow().get(0));
    }
  }

  private static void exec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    }
  }
}
