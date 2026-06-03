/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.PreparedCache;
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
 * The generated {@code @PgQuery} path as it now runs: a point select through {@link PreparedCache} on a
 * pooled connection. The plan is prepared once per connection and reused across checkouts (the pool's
 * reset keeps it), so this is the idiomatic FFM path with prepared-plan reuse, the number that should
 * close the gap to {@link JdbcQueryBench} that {@link BinaryQueryBench} (re-parse every call) left open.
 * Requires a reachable Postgres ({@code MONOLITH_TEST_CONNINFO}).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Threads(8)
public class CachedQueryBench {

  private static final int ROWS = 1000;
  private static final String SELECT = "SELECT name, val FROM bench_cached WHERE id = $1";

  private PgPool pool;

  @Setup(Level.Trial)
  public void setup() {
    var conninfo = System.getenv().getOrDefault(
        "MONOLITH_TEST_CONNINFO",
        "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));
    pool = new PgPool(conninfo, 16);
    exec("DROP TABLE IF EXISTS bench_cached");
    exec("CREATE TABLE bench_cached (id int PRIMARY KEY, name text NOT NULL, val bigint NOT NULL)");
    exec("INSERT INTO bench_cached SELECT i, 'n' || i, i * 2 FROM generate_series(0, " + (ROWS - 1) + ") i");
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (pool != null) pool.close();
  }

  @Benchmark
  public long pointSelectCached() {
    var id = ThreadLocalRandom.current().nextInt(ROWS);
    MemorySegment conn = pool.lease().getOrThrow();
    try (var arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, id);
      MemorySegment res = PreparedCache.execute(arena, conn, SELECT, p).getOrThrow();
      try {
        return ByteBuffer.wrap(Pg.getbytes(res, 0, 1)).getLong();
      } finally {
        Pg.clear(res);
      }
    } finally {
      pool.release(conn);
    }
  }

  private void exec(String sql) {
    MemorySegment conn = pool.lease().getOrThrow();
    try (var arena = Arena.ofConfined()) {
      Pg.exec(arena, conn, sql).getOrThrow();
    } finally {
      pool.release(conn);
    }
  }
}
