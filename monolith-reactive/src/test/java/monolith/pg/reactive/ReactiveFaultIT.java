/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgInvalidationRule;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fault injection for the change feed: drop the replication stream mid-flight by terminating its
 * walsender backend, and prove the feed recovers, an invalidation after the drop still reaches the
 * subscriber. Without reconnection the {@link Invalidator} would spin on a dead socket and silently
 * stop delivering, the missed-invalidation failure that shows stale data. Skips when no Postgres.
 */
@DisplayName("change feed recovers from a dropped replication stream")
class ReactiveFaultIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final String SLOT = "monolith_fault_it";

  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment admin;
  private static boolean available;

  @BeforeAll
  static void setup() {
    try {
      Result<MemorySegment> conn = Pg.connect(ARENA, CONNINFO);
      if (conn.isFailure()) return;
      admin = conn.getOrThrow();
      Pg.exec(ARENA, admin, """
          DROP TABLE IF EXISTS fault_events;
          CREATE TABLE fault_events (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind text NOT NULL);
          ALTER TABLE fault_events REPLICA IDENTITY FULL;""").getOrThrow();
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (admin != null) Pg.finish(admin);
    ARENA.close();
  }

  @BeforeEach
  void requirePostgres() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
  }

  @Test
  @DisplayName("a change after the walsender is killed still wakes the subscriber")
  void recoversAfterTheStreamDrops() throws InterruptedException {
    PgPool pool = new PgPool(CONNINFO, 2);
    ReactiveHub hub = new ReactiveHub(pool, List.of(eventsRule()));
    var fires = new AtomicInteger();
    hub.subscribe("FaultEvents", "all", fires::incrementAndGet);

    Invalidator invalidator = new Invalidator(CONNINFO, hub, SLOT);
    try {
      // Baseline: the feed delivers.
      insert();
      assertTrue(awaitAtLeast(fires, 1, 5000), "the feed should deliver before the fault");
      int before = fires.get();

      // Fault: terminate the walsender holding our slot. The stream drops mid-flight.
      killWalsender();

      // A change made after the drop must still arrive, once the Invalidator reconnects and the slot
      // replays it. Generous timeout: the slot can stay briefly active after the kill, so the reconnect
      // backs off and retries until the server frees it.
      insert();
      assertTrue(awaitAtLeast(fires, before + 1, 20000),
          "the feed should recover and deliver a change made after the stream dropped");
    } finally {
      invalidator.close();
      pool.close();
    }
  }

  private static void insert() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, "INSERT INTO fault_events (kind) VALUES ('created')").getOrThrow();
    }
  }

  private static void killWalsender() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots"
          + " WHERE slot_name = '" + SLOT + "' AND active_pid IS NOT NULL");
    }
  }

  private static boolean awaitAtLeast(AtomicInteger counter, int target, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (counter.get() >= target) return true;
      Thread.sleep(50);
    }
    return counter.get() >= target;
  }

  /** Any change on {@code fault_events} maps to the single param "all". */
  private static PgInvalidationRule eventsRule() {
    return new PgInvalidationRule() {
      @Override public String query() { return "FaultEvents"; }
      @Override public String[] tables() { return new String[] {"fault_events"}; }
      @Override public Set<String> affectedParams(
          String changedTable, Function<String, Set<String>> valuesOf, PgPool pool) {
        return changedTable.equals("fault_events") ? Set.of("all") : Set.of();
      }
    };
  }
}
