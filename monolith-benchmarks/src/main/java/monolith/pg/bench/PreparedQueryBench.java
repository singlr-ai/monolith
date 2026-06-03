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
 * The FFM prepared ceiling: {@code execPrepared} reusing a server-prepared plan on a dedicated
 * connection the pool never {@code DISCARD ALL}s, so it is the fair match for a pgjdbc
 * {@code PreparedStatement} whose plan survives across Hikari checkouts. Comparing this against
 * {@link JdbcQueryBench} isolates the question the idiomatic {@link BinaryQueryBench} cannot answer: is
 * the gap the FFM path itself, or {@link monolith.pg.runtime.PgPool}'s reset policy that precludes
 * prepared-statement reuse? Each benchmark thread owns its own libpq connection (libpq connections are
 * single-threaded). Requires a reachable Postgres ({@code MONOLITH_TEST_CONNINFO}).
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Threads(8)
public class PreparedQueryBench {

  private static final int ROWS = 1000;
  private static final String STMT = "bench_point_select";
  private static final String SELECT = "SELECT name, val FROM bench_prep WHERE id = $1";

  private static String conninfo() {
    return System.getenv().getOrDefault(
        "MONOLITH_TEST_CONNINFO",
        "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));
  }

  /** Benchmark-scoped: create and seed the shared table once, before any thread prepares against it. */
  @State(Scope.Benchmark)
  public static class Data {
    @Setup(Level.Trial)
    public void seed() {
      MemorySegment conn;
      try (var tmp = Arena.ofConfined()) {
        conn = Pg.connect(tmp, conninfo()).getOrThrow();
      }
      try (var arena = Arena.ofConfined()) {
        Pg.exec(arena, conn, "DROP TABLE IF EXISTS bench_prep").getOrThrow();
        Pg.exec(arena, conn,
            "CREATE TABLE bench_prep (id int PRIMARY KEY, name text NOT NULL, val bigint NOT NULL)")
            .getOrThrow();
        Pg.exec(arena, conn,
            "INSERT INTO bench_prep SELECT i, 'n' || i, i * 2 FROM generate_series(0, " + (ROWS - 1) + ") i")
            .getOrThrow();
      } finally {
        Pg.finish(conn);
      }
    }
  }

  /** Thread-scoped: one dedicated connection per thread, with the statement prepared once and kept. */
  @State(Scope.Thread)
  public static class Conn {
    MemorySegment conn;

    @Setup(Level.Trial)
    public void open(Data data) {
      try (var tmp = Arena.ofConfined()) {
        conn = Pg.connect(tmp, conninfo()).getOrThrow();
      }
      try (var arena = Arena.ofConfined()) {
        Pg.prepare(arena, conn, STMT, SELECT).getOrThrow();
      }
    }

    @TearDown(Level.Trial)
    public void close() {
      if (conn != null) Pg.finish(conn);
    }
  }

  @Benchmark
  public long pointSelectPrepared(Conn c) {
    var id = ThreadLocalRandom.current().nextInt(ROWS);
    try (var arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, id);
      MemorySegment res = Pg.execPrepared(arena, c.conn, STMT, p.values(), p.lengths(), p.formats())
          .getOrThrow();
      try {
        return ByteBuffer.wrap(Pg.getbytes(res, 0, 1)).getLong();
      } finally {
        Pg.clear(res);
      }
    }
  }
}
