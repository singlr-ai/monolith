/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Codecs between Java values and the Postgres <b>binary</b> wire format for the
 * variable-length composite types: {@code numeric}, {@code jsonb}, and 1-D
 * arrays. Generated readers/builders call these for the corresponding component
 * types; the bytes produced/consumed are exactly what {@code numeric_recv},
 * {@code jsonb_recv}, {@code array_recv} (and their {@code _send} counterparts)
 * expect, so the value flows through libpq unchanged.
 *
 * <p>{@link java.nio.ByteBuffer} defaults to big-endian (network order), which is
 * what the Postgres binary protocol uses.
 */
public final class PgCodec {

  // Element type OIDs used when encoding arrays (must match the column's element).
  public static final int OID_INT4 = 23;
  public static final int OID_INT8 = 20;
  public static final int OID_TEXT = 25;

  // numeric sign words
  private static final int NUMERIC_POS = 0x0000;
  private static final int NUMERIC_NEG = 0x4000;
  private static final int NUMERIC_NAN = 0xC000;
  private static final int NUMERIC_PINF = 0xD000;
  private static final int NUMERIC_NINF = 0xF000;
  private static final int NBASE = 10000;

  private PgCodec() {}

  // ======================= numeric (BigDecimal) ==========================

  /**
   * Encode a {@link BigDecimal} to Postgres {@code numeric} binary:
   * {@code int16 ndigits, weight, sign, dscale} followed by {@code ndigits}
   * base-10000 digit words (most significant first). Leading and trailing
   * all-zero words are trimmed so the output is canonical.
   */
  public static byte[] encodeNumeric(BigDecimal value) {
    int dscale = Math.max(value.scale(), 0);

    if (value.signum() == 0) {
      ByteBuffer z = ByteBuffer.allocate(8);
      z.putShort((short) 0).putShort((short) 0).putShort((short) NUMERIC_POS).putShort((short) dscale);
      return z.array();
    }

    int sign = value.signum() < 0 ? NUMERIC_NEG : NUMERIC_POS;
    String plain = value.abs().toPlainString();
    int dot = plain.indexOf('.');
    String intPart = dot < 0 ? plain : plain.substring(0, dot);
    String fracPart = dot < 0 ? "" : plain.substring(dot + 1);

    // strip leading zeros from the integer part ("007" -> "7", "0" -> "")
    int nz = 0;
    while (nz < intPart.length() && intPart.charAt(nz) == '0') nz++;
    intPart = intPart.substring(nz);

    // left-pad integer part and right-pad fraction part to multiples of 4
    int intPad = (4 - intPart.length() % 4) % 4;
    int fracPad = (4 - fracPart.length() % 4) % 4;
    String intP = "0".repeat(intPad) + intPart;
    String fracP = fracPart + "0".repeat(fracPad);

    int intGroups = intP.length() / 4;
    int weight = intGroups - 1; // power of NBASE of the first word (negative for pure fractions)

    String allDigits = intP + fracP;
    List<Short> digits = new ArrayList<>();
    for (int i = 0; i < allDigits.length(); i += 4) {
      digits.add((short) Integer.parseInt(allDigits.substring(i, i + 4)));
    }
    // Trim leading zero words (decrementing weight) and trailing zero words. The value is nonzero
    // here, so at least one word is nonzero and each loop always stops on it (the list never empties).
    while (digits.get(0) == 0) {
      digits.remove(0);
      weight--;
    }
    while (digits.get(digits.size() - 1) == 0) {
      digits.remove(digits.size() - 1);
    }

    ByteBuffer buf = ByteBuffer.allocate(8 + digits.size() * 2);
    buf.putShort((short) digits.size());
    buf.putShort((short) weight);
    buf.putShort((short) sign);
    buf.putShort((short) dscale);
    for (short d : digits) buf.putShort(d);
    return buf.array();
  }

  /** Decode Postgres {@code numeric} binary to a {@link BigDecimal}. */
  public static BigDecimal decodeNumeric(byte[] b) {
    ByteBuffer buf = ByteBuffer.wrap(b);
    int ndigits = buf.getShort() & 0xFFFF;
    int weight = buf.getShort();           // signed
    int sign = buf.getShort() & 0xFFFF;
    int dscale = buf.getShort() & 0xFFFF;
    if (sign == NUMERIC_NAN || sign == NUMERIC_PINF || sign == NUMERIC_NINF) {
      throw new UnsupportedOperationException(
          "numeric NaN/Infinity has no BigDecimal representation");
    }
    BigDecimal result = BigDecimal.ZERO;
    for (int i = 0; i < ndigits; i++) {
      int digit = buf.getShort() & 0xFFFF;
      result = result.add(BigDecimal.valueOf(digit).movePointRight(4 * (weight - i)));
    }
    if (sign == NUMERIC_NEG) result = result.negate();
    return result.setScale(dscale, RoundingMode.HALF_UP);
  }

  // ======================= jsonb =========================================

  /** Encode JSON text to {@code jsonb} binary: a 0x01 version byte + UTF-8 text. */
  public static byte[] encodeJsonb(String json) {
    byte[] text = json.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[text.length + 1];
    out[0] = 1; // jsonb binary version
    System.arraycopy(text, 0, out, 1, text.length);
    return out;
  }

  /** Decode {@code jsonb} binary (version byte + UTF-8 text) to JSON text. */
  public static String decodeJsonb(byte[] b) {
    if (b.length < 1 || b[0] != 1) {
      throw new IllegalArgumentException("unexpected jsonb version byte: " + (b.length == 0 ? "none" : b[0]));
    }
    return new String(b, 1, b.length - 1, StandardCharsets.UTF_8);
  }

  // ======================= arrays (1-D) ==================================

  public static byte[] encodeIntArray(int[] a) {
    int ndim = a.length == 0 ? 0 : 1;
    ByteBuffer buf = ByteBuffer.allocate(12 + (ndim == 1 ? 8 : 0) + a.length * 8);
    buf.putInt(ndim).putInt(0).putInt(OID_INT4);
    if (ndim == 1) buf.putInt(a.length).putInt(1);
    for (int v : a) buf.putInt(4).putInt(v);
    return buf.array();
  }

  public static int[] decodeIntArray(byte[] b) {
    ByteBuffer buf = ByteBuffer.wrap(b);
    int ndim = buf.getInt();
    buf.getInt(); // hasnull flags
    buf.getInt(); // elemtype
    if (ndim == 0) return new int[0];
    int len = buf.getInt();
    buf.getInt(); // lower bound
    int[] out = new int[len];
    for (int i = 0; i < len; i++) {
      int elemLen = buf.getInt();
      if (elemLen != 4) throw new IllegalStateException("int4[] element length " + elemLen);
      out[i] = buf.getInt();
    }
    return out;
  }

  public static byte[] encodeLongArray(long[] a) {
    int ndim = a.length == 0 ? 0 : 1;
    ByteBuffer buf = ByteBuffer.allocate(12 + (ndim == 1 ? 8 : 0) + a.length * 12);
    buf.putInt(ndim).putInt(0).putInt(OID_INT8);
    if (ndim == 1) buf.putInt(a.length).putInt(1);
    for (long v : a) buf.putInt(8).putLong(v);
    return buf.array();
  }

  public static long[] decodeLongArray(byte[] b) {
    ByteBuffer buf = ByteBuffer.wrap(b);
    int ndim = buf.getInt();
    buf.getInt();
    buf.getInt();
    if (ndim == 0) return new long[0];
    int len = buf.getInt();
    buf.getInt();
    long[] out = new long[len];
    for (int i = 0; i < len; i++) {
      int elemLen = buf.getInt();
      if (elemLen != 8) throw new IllegalStateException("int8[] element length " + elemLen);
      out[i] = buf.getLong();
    }
    return out;
  }

  /** {@code text[]}: supports NULL elements (encoded as element length -1). */
  public static byte[] encodeTextArray(String[] a) {
    int ndim = a.length == 0 ? 0 : 1;
    boolean hasNull = false;
    int payload = 0;
    byte[][] enc = new byte[a.length][];
    for (int i = 0; i < a.length; i++) {
      if (a[i] == null) { hasNull = true; payload += 4; }
      else { enc[i] = a[i].getBytes(StandardCharsets.UTF_8); payload += 4 + enc[i].length; }
    }
    ByteBuffer buf = ByteBuffer.allocate(12 + (ndim == 1 ? 8 : 0) + payload);
    buf.putInt(ndim).putInt(hasNull ? 1 : 0).putInt(OID_TEXT);
    if (ndim == 1) buf.putInt(a.length).putInt(1);
    for (int i = 0; i < a.length; i++) {
      if (a[i] == null) { buf.putInt(-1); }
      else { buf.putInt(enc[i].length).put(enc[i]); }
    }
    return buf.array();
  }

  public static String[] decodeTextArray(byte[] b) {
    ByteBuffer buf = ByteBuffer.wrap(b);
    int ndim = buf.getInt();
    buf.getInt();
    buf.getInt();
    if (ndim == 0) return new String[0];
    int len = buf.getInt();
    buf.getInt();
    String[] out = new String[len];
    for (int i = 0; i < len; i++) {
      int elemLen = buf.getInt();
      if (elemLen == -1) { out[i] = null; continue; }
      byte[] bytes = new byte[elemLen];
      buf.get(bytes);
      out[i] = new String(bytes, StandardCharsets.UTF_8);
    }
    return out;
  }
}
