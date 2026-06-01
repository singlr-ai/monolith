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
import java.util.List;
import monolith.pg.runtime.Migrator;
import monolith.pg.runtime.Migrator.Migration;
import monolith.pg.runtime.Migrator.RepeatableMigration;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the migration runner against real Postgres. Skips when no database is reachable. */
@DisplayName("Migrator against real Postgres")
class MigratorIT {

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
    exec("SET default_transaction_read_only = off");
    exec("DROP VIEW IF EXISTS mt_view");
    exec("DROP TABLE IF EXISTS monolith_migrations, monolith_repeatable,"
        + " mt_a, mt_b, mt_one, mt_ok, mt_never, mt_three, mt_six");
  }

  @Test
  @DisplayName("applies pending migrations in version order and records them")
  void appliesInOrder() {
    var applied = Migrator.migrate(conn, List.of(
        new Migration(2, "create_b", "CREATE TABLE mt_b (id int)"),  // given out of order
        new Migration(1, "create_a", "CREATE TABLE mt_a (id int)")));

    assertEquals(List.of(1, 2), applied.getOrThrow().appliedVersions());
    assertTrue(tableExists("mt_a"));
    assertTrue(tableExists("mt_b"));
    assertEquals(2, count("SELECT count(*) FROM monolith_migrations"));
  }

  @Test
  @DisplayName("re-running applies nothing new")
  void isIdempotent() {
    var migrations = List.of(new Migration(1, "create_a", "CREATE TABLE mt_a (id int)"));
    assertEquals(List.of(1), Migrator.migrate(conn, migrations).getOrThrow().appliedVersions());
    assertEquals(List.of(), Migrator.migrate(conn, migrations).getOrThrow().appliedVersions());
  }

  @Test
  @DisplayName("a migration edited after it was applied is rejected")
  void detectsAModifiedMigration() {
    Migrator.migrate(conn, List.of(new Migration(1, "a", "CREATE TABLE mt_a (id int)"))).getOrThrow();

    var result = Migrator.migrate(conn, List.of(new Migration(1, "a", "CREATE TABLE mt_a (id int, c int)")));

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("modified"));
  }

  @Test
  @DisplayName("a migration older than the latest applied is rejected")
  void rejectsOutOfOrder() {
    Migrator.migrate(conn, List.of(
        new Migration(1, "a", "CREATE TABLE mt_a (id int)"),
        new Migration(3, "b", "CREATE TABLE mt_b (id int)"))).getOrThrow();

    var result = Migrator.migrate(conn, List.of(
        new Migration(2, "late", "CREATE TABLE mt_one (id int)")));

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("older than"));
  }

  @Test
  @DisplayName("a failing migration rolls back and stops the run")
  void failsAndRollsBack() {
    var result = Migrator.migrate(conn, List.of(
        new Migration(1, "ok", "CREATE TABLE mt_ok (id int)"),
        new Migration(2, "bad", "CREATE TABLE this is not valid sql"),
        new Migration(3, "never", "CREATE TABLE mt_never (id int)")));

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("failed"));
    assertTrue(tableExists("mt_ok"), "the committed earlier migration survives");
    assertFalse(tableExists("mt_never"), "a later migration after the failure does not run");
    assertEquals(1, count("SELECT count(*) FROM monolith_migrations"), "only the good one is recorded");
  }

  @Test
  @DisplayName("a duplicate version is rejected and rolled back, leaving the connection usable")
  void rejectsDuplicateVersionCleanly() {
    var result = Migrator.migrate(conn, List.of(
        new Migration(1, "a", "CREATE TABLE mt_a (id int)"),
        new Migration(1, "b", "CREATE TABLE mt_b (id int)"))); // same version

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("recorded"));
    assertTrue(tableExists("mt_a"), "the first applied");
    assertFalse(tableExists("mt_b"), "the duplicate rolled back");
    // the connection is not stuck in an aborted transaction: a normal query still works
    assertEquals(1, count("SELECT count(*) FROM monolith_migrations"));
  }

  @Test
  @DisplayName("a clear failure when the tracking table cannot be created")
  void failsWhenTrackingTableCannotBeCreated() {
    exec("SET default_transaction_read_only = on"); // makes the CREATE TABLE fail
    try {
      var result = Migrator.migrate(conn, List.of(new Migration(1, "x", "CREATE TABLE mt_a (id int)")));
      assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("monolith_migrations"));
    } finally {
      exec("SET default_transaction_read_only = off");
    }
  }

  @Test
  @DisplayName("status reports applied, pending, and is clean before running")
  void statusReportsPendingWithoutApplying() {
    Migrator.migrate(conn, List.of(new Migration(1, "a", "CREATE TABLE mt_a (id int)"))).getOrThrow();

    var status = Migrator.status(conn, List.of(
        new Migration(1, "a", "CREATE TABLE mt_a (id int)"),
        new Migration(2, "b", "CREATE TABLE mt_b (id int)"))).getOrThrow();

    assertEquals(List.of(1), status.applied());
    assertEquals(List.of(2), status.pending().stream().map(Migration::version).toList());
    assertTrue(status.isClean());
    assertFalse(tableExists("mt_b"), "status applies nothing");
  }

  @Test
  @DisplayName("status surfaces a problem and is not clean")
  void statusFlagsProblems() {
    Migrator.migrate(conn, List.of(new Migration(1, "a", "CREATE TABLE mt_a (id int)"))).getOrThrow();

    var status = Migrator.status(conn, List.of(
        new Migration(1, "a", "CREATE TABLE mt_a (id int, c int)"))).getOrThrow(); // edited

    assertFalse(status.isClean());
    assertTrue(status.problems().get(0).contains("modified"));
  }

  @Test
  @DisplayName("status fails clearly when the tracking table cannot be created")
  void statusFailsWhenTrackingUnavailable() {
    exec("SET default_transaction_read_only = on");
    try {
      var result = Migrator.status(conn, List.of(new Migration(1, "a", "CREATE TABLE mt_a (id int)")));
      assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("monolith_migrations"));
    } finally {
      exec("SET default_transaction_read_only = off");
    }
  }

  @Test
  @DisplayName("a repeatable runs once, is skipped while unchanged, and re-runs when its SQL changes")
  void repeatableReappliesOnChange() {
    var first = Migrator.migrate(conn, List.of(),
        List.of(new RepeatableMigration("the_view", "CREATE OR REPLACE VIEW mt_view AS SELECT 1 AS x")))
        .getOrThrow();
    assertEquals(List.of("the_view"), first.appliedRepeatables());
    assertEquals("1", text("SELECT x FROM mt_view"));

    var unchanged = Migrator.migrate(conn, List.of(),
        List.of(new RepeatableMigration("the_view", "CREATE OR REPLACE VIEW mt_view AS SELECT 1 AS x")))
        .getOrThrow();
    assertEquals(List.of(), unchanged.appliedRepeatables(), "unchanged repeatable is skipped");

    var changed = Migrator.migrate(conn, List.of(),
        List.of(new RepeatableMigration("the_view", "CREATE OR REPLACE VIEW mt_view AS SELECT 2 AS x")))
        .getOrThrow();
    assertEquals(List.of("the_view"), changed.appliedRepeatables());
    assertEquals("2", text("SELECT x FROM mt_view"), "the changed repeatable re-ran");
  }

  @Test
  @DisplayName("a failing repeatable rolls back with a clear error")
  void repeatableFailureRollsBack() {
    var result = Migrator.migrate(conn, List.of(),
        List.of(new RepeatableMigration("bad", "CREATE OR REPLACE VIEW this is not valid sql")));

    assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("repeatable bad failed"));
    assertEquals(0, count("SELECT count(*) FROM monolith_repeatable"), "nothing recorded");
  }

  @Test
  @DisplayName("baseline skips versions at or below it and applies the ones above")
  void baselineSkipsBelowAndAppliesAbove() {
    Migrator.baseline(conn, 5, "legacy_schema").getOrThrow();

    var applied = Migrator.migrate(conn, List.of(
        new Migration(3, "below", "CREATE TABLE mt_three (id int)"),   // <= baseline: skipped
        new Migration(6, "above", "CREATE TABLE mt_six (id int)")))    // > baseline: applied
        .getOrThrow();

    assertEquals(List.of(6), applied.appliedVersions());
    assertFalse(tableExists("mt_three"), "a version at or below the baseline is not applied");
    assertTrue(tableExists("mt_six"), "a version above the baseline is applied");
  }

  @Test
  @DisplayName("baseline is recorded once and rejects a second baseline at the same version")
  void baselineRejectsDuplicate() {
    Migrator.baseline(conn, 5, "legacy_schema").getOrThrow();

    var again = Migrator.baseline(conn, 5, "again");

    assertTrue(assertInstanceOf(Result.Failure.class, again).error().contains("baseline at version 5"));
  }

  @Test
  @DisplayName("baseline fails clearly when the tracking table cannot be created")
  void baselineFailsWhenTrackingUnavailable() {
    exec("SET default_transaction_read_only = on");
    try {
      var result = Migrator.baseline(conn, 1, "x");
      assertTrue(assertInstanceOf(Result.Failure.class, result).error().contains("monolith_migrations"));
    } finally {
      exec("SET default_transaction_read_only = off");
    }
  }

  // ---- helpers ----

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
