/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decoder for Postgres's stable logical-replication output plugin, {@code pgoutput} (protocol
 * version 1). Unlike {@code test_decoding}, pgoutput is a documented, versioned binary format meant
 * for production consumers. The stream interleaves {@code Relation} messages (which describe a
 * table's id, name, and column names) with {@code Insert}/{@code Update}/{@code Delete} messages
 * that reference a relation by id; this class caches relations and turns each row message into a
 * {@link WalChange}. Framing messages (Begin, Commit, Type, Origin, Truncate, Message) decode to
 * {@code null}.
 *
 * <p>One instance is stateful (it holds the relation cache) and decodes a single ordered stream.
 */
public final class PgOutput {

  private record Relation(String table, String[] columns) {}

  private final Map<Integer, Relation> relations = new HashMap<>();

  /** Decode one pgoutput message; returns the row change, or {@code null} for a framing message. */
  public WalChange decode(byte[] message) {
    ByteBuffer b = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN);
    char type = (char) b.get();
    return switch (type) {
      case 'R' -> cacheRelation(b);
      case 'I' -> insertOrDelete(b); // relid, 'N', new tuple
      case 'D' -> insertOrDelete(b); // relid, 'K'/'O', old tuple
      case 'U' -> update(b);
      default -> null;               // B, C, T, O, Y, M: framing, no row payload
    };
  }

  private WalChange cacheRelation(ByteBuffer b) {
    int relid = b.getInt();
    cstring(b);                 // namespace
    String table = cstring(b);
    b.get();                    // replica identity setting
    int ncols = b.getShort() & 0xffff;
    String[] columns = new String[ncols];
    for (int i = 0; i < ncols; i++) {
      b.get();                  // column flags
      columns[i] = cstring(b);
      b.getInt();               // type oid
      b.getInt();               // atttypmod
    }
    relations.put(relid, new Relation(table, columns));
    return null;
  }

  private WalChange insertOrDelete(ByteBuffer b) {
    Relation relation = relations.get(b.getInt());
    if (relation == null) return null; // row before its Relation: nothing to map it to
    b.get();                           // tuple kind tag ('N' for insert, 'K'/'O' for delete)
    var values = new LinkedHashMap<String, Set<String>>();
    readTuple(b, relation, values);
    return new WalChange(relation.table(), values);
  }

  private WalChange update(ByteBuffer b) {
    Relation relation = relations.get(b.getInt());
    if (relation == null) return null;
    var values = new LinkedHashMap<String, Set<String>>();
    char tag = (char) b.get();
    if (tag == 'K' || tag == 'O') { // old tuple present (REPLICA IDENTITY USING INDEX / FULL)
      readTuple(b, relation, values);
      b.get();                      // the following 'N' tag
    }
    readTuple(b, relation, values); // new tuple
    return new WalChange(relation.table(), values);
  }

  private static void readTuple(ByteBuffer b, Relation relation, Map<String, Set<String>> into) {
    int ncols = b.getShort() & 0xffff;
    for (int i = 0; i < ncols; i++) {
      char kind = (char) b.get();
      if (kind == 't' || kind == 'b') { // textual or binary value present
        int len = b.getInt();
        byte[] value = new byte[len];
        b.get(value);
        into.computeIfAbsent(relation.columns()[i], k -> new LinkedHashSet<>())
            .add(new String(value, StandardCharsets.UTF_8));
      }
      // 'n' (null) and 'u' (unchanged TOAST) carry no bytes and no value
    }
  }

  private static String cstring(ByteBuffer b) {
    int start = b.position();
    while (b.get() != 0) {
      // advance to the null terminator
    }
    int len = b.position() - start - 1;
    byte[] bytes = new byte[len];
    b.position(start);
    b.get(bytes);
    b.get(); // consume the terminator
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
