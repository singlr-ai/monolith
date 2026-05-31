/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postgres logical replication as the reactive change source. Uses the built-in
 * {@code test_decoding} plugin and drains via {@code pg_logical_slot_get_changes} over the libpq
 * seam. Needs {@code wal_level = logical}. For continuous low-latency consumption use the streaming
 * {@link WalStream}; this polling form returns the same decoded changes on demand.
 */
public final class Wal {

  private static final Pattern TABLE = Pattern.compile("^table public\\.(\\w+):");

  public static void recreate(MemorySegment conn, String slot) {
    drop(conn, slot);
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "SELECT pg_create_logical_replication_slot('" + slot + "', 'test_decoding')")
          .getOrThrow();
    }
  }

  public static void drop(MemorySegment conn, String slot) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "SELECT pg_drop_replication_slot('" + slot
          + "') FROM pg_replication_slots WHERE slot_name = '" + slot + "'").getOrThrow();
    }
  }

  /** Pull and parse all changes since the last drain; consumes them from the slot. */
  public static List<WalChange> drain(MemorySegment conn, String slot) {
    try (Arena a = Arena.ofConfined()) {
      List<String> lines = Pg.textColumn(a, conn,
          "SELECT data FROM pg_logical_slot_get_changes('" + slot + "', NULL, NULL)").getOrThrow();
      List<WalChange> changes = new ArrayList<>();
      for (String line : lines) {
        if (line == null) continue;
        Matcher m = TABLE.matcher(line);
        if (m.find()) changes.add(new WalChange(m.group(1), line)); // skips BEGIN/COMMIT
      }
      return changes;
    }
  }

  private Wal() {}
}
