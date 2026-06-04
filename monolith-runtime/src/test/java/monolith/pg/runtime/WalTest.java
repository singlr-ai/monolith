/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wal slot/publication identifier safety")
class WalTest {

  // Names that must never reach the privileged replication/admin SQL: quoting/comment/statement
  // breakouts, whitespace, identifier-illegal characters, empty, and over-length.
  private static final List<String> UNSAFE = List.of(
      "",
      "a b",
      "slot'; DROP TABLE users; --",
      "slot'name",
      "slot\"name",
      "slot;name",
      "slot--name",
      "slot/*x*/",
      "1leading_digit",
      "has-dash",
      "has.dot",
      "naïve_unicode",
      "x".repeat(64)); // 64 > the 63-char identifier bound

  private static final List<String> SAFE = List.of(
      "monolith",
      "monolith_health_it",
      "_underscore_lead",
      "Slot_With_Mixed_Case",
      "a",
      "x".repeat(63));

  @Test
  @DisplayName("the identifier validator rejects unsafe names and accepts safe ones")
  void validatorGuardsTheBoundary() {
    assertThrows(IllegalArgumentException.class, () -> Wal.validateSlot(null), "null is not a valid slot");
    for (String bad : UNSAFE) {
      assertThrows(IllegalArgumentException.class, () -> Wal.validateSlot(bad),
          () -> "should reject unsafe slot name: <" + bad + ">");
    }
    for (String ok : SAFE) {
      assertDoesNotThrow(() -> Wal.validateSlot(ok), () -> "should accept safe slot name: <" + ok + ">");
    }
  }

  @Test
  @DisplayName("every public WAL operation validates its slot before touching the connection")
  void publicOperationsValidateBeforeAnyNativeCall() {
    // MemorySegment.NULL would fault if reached; a rejected slot must throw IllegalArgumentException
    // first, proving validation happens before any libpq call.
    String bad = "slot'; DROP TABLE users; --";
    MemorySegment conn = MemorySegment.NULL;
    assertThrows(IllegalArgumentException.class, () -> Wal.recreate(conn, bad));
    assertThrows(IllegalArgumentException.class, () -> Wal.drop(conn, bad));
    assertThrows(IllegalArgumentException.class, () -> Wal.health(conn, bad));
    assertThrows(IllegalArgumentException.class, () -> Wal.dropInactive(conn, bad));
    assertThrows(IllegalArgumentException.class, () -> Wal.drain(conn, bad));
  }
}
