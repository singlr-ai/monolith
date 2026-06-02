/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the logical-replication slot and publication the reactive change feed streams from, using
 * the stable {@code pgoutput} plugin. Needs {@code wal_level = logical}. For continuous low-latency
 * consumption use the streaming {@link WalStream}; this polling {@link #drain} returns the same
 * pgoutput changes on demand, decoded by {@link PgOutput}.
 */
public final class Wal {

  /** The publication a slot streams: one {@code FOR ALL TABLES}, named after the slot. */
  public static String publication(String slot) {
    return slot + "_pub";
  }

  public static void recreate(MemorySegment conn, String slot) {
    drop(conn, slot);
    exec(conn, "CREATE PUBLICATION " + publication(slot) + " FOR ALL TABLES");
    exec(conn, "SELECT pg_create_logical_replication_slot('" + slot + "', 'pgoutput')");
  }

  public static void drop(MemorySegment conn, String slot) {
    exec(conn, "SELECT pg_drop_replication_slot('" + slot
        + "') FROM pg_replication_slots WHERE slot_name = '" + slot + "'");
    exec(conn, "DROP PUBLICATION IF EXISTS " + publication(slot));
  }

  /**
   * The {@link SlotHealth} of {@code slot}: whether it exists, whether a consumer is attached, its
   * {@code wal_status}, and how much WAL it is retaining. Poll this to catch a stalled consumer before
   * its retained WAL fills the disk.
   */
  public static SlotHealth health(MemorySegment conn, String slot) {
    try (Arena a = Arena.ofConfined()) {
      List<String> rows = Pg.textColumn(a, conn,
          "SELECT active::text || '|' || coalesce(wal_status, 'none') || '|'"
              + " || coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), 0)::bigint::text"
              + " FROM pg_replication_slots WHERE slot_name = '" + slot + "'").getOrThrow();
      return rows.isEmpty() ? new SlotHealth(false, false, "none", 0) : parseHealth(rows.get(0));
    }
  }

  private static SlotHealth parseHealth(String row) {
    String[] parts = row.split("\\|"); // active('true'/'false') | wal_status | retained_bytes
    return new SlotHealth(true, Boolean.parseBoolean(parts[0]), parts[1], Long.parseLong(parts[2]));
  }

  /**
   * Drops {@code slot} only if it has no attached consumer, to reclaim the WAL an orphaned slot (a dead
   * consumer) is retaining without disturbing a live one. Returns whether a slot was dropped. The
   * publication is left in place.
   */
  public static boolean dropInactive(MemorySegment conn, String slot) {
    try (Arena a = Arena.ofConfined()) {
      List<String> dropped = Pg.textColumn(a, conn,
          "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots"
              + " WHERE slot_name = '" + slot + "' AND NOT active").getOrThrow();
      return !dropped.isEmpty();
    }
  }

  /** Pull and decode all pgoutput changes buffered since the last drain; consumes them. */
  public static List<WalChange> drain(MemorySegment conn, String slot) {
    try (Arena a = Arena.ofConfined()) {
      List<String> rows = Pg.textColumn(a, conn,
          "SELECT encode(data, 'hex') FROM pg_logical_slot_get_binary_changes('" + slot
              + "', NULL, NULL, 'proto_version', '1', 'publication_names', '"
              + publication(slot) + "')").getOrThrow();
      PgOutput decoder = new PgOutput();
      List<WalChange> changes = new ArrayList<>();
      for (String hex : rows) {
        WalChange change = decoder.decode(hexToBytes(hex));
        if (change != null) changes.add(change); // skips Begin/Relation/Commit framing
      }
      return changes;
    }
  }

  /** Decode Postgres {@code encode(bytea, 'hex')} output (lowercase, no prefix) to bytes. */
  static byte[] hexToBytes(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex, i * 2, i * 2 + 2, 16);
    }
    return out;
  }

  private static void exec(MemorySegment conn, String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    }
  }

  private Wal() {}
}
