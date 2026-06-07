/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
 * Fault injection for the FFM binary path: hammer it with verified reads from many virtual threads
 * while a chaos thread repeatedly terminates every backend ({@code pg_terminate_backend}), the most
 * common production failure (a backend dying, a failover, a network reset). The contract under chaos is
 * stronger than "it does not crash": a read that succeeds must return the correct row (a killed
 * connection must never yield another row's bytes or stale memory), the pool must self-heal, and once
 * the storm stops the path must fully recover. Skips when no database is reachable.
 */
@DisplayName("FFM binary path under backend kills")
class FfmFaultIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final int WORKERS = 16;
  private static final int SEED = 1000;
  private static final Duration DURATION = Duration.ofSeconds(
      Long.parseLong(System.getenv().getOrDefault("MONOLITH_FAULT_SECONDS", "3")));

  private static PgPool pool;
  private static boolean available;

  @BeforeAll
  static void connect() {
    try {
      pool = new PgPool(CONNINFO, 8);
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
    exec("DROP TABLE IF EXISTS ffm_fault");
    exec("CREATE TABLE ffm_fault (id int PRIMARY KEY, name text NOT NULL, val bigint NOT NULL)");
    exec("INSERT INTO ffm_fault SELECT i, 'n' || i, i * 2 FROM generate_series(0, " + (SEED - 1) + ") i");
  }

  @Test
  @DisplayName("verified reads stay correct and the pool self-heals under repeated backend kills")
  void survivesRepeatedBackendKills() throws InterruptedException {
    var running = new AtomicBoolean(true);
    var ok = new AtomicLong();
    var tolerated = new AtomicLong(); // failed ops during a kill: acceptable
    var corruptions = new CopyOnWriteArrayList<String>(); // a wrong row: never acceptable

    List<Thread> workers = new ArrayList<>();
    for (var i = 0; i < WORKERS; i++) {
      workers.add(Thread.ofVirtual().start(() -> {
        var random = ThreadLocalRandom.current();
        while (running.get()) {
          var id = random.nextInt(SEED);
          var wrong = readAndCheck(id);
          if (wrong == null) {
            ok.incrementAndGet();
          } else if (wrong.isEmpty()) {
            tolerated.incrementAndGet(); // a killed connection failed the op, as expected
          } else {
            corruptions.add(wrong); // a successful read returned the wrong bytes
          }
        }
      }));
    }

    // Chaos: a dedicated admin connection (never its own victim, it excludes its own pid) terminates
    // every other backend, including all of the pool's, on a short interval.
    MemorySegment admin;
    try (var tmp = Arena.ofConfined()) {
      admin = Pg.connect(tmp, CONNINFO).getOrThrow();
    }
    var chaos = Thread.ofPlatform().start(() -> {
      while (running.get()) {
        killAllOtherBackends(admin);
        sleepQuietly(400);
      }
    });

    Thread.sleep(DURATION.toMillis());
    running.set(false);
    for (var w : workers) w.join();
    chaos.join();
    Pg.finish(admin);

    assertTrue(corruptions.isEmpty(),
        "a read returned the wrong row under chaos (memory corruption): " + corruptions.stream().findFirst().orElse(""));
    assertTrue(ok.get() > 100,
        "expected real progress between kills, only " + ok.get() + " ok ops (tolerated " + tolerated.get() + ")");
    assertTrue(pool.replacedCount() > 0, "the pool never replaced a dead connection, so self-heal was not exercised");

    // Recovery: the pool heals a dead connection when it is released, not when it is leased, so a
    // connection killed in the storm's final instant fails its next read once before it is healed. Require
    // the path to return to a fully clean batch within a few seconds (eventual full recovery), while still
    // treating any wrong row as corruption that is never tolerated, even mid-recovery.
    long deadline = System.nanoTime() + 10_000_000_000L; // 10 seconds
    boolean recovered = false;
    while (!recovered && System.nanoTime() < deadline) {
      recovered = true;
      for (var id = 0; id < 100; id++) {
        var wrong = readAndCheck(id);
        if (wrong == null) {
          continue; // correct
        }
        if (wrong.isEmpty()) {
          recovered = false; // a straggler is still healing: retry the whole batch
          break;
        }
        fail("a read returned the wrong row after the storm (memory corruption): " + wrong);
      }
    }
    assertTrue(recovered, "the path did not return to a fully clean batch after the kill storm");
  }

  /**
   * Read a row and check it. Returns {@code null} if correct, an empty string if the op failed (a
   * tolerated outcome during a kill), or a description if a successful read returned the wrong bytes.
   */
  private static String readAndCheck(int id) {
    // A lease can fail when the kill storm has downed every pooled backend faster than the pool can
    // self-heal, so the wait hits the lease timeout. That is the same class of tolerated failure as a
    // backend killed mid-call — count it, don't let it escape and kill the worker thread (an uncaught
    // "pool exhausted" would also surface as a spurious CI error annotation on an otherwise-passing soak).
    Result<MemorySegment> leased = pool.lease();
    if (leased instanceof Result.Failure<MemorySegment>) return "";
    MemorySegment conn = leased.getOrThrow();
    try (var arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, id);
      Result<MemorySegment> r = Pg.execParamsBinary(arena, conn,
          "SELECT name, val FROM ffm_fault WHERE id = $1", p.values(), p.lengths(), p.formats());
      if (r instanceof Result.Failure<MemorySegment>) return "";
      MemorySegment res = r.getOrThrow();
      try {
        if (Pg.ntuples(res) != 1) return "";
        var name = new String(Pg.getbytes(res, 0, 0), StandardCharsets.UTF_8);
        var val = ByteBuffer.wrap(Pg.getbytes(res, 0, 1)).getLong();
        if (!("n" + id).equals(name) || val != id * 2L) {
          return "id=" + id + " got name=" + name + " val=" + val;
        }
        return null;
      } finally {
        Pg.clear(res);
      }
    } catch (RuntimeException killedMidCall) {
      return ""; // the backend died during the FFM call: tolerated
    } finally {
      pool.release(conn); // a dead connection is detected and replaced here
    }
  }

  private static void killAllOtherBackends(MemorySegment admin) {
    try (var a = Arena.ofConfined()) {
      Pg.exec(a, admin, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
          + " WHERE datname = current_database() AND pid <> pg_backend_pid()");
    } catch (RuntimeException ignore) {
      // tolerate a transient failure of the kill query itself; the next round retries
    }
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void exec(String sql) {
    MemorySegment conn = pool.lease().getOrThrow();
    try (var arena = Arena.ofConfined()) {
      Pg.exec(arena, conn, sql).getOrThrow();
    } finally {
      pool.release(conn);
    }
  }
}
