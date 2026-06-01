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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * End-to-end reactive proof against real Postgres: an {@link Invalidator} tails the WAL over a
 * streaming replication slot, and an ordinary INSERT wakes a {@link ReactiveHub} subscriber. This
 * also exercises the streaming {@code WalStream} consumer it drives. Skips when no Postgres.
 */
@DisplayName("Invalidator against real Postgres")
class InvalidatorIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

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
          DROP TABLE IF EXISTS events;
          CREATE TABLE events (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), kind text NOT NULL);
          ALTER TABLE events REPLICA IDENTITY FULL;""").getOrThrow();
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
  @DisplayName("a write wakes a subscriber through the streamed WAL")
  void aWriteWakesASubscriber() throws InterruptedException {
    PgPool pool = new PgPool(CONNINFO, 2);
    ReactiveHub hub = new ReactiveHub(pool, List.of(eventsRule()));
    CountDownLatch woken = new CountDownLatch(1);
    hub.subscribe("Events", "all", woken::countDown);

    Invalidator invalidator = new Invalidator(CONNINFO, hub, "monolith_invalidator_it");
    try {
      try (Arena a = Arena.ofConfined()) {
        Pg.exec(a, admin, "INSERT INTO events (kind) VALUES ('created')").getOrThrow();
      }
      assertTrue(woken.await(5, TimeUnit.SECONDS), "the streamed change should wake the subscriber");
    } finally {
      invalidator.close();
      pool.close();
    }
  }

  /** Any change on {@code events} maps to the single param "all", ignoring the (unused) pool. */
  private static PgInvalidationRule eventsRule() {
    return new PgInvalidationRule() {
      @Override public String query() { return "Events"; }
      @Override public String[] tables() { return new String[] {"events"}; }
      @Override public Set<String> affectedParams(
          String changedTable, Function<String, Set<String>> valuesOf, PgPool pool) {
        return changedTable.equals("events") ? Set.of("all") : Set.of();
      }
    };
  }
}
