/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PgCodec")
class PgCodecTest {

  @Nested
  @DisplayName("numeric")
  class Numeric {

    @ParameterizedTest
    @ValueSource(strings = {
        "0", "0.00", "123", "-123", "123.45", "-123.45",
        "0.5", "0.00005", "100.00", "1E+2",          // negative scale, leading/trailing zero words
        "9999.0001", "0.0001", "1000000000000.000001"
    })
    void roundTripsThroughBinary(String text) {
      var value = new BigDecimal(text);
      var decoded = PgCodec.decodeNumeric(PgCodec.encodeNumeric(value));
      assertEquals(0, decoded.compareTo(value), () -> "round trip of " + text + " gave " + decoded);
    }

    @Test
    void zeroEncodesToTheCanonicalEightByteHeader() {
      assertArrayEquals(new byte[8], PgCodec.encodeNumeric(BigDecimal.ZERO));
    }

    @Test
    void decodeRejectsNaNAndInfinity() {
      for (int sign : new int[] {0xC000, 0xD000, 0xF000}) {
        var bytes = ByteBuffer.allocate(8)
            .putShort((short) 0).putShort((short) 0).putShort((short) sign).putShort((short) 0).array();
        assertThrows(UnsupportedOperationException.class, () -> PgCodec.decodeNumeric(bytes));
      }
    }
  }

  @Nested
  @DisplayName("jsonb")
  class Jsonb {

    @Test
    void roundTripsKeepingTheVersionByte() {
      byte[] wire = PgCodec.encodeJsonb("{\"a\":1}");
      assertEquals(1, wire[0]);
      assertEquals("{\"a\":1}", PgCodec.decodeJsonb(wire));
    }

    @Test
    void decodeRejectsAWrongVersionByte() {
      var ex = assertThrows(IllegalArgumentException.class,
          () -> PgCodec.decodeJsonb(new byte[] {2, 33}));
      assertTrue(ex.getMessage().contains("2"));
    }

    @Test
    void decodeRejectsEmptyInput() {
      var ex = assertThrows(IllegalArgumentException.class, () -> PgCodec.decodeJsonb(new byte[0]));
      assertTrue(ex.getMessage().contains("none"));
    }
  }

  @Nested
  @DisplayName("arrays")
  class Arrays {

    @Test
    void intArrayRoundTripsIncludingEmpty() {
      assertArrayEquals(new int[0], PgCodec.decodeIntArray(PgCodec.encodeIntArray(new int[0])));
      assertArrayEquals(new int[] {1, -2, 3},
          PgCodec.decodeIntArray(PgCodec.encodeIntArray(new int[] {1, -2, 3})));
    }

    @Test
    void longArrayRoundTripsIncludingEmpty() {
      assertArrayEquals(new long[0], PgCodec.decodeLongArray(PgCodec.encodeLongArray(new long[0])));
      assertArrayEquals(new long[] {1L, -2L, 9_000_000_000L},
          PgCodec.decodeLongArray(PgCodec.encodeLongArray(new long[] {1L, -2L, 9_000_000_000L})));
    }

    @Test
    void textArrayRoundTripsIncludingNullsAndEmpty() {
      assertArrayEquals(new String[0], PgCodec.decodeTextArray(PgCodec.encodeTextArray(new String[0])));
      var in = new String[] {"a", null, "ccc"};
      assertArrayEquals(in, PgCodec.decodeTextArray(PgCodec.encodeTextArray(in)));
    }

    @Test
    void decodeRejectsWrongElementWidths() {
      // an int8[] payload handed to the int4 decoder has element length 8, not 4
      byte[] longs = PgCodec.encodeLongArray(new long[] {1L});
      assertThrows(IllegalStateException.class, () -> PgCodec.decodeIntArray(longs));
      byte[] ints = PgCodec.encodeIntArray(new int[] {1});
      assertThrows(IllegalStateException.class, () -> PgCodec.decodeLongArray(ints));
    }
  }
}
