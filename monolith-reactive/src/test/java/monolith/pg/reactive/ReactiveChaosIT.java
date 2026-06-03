/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

/**
 * A property-style chaos test for the change feed: rather than one scripted fault, drive a randomized
 * (fixed-seed, so reproducible) storm of writes interleaved with walsender kills and slot drops, then
 * assert the invariant that matters under any fault sequence: the feed never permanently dies. After the
 * storm the feed must deliver a fresh change, and the storm must have actually forced reconnects (so the
 * proof is not vacuous). Longer runs via {@code MONOLITH_CHAOS_SECONDS}. Skips when no Postgres.
 */
@DisplayName("change feed survives a random storm of faults")
class ReactiveChaosIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final String SLOT = "monolith_chaos_it";
  private static final Duration DURATION = Duration.ofSeconds(
      Long.parseLong(System.getenv().getOrDefault("MONOLITH_CHAOS_SECONDS", "5")));

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
          DROP TABLE IF EXISTS chaos_events;
          CREATE TABLE chaos_events (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind text NOT NULL);
          ALTER TABLE chaos_events REPLICA IDENTITY FULL;""").getOrThrow();
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (admin != null) {
      Wal.drop(admin, SLOT); // the chaos abuses the slot; drop slot + publication so we leave no trace
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
  @DisplayName("after any storm of writes, kills, and slot drops, a later change still arrives")
  void theFeedAlwaysRecovers() throws InterruptedException {
    PgPool pool = new PgPool(CONNINFO, 4);
    ReactiveHub hub = new ReactiveHub(pool, List.of(eventsRule()));
    var fires = new AtomicInteger();
    hub.subscribe("ChaosEvents", "all", fires::incrementAndGet);

    Invalidator invalidator = new Invalidator(CONNINFO, hub, SLOT);
    var running = new AtomicBoolean(true);
    Thread writer = Thread.ofPlatform().start(() -> {
      while (running.get()) {
        insert();
        sleepQuietly(ThreadLocalRandom.current().nextInt(10, 60));
      }
    });
    // Fixed seed: a reproducible but irregular fault schedule, not a scripted one.
    Thread faults = Thread.ofPlatform().start(() -> {
      var random = new Random(20260603L);
      while (running.get()) {
        sleepQuietly(random.nextInt(30, 150));
        killWalsender();
        if (random.nextInt(3) == 0) dropSlotIfInactive(); // sometimes force the lost-slot path
      }
    });

    try {
      Thread.sleep(DURATION.toMillis());
      running.set(false);
      writer.join();
      faults.join();

      // The invariant: once the storm stops, the feed works. A fresh change must reach the subscriber.
      int before = fires.get();
      insert();
      assertTrue(awaitAtLeast(fires, before + 1, 20000),
          "the feed must recover after the storm and deliver a later change");
      var reconnects = events.stream().filter(e -> e instanceof ReactiveEvent.StreamReconnected).count();
      assertTrue(reconnects > 0, "the storm should have forced at least one reconnect, else it proved nothing");
    } finally {
      running.set(false);
      writer.join();
      faults.join();
      invalidator.close();
      pool.close();
    }
  }

  private static void insert() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, "INSERT INTO chaos_events (kind) VALUES ('created')").getOrThrow();
    } catch (RuntimeException ignore) {
      // a write can fail if its backend was a kill victim; the writer loops and tries again
    }
  }

  private static void killWalsender() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots"
          + " WHERE slot_name = '" + SLOT + "' AND active_pid IS NOT NULL");
    } catch (RuntimeException ignore) {
      // the slot may be momentarily gone; the next cycle retries
    }
  }

  private static void dropSlotIfInactive() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots"
          + " WHERE slot_name = '" + SLOT + "' AND active_pid IS NULL");
    } catch (RuntimeException ignore) {
      // active or already gone; the next cycle retries
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

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Any change on {@code chaos_events} maps to the single param "all". */
  private static PgInvalidationRule eventsRule() {
    return new PgInvalidationRule() {
      @Override public String query() { return "ChaosEvents"; }
      @Override public String[] tables() { return new String[] {"chaos_events"}; }
      @Override public Set<String> affectedParams(
          String changedTable, Function<String, Set<String>> valuesOf, PgPool pool) {
        return changedTable.equals("chaos_events") ? Set.of("all") : Set.of();
      }
    };
  }
}
