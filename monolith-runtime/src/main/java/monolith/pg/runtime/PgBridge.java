/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Repacks one binary result row into a generated reader's layout, driven by the
 * reader's own metadata arrays ({@code OFFSET}, {@code WIDTH}). Works for a table
 * row or an arbitrary query projection alike, the result is just typed binary
 * columns either way. Fixed columns are copied to their offset; variable columns
 * append to the tail with an int4 offset/length header; NULL columns set the null
 * bitmap bit.
 */
public final class PgBridge {

  private static final ValueLayout.OfInt BE_INT =
      ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);

  private PgBridge() {}

  public static byte[] row(MemorySegment res, int row, int fixedSize, int[] off, int[] width) {
    int n = off.length;
    int total = fixedSize;
    byte[][] cells = new byte[n][];
    boolean[] nulls = new boolean[n];
    for (int i = 0; i < n; i++) {
      nulls[i] = Pg.getisnull(res, row, i);
      if (nulls[i]) continue;
      cells[i] = Pg.getbytes(res, row, i);
      if (width[i] < 0) total += cells[i].length;
    }
    byte[] buf = new byte[total];
    MemorySegment seg = MemorySegment.ofArray(buf);
    int tail = fixedSize;
    for (int i = 0; i < n; i++) {
      if (nulls[i]) {
        buf[i >> 3] |= (byte) (1 << (i & 7));
        continue;
      }
      byte[] cell = cells[i];
      if (width[i] >= 0) {
        if (cell.length != width[i]) {
          // The SQL result's binary width for a fixed column must equal the declared layout width; a
          // longer cell would overwrite later fields in the buffer. A mismatch means the @PgQuery record
          // type does not match the actual SQL result type — fail loudly rather than corrupt the row.
          throw new IllegalStateException("row " + row + " column " + i + ": expected a fixed-width cell of "
              + width[i] + " bytes but got " + cell.length
              + " (the @PgQuery record type does not match the SQL result type)");
        }
        MemorySegment.copy(MemorySegment.ofArray(cell), 0, seg, off[i], cell.length);
      } else {
        seg.set(BE_INT, off[i], tail);
        seg.set(BE_INT, off[i] + 4, cell.length);
        MemorySegment.copy(MemorySegment.ofArray(cell), 0, seg, tail, cell.length);
        tail += cell.length;
      }
    }
    return buf;
  }
}
