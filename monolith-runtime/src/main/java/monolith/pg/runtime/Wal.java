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
