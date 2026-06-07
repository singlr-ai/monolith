/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stress/soak harness for the FFM libpq path: many virtual threads hammer the binary parameter and
 * binary result path through the pool, and every read is verified, so a use-after-free, a cross-Arena
 * read, or a concurrency bug surfaces under load (as wrong data or a JVM crash) rather than in
 * production. The default run is a short smoke; set {@code MONOLITH_STRESS_SECONDS} for a real soak.
 * Skips when no database is reachable.
 */
@DisplayName("FFM binary path under concurrency")
class FfmStressIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final int CONCURRENCY = 32;
  private static final int SEED = 1000;
  private static final Duration DURATION = Duration.ofSeconds(
      Long.parseLong(System.getenv().getOrDefault("MONOLITH_STRESS_SECONDS", "2")));

  private static PgPool pool;
  private static boolean available;

  @BeforeAll
  static void connect() {
    try {
      // One connection per worker. This soak verifies the FFM binary path under concurrency (use-after-
      // free, cross-Arena reads, wrong data) — not the pool's lease-wait. A pool smaller than CONCURRENCY
      // turns it into a lease-contention test: the synchronous execParamsBinary path pins its carrier for
      // the whole round trip (and release adds a RESET_SESSION round trip), so on a constrained CI runner
      // (few cores, small soak heap) a GC/scheduling stall can hold every connection past the 10s lease
      // timeout and fail the soak spuriously. Pool sizing under carrier pinning is the roadmap's async-
      // dispatch concern and is stress-proven for the pool itself elsewhere; here, give each worker its
      // own connection so pinning only serializes execution rather than starving leases.
      pool = new PgPool(CONNINFO, CONCURRENCY);
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
  void seed() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    exec("DROP TABLE IF EXISTS ffm_stress");
    exec("CREATE TABLE ffm_stress (id int PRIMARY KEY, name text NOT NULL, val bigint NOT NULL)");
    exec("INSERT INTO ffm_stress SELECT i, 'n' || i, i * 2 FROM generate_series(0, " + (SEED - 1) + ") i");
  }

  @Test
  @DisplayName("verified binary reads stay correct under sustained concurrency")
  void hammersTheBinaryPathConcurrently() throws InterruptedException {
    var running = new AtomicBoolean(true);
    var ops = new AtomicLong();
    var errors = new CopyOnWriteArrayList<Throwable>();
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      workers.add(Thread.ofVirtual().start(() -> {
        var random = ThreadLocalRandom.current();
        while (running.get() && errors.isEmpty()) {
          try {
            queryAndVerify(random.nextInt(SEED));
            ops.incrementAndGet();
          } catch (Throwable t) {
            errors.add(t);
          }
        }
      }));
    }

    Thread.sleep(DURATION.toMillis());
    running.set(false);
    for (Thread w : workers) {
      w.join();
    }

    if (!errors.isEmpty()) {
      throw new AssertionError("the binary path failed under concurrency: " + errors.get(0), errors.get(0));
    }
    assertTrue(ops.get() > 1000, "expected sustained throughput, only ran " + ops.get() + " ops");
  }

  /** Bind a binary int param, run a binary-result query, and verify the decoded row against its id. */
  private static void queryAndVerify(int id) {
    MemorySegment conn = pool.lease().getOrThrow();
    try (Arena arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, id);
      MemorySegment res = Pg.execParamsBinary(arena, conn,
          "SELECT name, val FROM ffm_stress WHERE id = $1", p.values(), p.lengths(), p.formats())
          .getOrThrow();
      try {
        assertEquals(1, Pg.ntuples(res));
        String name = new String(Pg.getbytes(res, 0, 0), StandardCharsets.UTF_8);
        long val = ByteBuffer.wrap(Pg.getbytes(res, 0, 1)).getLong();
        assertEquals("n" + id, name, "a binary text read returned the wrong row's bytes");
        assertEquals(id * 2L, val, "a binary bigint read returned the wrong row's bytes");
      } finally {
        Pg.clear(res);
      }
    } finally {
      pool.release(conn);
    }
  }

  private static void exec(String sql) {
    MemorySegment conn = pool.lease().getOrThrow();
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    } finally {
      pool.release(conn);
    }
  }
}
