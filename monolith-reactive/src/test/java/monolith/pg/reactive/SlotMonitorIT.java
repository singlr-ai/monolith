/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import monolith.pg.runtime.MonolithEvent;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Wal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves the slot monitor emits slot health through the observability seam. Skips with no database. */
@DisplayName("SlotMonitor against real Postgres")
class SlotMonitorIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));
  private static final String SLOT = "monolith_monitor_it";

  private static PgPool pool;
  private static boolean available;
  private final List<MonolithEvent> events = Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  static void connect() {
    try {
      pool = new PgPool(CONNINFO, 4);
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.close();
  }

  @BeforeEach
  void installObserverAndSlot() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    onConn(c -> Wal.recreate(c, SLOT));
    Observability.use(events::add);
  }

  @AfterEach
  void cleanup() {
    Observability.reset();
    if (available) onConn(c -> Wal.drop(c, SLOT));
  }

  @Test
  @DisplayName("it emits slot health through the seam while running")
  void emitsSlotHealthThroughTheSeam() {
    try (SlotMonitor ignored = SlotMonitor.start(pool, SLOT, Duration.ofMillis(100))) {
      awaitUntil(() -> events.stream().anyMatch(e -> e instanceof ReactiveEvent.SlotHealthChecked),
          "a SlotHealthChecked event was emitted");
    }
    var checked = events.stream()
        .filter(e -> e instanceof ReactiveEvent.SlotHealthChecked)
        .map(e -> (ReactiveEvent.SlotHealthChecked) e)
        .findFirst().orElseThrow();
    assertEquals(SLOT, checked.slot());
    assertFalse(checked.active(), "no consumer is attached to the idle slot");
  }

  // ---- helpers ----

  private static void awaitUntil(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    fail("timed out waiting for: " + what);
  }

  private static void onConn(java.util.function.Consumer<MemorySegment> work) {
    MemorySegment c = pool.lease().getOrThrow();
    try {
      work.accept(c);
    } finally {
      pool.release(c);
    }
  }
}
