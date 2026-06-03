/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
 * The JDBC baseline for {@link BinaryQueryBench}: the identical point select, the identical pool size
 * and concurrency, run through pgjdbc and HikariCP instead of the FFM/libpq path. This is what turns
 * the transport decision's performance claim into a measured comparison rather than an assertion. Both
 * benchmarks use their stack's idiomatic point-select path (libpq {@code PQexecParams} with a binary
 * result here vs a pgjdbc {@code PreparedStatement}), so the numbers compare the two as they are
 * actually used. Requires a reachable Postgres ({@code MONOLITH_TEST_CONNINFO}).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Threads(8)
public class JdbcQueryBench {

  private static final int ROWS = 1000;

  private HikariDataSource ds;

  @Setup(Level.Trial)
  public void setup() {
    var conninfo = System.getenv().getOrDefault(
        "MONOLITH_TEST_CONNINFO",
        "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));
    var cfg = new HikariConfig();
    cfg.setJdbcUrl(jdbcUrl(conninfo));
    var user = field(conninfo, "user");
    if (user != null) cfg.setUsername(user);
    var password = field(conninfo, "password");
    if (password != null) cfg.setPassword(password);
    cfg.setMaximumPoolSize(16);
    ds = new HikariDataSource(cfg);

    exec("DROP TABLE IF EXISTS bench_rows_jdbc");
    exec("CREATE TABLE bench_rows_jdbc (id int PRIMARY KEY, name text NOT NULL, val bigint NOT NULL)");
    exec("INSERT INTO bench_rows_jdbc SELECT i, 'n' || i, i * 2 FROM generate_series(0, " + (ROWS - 1) + ") i");
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (ds != null) ds.close();
  }

  @Benchmark
  public long pointSelectJdbc() throws Exception {
    var id = ThreadLocalRandom.current().nextInt(ROWS);
    try (Connection conn = ds.getConnection();
        PreparedStatement ps =
            conn.prepareStatement("SELECT name, val FROM bench_rows_jdbc WHERE id = ?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(2);
      }
    }
  }

  private void exec(String sql) {
    try (Connection conn = ds.getConnection();
        Statement st = conn.createStatement()) {
      st.execute(sql);
    } catch (Exception e) {
      throw new IllegalStateException("benchmark setup failed: " + sql, e);
    }
  }

  /** Build a JDBC URL from a libpq conninfo string so both benchmarks read the same MONOLITH_TEST_CONNINFO. */
  private static String jdbcUrl(String conninfo) {
    var host = field(conninfo, "host");
    var port = field(conninfo, "port");
    var dbname = field(conninfo, "dbname");
    var authority = (host == null ? "localhost" : host) + (port == null ? "" : ":" + port);
    return "jdbc:postgresql://" + authority + "/" + (dbname == null ? "postgres" : dbname);
  }

  /** Extract a {@code key=value} field from a space-separated libpq conninfo string, or null. */
  private static String field(String conninfo, String key) {
    for (var token : conninfo.trim().split("\\s+")) {
      var eq = token.indexOf('=');
      if (eq > 0 && token.substring(0, eq).equals(key)) {
        return token.substring(eq + 1);
      }
    }
    return null;
  }
}
