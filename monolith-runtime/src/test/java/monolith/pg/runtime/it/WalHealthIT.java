/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Wal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises replication-slot health and orphan cleanup against real Postgres. Skips with no database. */
@DisplayName("WAL slot health against real Postgres")
class WalHealthIT {

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
  void requirePostgres() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
  }

  @Test
  @DisplayName("reports the health of an existing, idle slot")
  void reportsAnExistingSlot() {
    Wal.recreate(conn, "monolith_health_it");
    try {
      var health = Wal.health(conn, "monolith_health_it");
      assertTrue(health.exists());
      assertFalse(health.active(), "no consumer is attached");
      assertFalse(health.isLost());
      assertTrue(health.retainedBytes() >= 0);
    } finally {
      Wal.drop(conn, "monolith_health_it");
    }
  }

  @Test
  @DisplayName("reports an absent slot as not present")
  void reportsAnAbsentSlot() {
    var health = Wal.health(conn, "monolith_absent_slot");
    assertFalse(health.exists());
    assertEquals("none", health.walStatus());
  }

  @Test
  @DisplayName("dropInactive reclaims an orphaned (inactive) slot")
  void dropInactiveReclaimsAnOrphan() {
    Wal.recreate(conn, "monolith_orphan_it");
    try {
      assertTrue(Wal.health(conn, "monolith_orphan_it").exists());
      assertTrue(Wal.dropInactive(conn, "monolith_orphan_it"), "an inactive slot is dropped");
      assertFalse(Wal.health(conn, "monolith_orphan_it").exists(), "and is gone");
    } finally {
      Wal.drop(conn, "monolith_orphan_it");
    }
  }

  @Test
  @DisplayName("dropInactive does nothing for an absent slot")
  void dropInactiveIgnoresAnAbsentSlot() {
    assertFalse(Wal.dropInactive(conn, "monolith_absent_slot"));
  }
}
