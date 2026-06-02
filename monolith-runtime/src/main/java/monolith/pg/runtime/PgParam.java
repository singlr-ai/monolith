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
import java.util.List;
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
      case Enum<?> e -> e.name().getBytes(StandardCharsets.UTF_8); // a Postgres enum receives its label
      case int[] a -> PgCodec.encodeIntArray(a);
      case long[] a -> PgCodec.encodeLongArray(a);
      case String[] a -> PgCodec.encodeTextArray(a);
      case UUID[] a -> PgCodec.encodeUuidArray(a);
      case List<?> list -> encodeList(list);
      default -> throw new IllegalArgumentException(
          "unsupported query parameter type: " + v.getClass().getName());
    };
  }

  /**
   * Encodes a {@code List} as a Postgres array, inferring the element type from the first non-null
   * element. An empty or all-null list is rejected, because its element type cannot be inferred; pass
   * a typed array (such as {@code new UUID[0]}) in that case.
   */
  private static byte[] encodeList(List<?> list) {
    Object sample = null;
    for (Object element : list) {
      if (element != null) {
        sample = element;
        break;
      }
    }
    return switch (sample) {
      case Integer ignored -> PgCodec.encodeIntArray(toIntArray(list));
      case Long ignored -> PgCodec.encodeLongArray(toLongArray(list));
      case String ignored -> PgCodec.encodeTextArray(list.toArray(new String[0]));
      case UUID ignored -> PgCodec.encodeUuidArray(list.toArray(new UUID[0]));
      case null, default -> throw new IllegalArgumentException(
          "cannot encode list parameter " + list + ": elements must be Integer, Long, String, or"
              + " UUID, and an empty or all-null list needs a typed array (e.g. new UUID[0]) instead");
    };
  }

  private static int[] toIntArray(List<?> list) {
    int[] out = new int[list.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = (Integer) list.get(i);
    }
    return out;
  }

  private static long[] toLongArray(List<?> list) {
    long[] out = new long[list.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = (Long) list.get(i);
    }
    return out;
  }

  private static long micros(long epochSecond, int nano) {
    return (epochSecond - PG_EPOCH_SECONDS) * 1_000_000L + nano / 1000L;
  }
}
