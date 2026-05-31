/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import monolith.pg.Json;

/**
 * Encodes Java query parameters to their Postgres <b>binary</b> representation, the same
 * encodings the generated builders use, so a generated {@code <Name>Query}
 * can bind {@code Object...} params for {@code PQexecParams}. Postgres infers the
 * parameter types from the query context, so we only supply the bytes.
 *
 * <p>{@link ByteBuffer} defaults to big-endian (network order).
 */
public final class PgParam {

  private static final long PG_EPOCH_DAYS = 10957L;        // 2000-01-01
  private static final long PG_EPOCH_SECONDS = 946684800L; // 2000-01-01T00:00:00Z

  private PgParam() {}

  /** Bound parameter arrays ready for {@link Pg#execParamsBinary}. */
  public record Bound(MemorySegment[] values, int[] lengths, int[] formats) {}

  public static Bound bind(Arena arena, Object... params) {
    int n = params.length;
    MemorySegment[] values = new MemorySegment[n];
    int[] lengths = new int[n];
    int[] formats = new int[n];
    for (int i = 0; i < n; i++) {
      formats[i] = 1; // binary
      byte[] enc = encode(params[i]);
      if (enc == null) {
        continue; // SQL NULL
      }
      MemorySegment nat = arena.allocate(Math.max(enc.length, 1));
      MemorySegment.copy(MemorySegment.ofArray(enc), 0, nat, 0, enc.length);
      values[i] = nat;
      lengths[i] = enc.length;
    }
    return new Bound(values, lengths, formats);
  }

  /** Postgres binary encoding of a single parameter value (null -> SQL NULL). */
  public static byte[] encode(Object v) {
    return switch (v) {
      case null -> null;
      case String s -> s.getBytes(StandardCharsets.UTF_8);
      case byte[] b -> b;
      case UUID u -> ByteBuffer.allocate(16)
          .putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
      case Boolean b -> new byte[]{(byte) (b ? 1 : 0)};
      case Integer i -> ByteBuffer.allocate(4).putInt(i).array();
      case Long l -> ByteBuffer.allocate(8).putLong(l).array();
      case Short sh -> ByteBuffer.allocate(2).putShort(sh).array();
      case Double d -> ByteBuffer.allocate(8).putDouble(d).array();
      case Float f -> ByteBuffer.allocate(4).putFloat(f).array();
      case BigDecimal bd -> PgCodec.encodeNumeric(bd);
      case Json j -> PgCodec.encodeJsonb(j.value());
      case LocalDate d -> ByteBuffer.allocate(4).putInt((int) (d.toEpochDay() - PG_EPOCH_DAYS)).array();
      case LocalTime t -> ByteBuffer.allocate(8).putLong(t.toNanoOfDay() / 1000L).array();
      case LocalDateTime ldt -> ByteBuffer.allocate(8).putLong(micros(ldt.toEpochSecond(ZoneOffset.UTC), ldt.getNano())).array();
      case Instant ts -> ByteBuffer.allocate(8).putLong(micros(ts.getEpochSecond(), ts.getNano())).array();
      case OffsetDateTime odt -> ByteBuffer.allocate(8).putLong(micros(odt.toInstant().getEpochSecond(), odt.toInstant().getNano())).array();
      default -> throw new IllegalArgumentException(
          "unsupported query parameter type: " + v.getClass().getName());
    };
  }

  private static long micros(long epochSecond, int nano) {
    return (epochSecond - PG_EPOCH_SECONDS) * 1_000_000L + nano / 1000L;
  }
}
