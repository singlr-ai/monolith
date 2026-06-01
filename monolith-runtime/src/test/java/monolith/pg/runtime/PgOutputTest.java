/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PgOutput")
class PgOutputTest {

  /** A tiny builder for crafting pgoutput wire messages, big-endian like the protocol. */
  private static final class Msg {
    private final ByteArrayOutputStream o = new ByteArrayOutputStream();

    Msg u8(int v) { o.write(v & 0xff); return this; }
    Msg i16(int v) { return u8(v >> 8).u8(v); }
    Msg i32(int v) { return u8(v >> 24).u8(v >> 16).u8(v >> 8).u8(v); }
    Msg cstr(String s) { o.writeBytes(s.getBytes(StandardCharsets.UTF_8)); return u8(0); }
    Msg text(String s) { var x = s.getBytes(StandardCharsets.UTF_8); return u8('t').i32(x.length).raw(x); }
    Msg bin(byte[] x) { return u8('b').i32(x.length).raw(x); }
    Msg nul() { return u8('n'); }
    Msg unchanged() { return u8('u'); }
    Msg raw(byte[] x) { o.writeBytes(x); return this; }
    byte[] done() { return o.toByteArray(); }
  }

  private static byte[] relation(int relid, String table, String... columns) {
    var m = new Msg().u8('R').i32(relid).cstr("public").cstr(table).u8('f').i16(columns.length);
    for (String c : columns) m.u8(0).cstr(c).i32(0).i32(-1); // flags, name, type oid, atttypmod
    return m.done();
  }

  @Test
  void aRelationCachesTheTableThenAnInsertMapsItsColumns() {
    var pg = new PgOutput();
    assertNull(pg.decode(relation(1, "widgets", "id", "name")));

    var change = pg.decode(new Msg().u8('I').i32(1).u8('N').i16(2)
        .text("w1").text("hello").done());

    assertEquals("widgets", change.table());
    assertEquals(Set.of("w1"), change.valuesOf("id"));
    assertEquals(Set.of("hello"), change.valuesOf("name"));
  }

  @Test
  void anUpdateWithAFullOldTupleCarriesBothOldAndNew() {
    var pg = new PgOutput();
    pg.decode(relation(1, "widgets", "id", "name"));

    var change = pg.decode(new Msg().u8('U').i32(1)
        .u8('O').i16(2).text("w1").text("old")   // old tuple (REPLICA IDENTITY FULL)
        .u8('N').i16(2).text("w1").text("new")    // new tuple
        .done());

    assertEquals(Set.of("w1"), change.valuesOf("id"));          // unchanged: old and new coincide
    assertEquals(Set.of("old", "new"), change.valuesOf("name")); // both visible
  }

  @Test
  void anUpdateWithAKeyOldTupleIsAccepted() {
    var pg = new PgOutput();
    pg.decode(relation(1, "widgets", "id", "name"));

    var change = pg.decode(new Msg().u8('U').i32(1)
        .u8('K').i16(2).text("w1").nul()          // key-only old tuple
        .u8('N').i16(2).text("w1").text("new")
        .done());

    assertEquals(Set.of("new"), change.valuesOf("name"));
  }

  @Test
  void anUpdateWithoutAnOldTupleHasOnlyTheNewValues() {
    var pg = new PgOutput();
    pg.decode(relation(1, "widgets", "id", "name"));

    var change = pg.decode(new Msg().u8('U').i32(1).u8('N').i16(2).text("w1").text("new").done());

    assertEquals(Set.of("new"), change.valuesOf("name"));
  }

  @Test
  void aDeleteCarriesTheOldRow() {
    var pg = new PgOutput();
    pg.decode(relation(1, "widgets", "id", "name"));

    var change = pg.decode(new Msg().u8('D').i32(1).u8('O').i16(2).text("w1").text("gone").done());

    assertEquals("widgets", change.table());
    assertEquals(Set.of("w1"), change.valuesOf("id"));
  }

  @Test
  void nullAndUnchangedColumnsAreOmittedWhileBinaryValuesAreKept() {
    var pg = new PgOutput();
    pg.decode(relation(2, "thing", "a", "b", "c", "d"));

    var change = pg.decode(new Msg().u8('I').i32(2).u8('N').i16(4)
        .text("present").nul().unchanged().bin(new byte[] {120}) // 'x'
        .done());

    assertEquals(Set.of("present"), change.valuesOf("a"));
    assertTrue(change.valuesOf("b").isEmpty(), "null column has no value");
    assertTrue(change.valuesOf("c").isEmpty(), "unchanged-toast column has no value");
    assertEquals(Set.of("x"), change.valuesOf("d"), "binary value decoded");
  }

  @Test
  void framingMessagesDecodeToNull() {
    var pg = new PgOutput();
    assertNull(pg.decode(new Msg().u8('B').done())); // Begin
    assertNull(pg.decode(new Msg().u8('C').done())); // Commit
  }

  @Test
  void aRowMessageBeforeItsRelationIsIgnored() {
    var pg = new PgOutput();
    assertNull(pg.decode(new Msg().u8('I').i32(99).u8('N').i16(1).text("x").done()));
    assertNull(pg.decode(new Msg().u8('U').i32(99).u8('N').i16(1).text("x").done()));
    assertNull(pg.decode(new Msg().u8('D').i32(99).u8('O').i16(1).text("x").done()));
  }
}
