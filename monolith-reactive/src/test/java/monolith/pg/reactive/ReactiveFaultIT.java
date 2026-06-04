/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgInvalidationRule;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Wal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

/**
 * Fault injection for the change feed: drop the replication stream mid-flight and prove the feed
 * recovers, distinguishing the two recovery paths through the {@link ReactiveEvent}s the
 * {@link Invalidator} emits. A clean drop (the slot survives) resumes from the slot's confirmed LSN and
 * misses nothing ({@code gap == false}); a lost slot is recreated and every subscriber re-queries
 * ({@code gap == true}). Without recovery the Invalidator would spin on a dead socket and silently stop,
 * the missed-invalidation failure that shows stale data. Skips when no Postgres.
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

  private final List<ReactiveEvent> events = new CopyOnWriteArrayList<>();

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
    if (admin != null) {
      Wal.drop(admin, SLOT); // the fault injection abuses the slot; drop slot + publication on the way out
      Pg.finish(admin);
    }
    ARENA.close();
  }

  @BeforeEach
  void requirePostgres() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    events.clear();
    Observability.use(event -> {
      if (event instanceof ReactiveEvent reactive) events.add(reactive);
    });
  }

  @AfterEach
  void clearObserver() {
    Observability.reset();
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("a clean drop resumes from the slot without a gap")
  void recoversFromACleanDrop() throws InterruptedException {
    PgPool pool = new PgPool(CONNINFO, 2);
    ReactiveHub hub = new ReactiveHub(pool, List.of(eventsRule()));
    var fires = new AtomicInteger();
    hub.subscribe("FaultEvents", "all", fires::incrementAndGet);

    Invalidator invalidator = new Invalidator(CONNINFO, hub, SLOT);
    try {
      insert();
      assertTrue(awaitAtLeast(fires, 1, 5000), "the feed should deliver before the fault");
      int before = fires.get();

      killWalsender(admin); // the slot survives, so the reconnect resumes by replay

      insert();
      assertTrue(awaitAtLeast(fires, before + 1, 20000),
          "the feed should recover and deliver a change made after the stream dropped");
      assertTrue(awaitEvent(reconnectedWithGap(false), 5000),
          "a clean drop should reconnect without a gap");
    } finally {
      invalidator.close();
      pool.close();
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS) // 30s gap-await + recovery, plus margin
  @DisplayName("a lost slot is recreated and every subscriber re-queries")
  void recoversFromALostSlot() throws InterruptedException {
    PgPool pool = new PgPool(CONNINFO, 2);
    ReactiveHub hub = new ReactiveHub(pool, List.of(eventsRule()));
    var fires = new AtomicInteger();
    hub.subscribe("FaultEvents", "all", fires::incrementAndGet);

    Invalidator invalidator = new Invalidator(CONNINFO, hub, SLOT);
    MemorySegment faultConn = Pg.connect(ARENA, CONNINFO).getOrThrow(); // the fault thread's own connection
    var faulting = new AtomicBoolean(true);
    // Relentlessly kill the walsender and drop the slot, so the Invalidator can never resume by replay
    // and is forced down the lost-slot path (recreate + re-query) at least once. Deterministic: the slot
    // is dropped every cycle, so some reconnect must find it missing.
    Thread faultThread = Thread.ofPlatform().start(() -> {
      while (faulting.get()) {
        killWalsender(faultConn);
        dropSlotIfInactive(faultConn);
        sleepQuietly(20);
      }
    });
    try {
      assertTrue(awaitEvent(reconnectedWithGap(true), 30000),
          "a lost slot should be detected and recovered with a full re-query");
      faulting.set(false);
      faultThread.join();

      // Once the faults stop, the feed must work again: a fresh change reaches the subscriber.
      int before = fires.get();
      insert();
      assertTrue(awaitAtLeast(fires, before + 1, 20000), "the feed should work again after a lost slot");
    } finally {
      faulting.set(false);
      faultThread.join();
      Pg.finish(faultConn);
      invalidator.close();
      pool.close();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("closing during a reconnect storm drops the slot and leaves no leak")
  void closeDuringAReconnectStormLeavesNoLeak() throws InterruptedException {
    PgPool pool = new PgPool(CONNINFO, 2);
    ReactiveHub hub = new ReactiveHub(pool, List.of(eventsRule()));
    hub.subscribe("FaultEvents", "all", () -> { });

    Invalidator invalidator = new Invalidator(CONNINFO, hub, SLOT);
    MemorySegment faultConn = Pg.connect(ARENA, CONNINFO).getOrThrow();
    var churning = new AtomicBoolean(true);
    // Keep the stream dropping so the loop is reconnecting when close() lands: the race that used to let
    // a late reconnect resurrect the slot after close() had dropped it, leaking a WAL-retaining slot.
    Thread churn = Thread.ofPlatform().start(() -> {
      while (churning.get()) {
        killWalsender(faultConn);
        sleepQuietly(25);
      }
    });
    try {
      assertTrue(awaitEvent(reconnectedWithGap(false), 20000), "the feed should be actively reconnecting");
      invalidator.close(); // close mid-reconnect: the loop thread must drop its own slot, no race, no leak
    } finally {
      churning.set(false);
      churn.join();
      Pg.finish(faultConn);
      pool.close();
    }

    assertFalse(slotExists(),
        "close() must drop the slot even under a reconnect storm: a leaked slot retains WAL without bound");
    assertFalse(publicationExists(), "close() must drop the publication too");
  }

  private Predicate<ReactiveEvent> reconnectedWithGap(boolean gap) {
    return e -> e instanceof ReactiveEvent.StreamReconnected r && r.gap() == gap;
  }

  private static void insert() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, "INSERT INTO fault_events (kind) VALUES ('created')").getOrThrow();
    }
  }

  private static void killWalsender(MemorySegment conn) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots"
          + " WHERE slot_name = '" + SLOT + "' AND active_pid IS NOT NULL");
    } catch (RuntimeException ignore) {
      // the slot may not exist this instant (just dropped); the next cycle retries
    }
  }

  private static void dropSlotIfInactive(MemorySegment conn) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots"
          + " WHERE slot_name = '" + SLOT + "' AND active_pid IS NULL");
    } catch (RuntimeException ignore) {
      // the slot may be active or already gone; the next cycle retries
    }
  }

  private static boolean slotExists() {
    return count("SELECT count(*) FROM pg_replication_slots WHERE slot_name = '" + SLOT + "'") > 0;
  }

  private static boolean publicationExists() {
    return count("SELECT count(*) FROM pg_publication WHERE pubname = '" + SLOT + "_pub'") > 0;
  }

  private static int count(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Integer.parseInt(Pg.textColumn(a, admin, sql).getOrThrow().get(0));
    }
  }

  private boolean awaitEvent(Predicate<ReactiveEvent> predicate, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (events.stream().anyMatch(predicate)) return true;
      Thread.sleep(50);
    }
    return events.stream().anyMatch(predicate);
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

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
