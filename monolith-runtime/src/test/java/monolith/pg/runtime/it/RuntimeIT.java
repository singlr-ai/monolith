/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Wal;
import monolith.pg.runtime.WalChange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the FFM/Postgres half of the runtime against a real database: the libpq path, the
 * binary tuple bridge through generated readers, the connection pool's lease/timeout/self-heal, the
 * WAL poll feed, and the generated invalidation rules. Skips cleanly when no Postgres is reachable.
 */
@DisplayName("runtime against real Postgres")
class RuntimeIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final String SCHEMA = """
      DROP TABLE IF EXISTS widgets;
      DROP TABLE IF EXISTS boxes;
      CREATE TABLE boxes (id uuid PRIMARY KEY, region text NOT NULL);
      CREATE TABLE widgets (id uuid PRIMARY KEY, box_id uuid NOT NULL REFERENCES boxes(id),
          name text NOT NULL, qty int NOT NULL, note text);
      ALTER TABLE widgets REPLICA IDENTITY FULL;
      ALTER TABLE boxes REPLICA IDENTITY FULL;""";

  private static final String INSERT_BOX = "INSERT INTO boxes (id, region) VALUES ($1, $2)";
  private static final String INSERT_WIDGET =
      "INSERT INTO widgets (id, box_id, name, qty, note) VALUES ($1, $2, $3, $4, $5)";

  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment admin;
  private static boolean available;

  @BeforeAll
  static void connectAndCreateSchema() {
    try {
      Result<MemorySegment> conn = Pg.connect(ARENA, CONNINFO);
      if (conn.isFailure()) {
        return;
      }
      admin = conn.getOrThrow();
      Pg.exec(ARENA, admin, SCHEMA).getOrThrow();
      available = true;
    } catch (Throwable t) {
      available = false; // no libpq or no server: the tests assume-skip
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
    exec("DELETE FROM widgets; DELETE FROM boxes");
  }

  @Test
  @DisplayName("reads binary rows through a generated reader, including NULL columns")
  void readsBinaryRowsIncludingNulls() {
    UUID box = insertBox("EU");
    insertWidget(box, "aaa", 3, "hot");
    insertWidget(box, "bbb", 7, null);

    try (Arena a = Arena.ofConfined()) {
      List<WidgetsByBoxReader> rows = WidgetsByBoxQuery.run(a, admin, box);
      assertEquals(2, rows.size());
      assertEquals("aaa", rows.get(0).name());
      assertEquals(3, rows.get(0).qty());
      assertEquals("hot", rows.get(0).note());
      assertEquals("bbb", rows.get(1).name());
      assertEquals(7, rows.get(1).qty());
      assertNull(rows.get(1).note(), "a NULL column reads back as null");
    }
  }

  @Test
  @DisplayName("exec and execParamsBinary surface SQL errors as a Result.Failure")
  void sqlErrorsBecomeFailures() {
    try (Arena a = Arena.ofConfined()) {
      assertTrue(Pg.exec(a, admin, "SELECT 1").isSuccess());
      assertTrue(Pg.exec(a, admin, "SELECT this is not valid ###").isFailure());

      UUID orphanBox = UUID.randomUUID(); // never inserted -> FK violation
      var bound = PgParam.bind(a, UUID.randomUUID(), orphanBox, "x", 1, null);
      Result<MemorySegment> r = Pg.execParamsBinary(a, admin, INSERT_WIDGET,
          bound.values(), bound.lengths(), bound.formats());
      assertTrue(r.isFailure());
    }
  }

  @Test
  @DisplayName("textColumn returns column zero of every row")
  void textColumnReadsValues() {
    insertBox("EU");
    insertBox("US");
    try (Arena a = Arena.ofConfined()) {
      Result<List<String>> r = Pg.textColumn(a, admin, "SELECT region FROM boxes ORDER BY region");
      assertTrue(r.isSuccess());
      assertEquals(List.of("EU", "US"), r.getOrThrow());
    }
  }

  @Test
  @DisplayName("the pool leases and returns connections")
  void poolLeasesAndReleases() {
    try (PgPool pool = new PgPool(CONNINFO, 2)) {
      assertEquals(2, pool.size());
      MemorySegment c = pool.lease().getOrThrow();
      pool.release(c);
      assertEquals(0, pool.replacedCount());
    }
  }

  @Test
  @DisplayName("a lease times out when the pool is exhausted")
  void poolTimesOutWhenExhausted() {
    try (PgPool pool = new PgPool(CONNINFO, 1, Duration.ofMillis(100))) {
      MemorySegment held = pool.lease().getOrThrow();
      Result<MemorySegment> second = pool.lease();
      assertTrue(second.isFailure());
      assertTrue(((Result.Failure<MemorySegment>) second).error().contains("exhausted"));
      pool.release(held);
    }
  }

  @Test
  @DisplayName("an interrupted lease returns a failure")
  void poolInterruptedLeaseFails() throws InterruptedException {
    try (PgPool pool = new PgPool(CONNINFO, 1, Duration.ofSeconds(30))) {
      MemorySegment held = pool.lease().getOrThrow();
      var result = new AtomicReference<Result<MemorySegment>>();
      Thread waiter = new Thread(() -> result.set(pool.lease()));
      waiter.start();
      Thread.sleep(100); // let it block on the empty queue
      waiter.interrupt();
      waiter.join(2000);
      assertTrue(result.get().isFailure());
      pool.release(held);
    }
  }

  @Test
  @DisplayName("the pool replaces a connection whose backend died")
  void poolReplacesADeadBackend() {
    try (PgPool pool = new PgPool(CONNINFO, 1)) {
      MemorySegment c = pool.lease().getOrThrow();
      int pid;
      try (Arena a = Arena.ofConfined()) {
        pid = Integer.parseInt(Pg.textColumn(a, c, "SELECT pg_backend_pid()").getOrThrow().get(0));
        Pg.exec(a, admin, "SELECT pg_terminate_backend(" + pid + ")").getOrThrow();
      }
      pool.release(c); // reset fails on the dead backend -> replace
      assertEquals(1, pool.replacedCount());
      pool.release(pool.lease().getOrThrow()); // pool is healthy again
    }
  }

  @Test
  @DisplayName("the WAL poll feed decodes row changes")
  void walPollDeliversChanges() {
    String slot = "monolith_runtime_it";
    Wal.recreate(admin, slot);
    try {
      UUID box = insertBox("EU");
      insertWidget(box, "w", 1, null);
      List<WalChange> changes = Wal.drain(admin, slot);
      assertTrue(changes.stream().anyMatch(c -> c.table().equals("widgets")));
    } finally {
      Wal.drop(admin, slot);
    }
  }

  @Test
  @DisplayName("generated invalidation rules map changes back to params, joins included")
  void invalidationRulesResolveParams() {
    UUID box = insertBox("EU");
    UUID widget = insertWidget(box, "w", 1, null);

    try (PgPool pool = new PgPool(CONNINFO, 2)) {
      var byRegion = new WidgetsByRegionInvalidation();
      var byBox = new WidgetsByBoxInvalidation();

      var boxChange = new WalChange("boxes", "table public.boxes: UPDATE: region[text]:'EU'");
      assertEquals(Set.of("EU"), byRegion.affectedParams("boxes", boxChange::valuesOf, pool));

      var widgetChange = new WalChange("widgets",
          "table public.widgets: INSERT: id[uuid]:'" + widget + "' box_id[uuid]:'" + box
              + "' name[text]:'w' qty[integer]:1");
      // joined param: box_id walks up to the box's region
      assertEquals(Set.of("EU"), byRegion.affectedParams("widgets", widgetChange::valuesOf, pool));
      // direct param: box_id read straight off the change
      assertEquals(Set.of(box.toString()), byBox.affectedParams("widgets", widgetChange::valuesOf, pool));

      var noBoxId = new WalChange("widgets", "table public.widgets: INSERT: name[text]:'w'");
      assertTrue(byRegion.affectedParams("widgets", noBoxId::valuesOf, pool).isEmpty());

      assertTrue(byRegion.affectedParams("unrelated", widgetChange::valuesOf, pool).isEmpty());
      assertTrue(byBox.affectedParams("unrelated", widgetChange::valuesOf, pool).isEmpty());
    }
  }

  // ---- helpers ----

  private static UUID insertBox(String region) {
    UUID id = UUID.randomUUID();
    try (Arena a = Arena.ofConfined()) {
      var p = PgParam.bind(a, id, region);
      Pg.clear(Pg.execParamsBinary(a, admin, INSERT_BOX, p.values(), p.lengths(), p.formats()).getOrThrow());
    }
    return id;
  }

  private static UUID insertWidget(UUID box, String name, int qty, String note) {
    UUID id = UUID.randomUUID();
    try (Arena a = Arena.ofConfined()) {
      var p = PgParam.bind(a, id, box, name, qty, note);
      Pg.clear(Pg.execParamsBinary(a, admin, INSERT_WIDGET, p.values(), p.lengths(), p.formats()).getOrThrow());
    }
    return id;
  }

  private static void exec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, sql).getOrThrow();
    }
  }
}
