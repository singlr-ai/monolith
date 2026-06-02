/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.UUID;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves array and enum parameters travel over the binary wire correctly: each test deletes by a
 * parameter and checks the surviving count, so it never has to read a binary result. Skips when no
 * database is reachable.
 */
@DisplayName("Array and enum parameters against real Postgres")
class ParamSurfaceIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment conn;
  private static boolean available;

  private enum Mood { happy, sad, meh }

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
    exec("DROP TABLE IF EXISTS ps_nums, ps_ids, ps_moods");
    exec("DROP TYPE IF EXISTS ps_mood");
  }

  @Test
  @DisplayName("an int[] binds to ANY($1)")
  void intArrayMatchesWithAny() {
    exec("CREATE TABLE ps_nums (n int)");
    exec("INSERT INTO ps_nums VALUES (1), (2), (3), (4), (5)");

    execParams("DELETE FROM ps_nums WHERE n = ANY($1)", new int[] {2, 4});

    assertEquals(3, count("SELECT count(*) FROM ps_nums"));
  }

  @Test
  @DisplayName("a List<Integer> binds to ANY($1) the same way")
  void listOfIntegersMatchesWithAny() {
    exec("CREATE TABLE ps_nums (n int)");
    exec("INSERT INTO ps_nums VALUES (1), (2), (3), (4), (5)");

    execParams("DELETE FROM ps_nums WHERE n = ANY($1)", List.of(1, 3, 5));

    assertEquals(2, count("SELECT count(*) FROM ps_nums"));
  }

  @Test
  @DisplayName("a List<UUID> binds to ANY($1)")
  void uuidListMatchesWithAny() {
    var keep = UUID.randomUUID();
    var dropA = UUID.randomUUID();
    var dropB = UUID.randomUUID();
    exec("CREATE TABLE ps_ids (id uuid)");
    for (var id : List.of(keep, dropA, dropB)) {
      execParams("INSERT INTO ps_ids VALUES ($1)", id);
    }

    // A List, not a UUID[]: an Object[] would be spread by the Object... varargs into many params.
    execParams("DELETE FROM ps_ids WHERE id = ANY($1)", List.of(dropA, dropB));

    assertEquals(1, count("SELECT count(*) FROM ps_ids"));
  }

  @Test
  @DisplayName("a Java enum binds to a Postgres enum column by its label")
  void enumBindsToAPostgresEnum() {
    exec("CREATE TYPE ps_mood AS ENUM ('happy', 'sad', 'meh')");
    exec("CREATE TABLE ps_moods (m ps_mood)");
    exec("INSERT INTO ps_moods VALUES ('happy'), ('sad'), ('meh')");

    execParams("DELETE FROM ps_moods WHERE m = $1", Mood.sad);

    assertEquals(2, count("SELECT count(*) FROM ps_moods"));
    assertEquals(0, count("SELECT count(*) FROM ps_moods WHERE m = 'sad'"));
  }

  // ---- helpers ----

  private static void execParams(String sql, Object... params) {
    try (Arena a = Arena.ofConfined()) {
      var p = PgParam.bind(a, params);
      var res = Pg.execParamsBinary(a, conn, sql, p.values(), p.lengths(), p.formats()).getOrThrow();
      Pg.clear(res);
    }
  }

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
