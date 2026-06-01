/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * Applies schema migrations and records them, so a schema can evolve over time. It is forward-only by
 * design (a "down" migration can't restore data a change dropped, and rots untested until an
 * emergency); roll forward with a new migration, and use point-in-time recovery for true rollback.
 *
 * <p>It supports the pieces a real project needs:
 * <ul>
 *   <li><b>Versioned</b> {@link Migration}s, applied once each, in version order, each in its own
 *       transaction. Re-running applies only what is new.
 *   <li><b>Repeatable</b> {@link RepeatableMigration}s (for views, functions, procedures), re-applied
 *       whenever their content changes, after the versioned ones.
 *   <li><b>{@link #status}</b> to see what would run before running it.
 *   <li><b>{@link #baseline}</b> to adopt an existing, non-empty database at a starting version.
 * </ul>
 *
 * <p>A CRC32 checksum (as Flyway uses) detects a versioned migration edited after it was applied, and
 * a migration older than the latest applied version is rejected as out of order. Pairs with the
 * codegen's {@code schema.lock}: a changed {@code @PgType} surfaces as build-time drift, your cue to
 * write the matching migration here. See {@code docs/MIGRATIONS.md}.
 */
public final class Migrator {

  /** A versioned migration: a strictly-increasing version, a name, and its SQL. */
  public record Migration(int version, String name, String sql) {}

  /** A repeatable migration (re-applied when its SQL changes): a name and its SQL. */
  public record RepeatableMigration(String name, String sql) {}

  /** What a {@link #migrate} run did. */
  public record Outcome(List<Integer> appliedVersions, List<String> appliedRepeatables) {}

  /** What {@link #migrate} <i>would</i> do for the versioned set, computed without applying anything. */
  public record Status(List<Integer> applied, List<Migration> pending, List<String> problems) {
    public boolean isClean() {
      return problems.isEmpty();
    }
  }

  private static final String BASELINE_MARKER = "baseline";

  private static final String TRACKING = """
      CREATE TABLE IF NOT EXISTS monolith_migrations (
        version int PRIMARY KEY, name text NOT NULL, checksum text NOT NULL,
        applied_at timestamptz NOT NULL DEFAULT now());
      CREATE TABLE IF NOT EXISTS monolith_repeatable (
        name text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())""";

  /** Report what {@link #migrate} would do for {@code versioned}, without applying anything. */
  public static Result<Status> status(MemorySegment conn, List<Migration> versioned) {
    Result<Void> ensured = ensureTracking(conn);
    if (ensured instanceof Result.Failure<Void>(var error, var cause)) {
      return Result.failure(error, cause);
    }
    Map<Integer, String> recorded = readVersioned(conn);
    int baseline = recorded.entrySet().stream()
        .filter(e -> e.getValue().equals(BASELINE_MARKER))
        .mapToInt(Map.Entry::getKey).max().orElse(0);
    int latest = recorded.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);

    List<Migration> pending = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (Migration m : versioned.stream().sorted(comparingInt(Migration::version)).toList()) {
      if (m.version() <= baseline) {
        continue; // below the baseline: considered already handled
      }
      if (recorded.containsKey(m.version())) {
        if (!recorded.get(m.version()).equals(checksum(m.sql()))) {
          problems.add("migration " + m.version() + " (" + m.name()
              + ") was modified after it was applied");
        }
      } else if (m.version() <= latest) {
        problems.add("migration " + m.version() + " (" + m.name()
            + ") is older than the latest applied version " + latest);
      } else {
        pending.add(m);
      }
    }
    return Result.success(new Status(recorded.keySet().stream().sorted().toList(), pending, problems));
  }

  /** Apply all pending versioned migrations (no repeatables). */
  public static Result<Outcome> migrate(MemorySegment conn, List<Migration> versioned) {
    return migrate(conn, versioned, List.of());
  }

  /**
   * Apply all pending versioned migrations in order, then every repeatable whose SQL has changed (in
   * name order). Returns what was applied, or the first problem as a {@link Result.Failure}; a failed
   * migration rolls back and stops the run.
   */
  public static Result<Outcome> migrate(
      MemorySegment conn, List<Migration> versioned, List<RepeatableMigration> repeatables) {
    Result<Status> planned = status(conn, versioned);
    if (planned instanceof Result.Failure<Status>(var error, var cause)) {
      return Result.failure(error, cause);
    }
    Status status = planned.getOrThrow();
    if (!status.isClean()) {
      return Result.failure(status.problems().get(0));
    }

    List<Integer> appliedVersions = new ArrayList<>();
    for (Migration m : status.pending()) {
      Result<Void> applied = applyVersioned(conn, m);
      if (applied instanceof Result.Failure<Void>(var error, var cause)) {
        return Result.failure(error, cause);
      }
      appliedVersions.add(m.version());
    }

    Map<String, String> recordedRepeatable = readRepeatable(conn);
    List<String> appliedRepeatables = new ArrayList<>();
    for (RepeatableMigration r : repeatables.stream().sorted(comparing(RepeatableMigration::name)).toList()) {
      if (!checksum(r.sql()).equals(recordedRepeatable.get(r.name()))) { // new or changed
        Result<Void> applied = applyRepeatable(conn, r);
        if (applied instanceof Result.Failure<Void>(var error, var cause)) {
          return Result.failure(error, cause);
        }
        appliedRepeatables.add(r.name());
      }
    }
    return Result.success(new Outcome(appliedVersions, appliedRepeatables));
  }

  /**
   * Mark an existing database at {@code version}, so {@link #migrate} only applies versions strictly
   * greater. Use once when adopting Monolith on a schema that already has tables.
   */
  public static Result<Void> baseline(MemorySegment conn, int version, String name) {
    Result<Void> ensured = ensureTracking(conn);
    if (ensured instanceof Result.Failure<Void>(var error, var cause)) {
      return Result.failure(error, cause);
    }
    return inTransaction(conn, a -> record(a, conn,
        "INSERT INTO monolith_migrations (version, name, checksum) VALUES ($1, $2, $3)",
        "baseline at version " + version, version, name, BASELINE_MARKER));
  }

  // ---- applying ----

  private static Result<Void> applyVersioned(MemorySegment conn, Migration m) {
    return inTransaction(conn, a -> {
      if (Pg.exec(a, conn, m.sql()) instanceof Result.Failure<Void>(var error, var cause)) {
        return Result.failure("migration " + m.version() + " (" + m.name() + ") failed: " + error, cause);
      }
      return record(a, conn,
          "INSERT INTO monolith_migrations (version, name, checksum) VALUES ($1, $2, $3)",
          "migration " + m.version() + " (" + m.name() + ") could not be recorded",
          m.version(), m.name(), checksum(m.sql()));
    });
  }

  private static Result<Void> applyRepeatable(MemorySegment conn, RepeatableMigration r) {
    return inTransaction(conn, a -> {
      if (Pg.exec(a, conn, r.sql()) instanceof Result.Failure<Void>(var error, var cause)) {
        return Result.failure("repeatable " + r.name() + " failed: " + error, cause);
      }
      return record(a, conn,
          "INSERT INTO monolith_repeatable (name, checksum) VALUES ($1, $2)"
              + " ON CONFLICT (name) DO UPDATE SET checksum = EXCLUDED.checksum, applied_at = now()",
          "repeatable " + r.name() + " could not be recorded", r.name(), checksum(r.sql()));
    });
  }

  /** Run a parameterized {@code INSERT} (binding {@code params} as $1..$n), mapping a failure to {@code onError}. */
  private static Result<Void> record(
      Arena a, MemorySegment conn, String sql, String onError, Object... params) {
    var p = PgParam.bind(a, params);
    Result<MemorySegment> r =
        Pg.execParamsBinary(a, conn, sql, p.values(), p.lengths(), p.formats());
    if (r instanceof Result.Failure<MemorySegment>(var error, var cause)) {
      return Result.failure(onError + ": " + error, cause);
    }
    Pg.clear(r.getOrThrow());
    return Result.success(null);
  }

  /** BEGIN, run {@code work}, then COMMIT on success or ROLLBACK on failure; returns work's result. */
  private static Result<Void> inTransaction(MemorySegment conn, Function<Arena, Result<Void>> work) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "BEGIN").getOrThrow();
      Result<Void> outcome = work.apply(a);
      Pg.exec(a, conn, outcome.isSuccess() ? "COMMIT" : "ROLLBACK").getOrThrow();
      return outcome;
    }
  }

  // ---- reading ----

  private static Result<Void> ensureTracking(MemorySegment conn) {
    Result<Void> created = exec(conn, TRACKING);
    if (created instanceof Result.Failure<Void>(var error, var cause)) {
      return Result.failure("could not create the monolith_migrations tracking tables: " + error, cause);
    }
    return Result.success(null);
  }

  private static Map<Integer, String> readVersioned(MemorySegment conn) {
    Map<Integer, String> out = new LinkedHashMap<>();
    for (String row : textRows(conn, "SELECT version || '|' || checksum FROM monolith_migrations")) {
      int bar = row.indexOf('|');
      out.put(Integer.parseInt(row.substring(0, bar)), row.substring(bar + 1));
    }
    return out;
  }

  private static Map<String, String> readRepeatable(MemorySegment conn) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String row : textRows(conn, "SELECT name || '|' || checksum FROM monolith_repeatable")) {
      int bar = row.indexOf('|');
      out.put(row.substring(0, bar), row.substring(bar + 1));
    }
    return out;
  }

  private static List<String> textRows(MemorySegment conn, String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, conn, sql).getOrThrow();
    }
  }

  private static Result<Void> exec(MemorySegment conn, String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, conn, sql);
    }
  }

  private static String checksum(String sql) {
    CRC32 crc = new CRC32();
    crc.update(sql.getBytes(StandardCharsets.UTF_8));
    return Long.toHexString(crc.getValue());
  }

  private Migrator() {}
}
