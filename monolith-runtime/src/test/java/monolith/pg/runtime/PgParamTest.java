/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import monolith.pg.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PgParam")
class PgParamTest {

  @Nested
  @DisplayName("encode")
  class Encode {

    @Test
    void nullEncodesToSqlNull() {
      assertNull(PgParam.encode(null));
    }

    @Test
    void stringUsesUtf8() {
      assertArrayEquals(new byte[] {104, 105}, PgParam.encode("hi"));
    }

    @Test
    void byteArrayIsPassedThroughUnchanged() {
      var bytes = new byte[] {9, 8, 7};
      assertSame(bytes, PgParam.encode(bytes));
    }

    @Test
    void uuidIsSixteenBigEndianBytes() {
      assertArrayEquals(new byte[16], PgParam.encode(new UUID(0L, 0L)));
    }

    @Test
    void booleanIsOneByte() {
      assertArrayEquals(new byte[] {1}, PgParam.encode(Boolean.TRUE));
      assertArrayEquals(new byte[] {0}, PgParam.encode(Boolean.FALSE));
    }

    @Test
    void fixedWidthNumbersAreBigEndian() {
      assertArrayEquals(new byte[] {0, 0, 0, 1}, PgParam.encode(1));
      assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}, PgParam.encode(1L));
      assertArrayEquals(new byte[] {0, 1}, PgParam.encode((short) 1));
      assertEquals(8, PgParam.encode(1.5d).length);
      assertEquals(4, PgParam.encode(1.5f).length);
    }

    @Test
    void bigDecimalMatchesTheNumericCodec() {
      var v = new BigDecimal("123.45");
      assertArrayEquals(PgCodec.encodeNumeric(v), PgParam.encode(v));
    }

    @Test
    void jsonMatchesTheJsonbCodec() {
      assertArrayEquals(PgCodec.encodeJsonb("{}"), PgParam.encode(new Json("{}")));
    }

    @Test
    void temporalTypesUsePostgresEpochs() {
      assertArrayEquals(new byte[4], PgParam.encode(LocalDate.of(2000, 1, 1))); // day 0
      assertArrayEquals(new byte[8], PgParam.encode(LocalTime.of(0, 0)));       // µs 0
      assertEquals(8, PgParam.encode(LocalDateTime.of(2000, 1, 1, 0, 0)).length);
      assertEquals(8, PgParam.encode(Instant.EPOCH).length);
      assertEquals(8, PgParam.encode(OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)).length);
    }

    @Test
    void unsupportedTypeIsRejected() {
      var ex = assertThrows(IllegalArgumentException.class, () -> PgParam.encode('x'));
      assertEquals(true, ex.getMessage().contains("Character"));
    }
  }

  @Nested
  @DisplayName("bind")
  class Bind {

    @Test
    void buildsParallelArraysWithBinaryFormatAndNullSlots() {
      try (var arena = Arena.ofConfined()) {
        var bound = PgParam.bind(arena, "hi", null, 42);
        assertEquals(3, bound.values().length);
        for (int fmt : bound.formats()) {
          assertEquals(1, fmt); // every parameter is binary
        }
        assertEquals(2, bound.lengths()[0]); // "hi"
        assertNull(bound.values()[1]);        // SQL NULL: no native segment
        assertEquals(0, bound.lengths()[1]);
        assertEquals(4, bound.lengths()[2]);  // int
      }
    }

    @Test
    void bindWithNoParamsIsEmpty() {
      try (var arena = Arena.ofConfined()) {
        assertEquals(0, PgParam.bind(arena).values().length);
      }
    }
  }
}
