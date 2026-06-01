/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static java.util.Comparator.comparingInt;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Applies ordered, versioned schema migrations and records them, so a schema can evolve over time.
 * Each not-yet-applied migration runs in its own transaction (DDL is transactional in Postgres), in
 * version order, and is recorded in a {@code monolith_migrations} table; re-running applies only what
 * is new. The recorded checksum (CRC32, as Flyway uses) detects a migration edited after it was
 * applied, and a migration older than the latest applied version is rejected as out of order.
 *
 * <p>Pairs with the codegen's {@code schema.lock}: a changed {@code @PgType} shows up as drift in
 * that lock file at build time, which is your cue to write the matching migration here.
 */
public final class Migrator {

  /** A single migration: a strictly-increasing version, a name (for diagnostics), and its SQL. */
  public record Migration(int version, String name, String sql) {}

  private static final String TRACKING_TABLE = """
      CREATE TABLE IF NOT EXISTS monolith_migrations (
        version    int PRIMARY KEY,
        name       text NOT NULL,
        checksum   text NOT NULL,
        applied_at timestamptz NOT NULL DEFAULT now())""";

  /**
   * Apply every migration not yet recorded, in version order. Returns the versions newly applied (in
   * order), or a {@link Result.Failure} describing the first problem (a failed migration, an edited
   * one, or one out of order); on a failed migration its transaction is rolled back and no later
   * migration runs.
   */
  public static Result<List<Integer>> migrate(MemorySegment conn, List<Migration> migrations) {
    if (exec(conn, TRACKING_TABLE) instanceof Result.Failure<Void>(var error, var cause)) {
      return Result.failure("could not create monolith_migrations: " + error, cause);
    }
    Map<Integer, String> applied = readApplied(conn);
    int latest = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);

    List<Migration> pending = new ArrayList<>();
    for (Migration m : migrations.stream().sorted(comparingInt(Migration::version)).toList()) {
      String sum = checksum(m.sql());
      if (applied.containsKey(m.version())) {
        if (!applied.get(m.version()).equals(sum)) {
          return Result.failure("migration " + m.version() + " (" + m.name()
              + ") was modified after it was applied");
        }
      } else if (m.version() <= latest) {
        return Result.failure("migration " + m.version() + " (" + m.name()
            + ") is older than the latest applied version " + latest);
      } else {
        pending.add(m);
      }
    }

    List<Integer> done = new ArrayList<>();
    for (Migration m : pending) {
      if (applyOne(conn, m) instanceof Result.Failure<Void>(var error, var cause)) {
        return Result.failure(error, cause);
      }
      done.add(m.version());
    }
    return Result.success(done);
  }

  private static Result<Void> applyOne(MemorySegment conn, Migration m) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "BEGIN").getOrThrow();
      Result<Void> outcome = runWithinTransaction(a, conn, m);
      Pg.exec(a, conn, outcome.isSuccess() ? "COMMIT" : "ROLLBACK").getOrThrow();
      return outcome;
    }
  }

  /** Run the migration and record it; any failure leaves the caller to roll the transaction back. */
  private static Result<Void> runWithinTransaction(Arena a, MemorySegment conn, Migration m) {
    if (Pg.exec(a, conn, m.sql()) instanceof Result.Failure<Void>(var error, var cause)) {
      return Result.failure("migration " + m.version() + " (" + m.name() + ") failed: " + error, cause);
    }
    var p = PgParam.bind(a, m.version(), m.name(), checksum(m.sql()));
    Result<MemorySegment> recorded = Pg.execParamsBinary(a, conn,
        "INSERT INTO monolith_migrations (version, name, checksum) VALUES ($1, $2, $3)",
        p.values(), p.lengths(), p.formats());
    if (recorded instanceof Result.Failure<MemorySegment>(var error, var cause)) {
      return Result.failure("migration " + m.version() + " (" + m.name()
          + ") could not be recorded: " + error, cause);
    }
    Pg.clear(recorded.getOrThrow());
    return Result.success(null);
  }

  private static Map<Integer, String> readApplied(MemorySegment conn) {
    try (Arena a = Arena.ofConfined()) {
      Map<Integer, String> out = new LinkedHashMap<>();
      for (String row : Pg.textColumn(a, conn,
          "SELECT version || '|' || checksum FROM monolith_migrations").getOrThrow()) {
        int bar = row.indexOf('|');
        out.put(Integer.parseInt(row.substring(0, bar)), row.substring(bar + 1));
      }
      return out;
    }
  }

  private static String checksum(String sql) {
    CRC32 crc = new CRC32();
    crc.update(sql.getBytes(StandardCharsets.UTF_8));
    return Long.toHexString(crc.getValue());
  }

  private static Result<Void> exec(MemorySegment conn, String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, conn, sql);
    }
  }

  private Migrator() {}
}
