/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Isolates the value of prepared-plan reuse: the same join-and-aggregate query run two ways on a
 * dedicated connection (no pool reset to confound the comparison), once re-parsing and re-planning every
 * call ({@code execParamsBinary}) and once reusing a prepared plan ({@code execPrepared}). A point
 * select's parse cost is lost in the network round trip, but a query the planner has to think about is
 * where reuse shows, both in latency and in the server CPU it does not spend re-planning. Requires a
 * reachable Postgres ({@code MONOLITH_TEST_CONNINFO}); each thread owns its connection.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Threads(8)
@State(Scope.Thread)
public class ComplexQueryBench {

  private static final int REGIONS = 20;
  private static final String STMT = "bench_complex";
  private static final String SELECT = """
      SELECT b.id, count(w.id) AS widgets, coalesce(sum(w.val), 0) AS total
        FROM bench_boxes b
        LEFT JOIN bench_widgets w ON w.box_id = b.id
       WHERE b.region = $1
       GROUP BY b.id
       ORDER BY total DESC
       LIMIT 10""";

  private MemorySegment conn;

  @Setup(Level.Trial)
  public void setup() {
    var conninfo = System.getenv().getOrDefault(
        "MONOLITH_TEST_CONNINFO",
        "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));
    try (var tmp = Arena.ofConfined()) {
      conn = Pg.connect(tmp, conninfo).getOrThrow();
    }
    seedOnce(conninfo);
    try (var a = Arena.ofConfined()) {
      Pg.prepare(a, conn, STMT, SELECT).getOrThrow();
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (conn != null) Pg.finish(conn);
  }

  @Benchmark
  public long reParsedEachCall() {
    var region = ThreadLocalRandom.current().nextInt(REGIONS);
    try (var arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, region);
      MemorySegment res = Pg.execParamsBinary(arena, conn, SELECT, p.values(), p.lengths(), p.formats())
          .getOrThrow();
      try {
        return Pg.ntuples(res);
      } finally {
        Pg.clear(res);
      }
    }
  }

  @Benchmark
  public long preparedPlanReused() {
    var region = ThreadLocalRandom.current().nextInt(REGIONS);
    try (var arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, region);
      MemorySegment res = Pg.execPrepared(arena, conn, STMT, p.values(), p.lengths(), p.formats())
          .getOrThrow();
      try {
        return Pg.ntuples(res);
      } finally {
        Pg.clear(res);
      }
    }
  }

  /** Seed the shared tables idempotently (every thread calls this; ON CONFLICT makes it a no-op after the first). */
  private void seedOnce(String conninfo) {
    try (var a = Arena.ofConfined()) {
      Pg.exec(a, conn, """
          CREATE TABLE IF NOT EXISTS bench_boxes (id int PRIMARY KEY, region int NOT NULL);
          CREATE TABLE IF NOT EXISTS bench_widgets (id int PRIMARY KEY, box_id int NOT NULL, val bigint NOT NULL);
          INSERT INTO bench_boxes SELECT i, i % """ + REGIONS + """
             FROM generate_series(0, 199) i ON CONFLICT DO NOTHING;
          INSERT INTO bench_widgets SELECT i, i % 200, i * 2
             FROM generate_series(0, 4999) i ON CONFLICT DO NOTHING;""").getOrThrow();
    }
  }
}
