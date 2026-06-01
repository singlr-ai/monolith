/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.helidon;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Frames a live-query result set for the wire. Each row is already in Monolith's binary layout
 * (the bytes a generated {@code <Name>Reader} reads); this wraps them in a length-prefixed envelope
 * a client decodes without any per-row delimiter scan:
 *
 * <pre>
 *   [int32 rowCount] ( [int32 byteLength] [row bytes] )*
 * </pre>
 *
 * All integers are big-endian, matching the row payloads and the generated TypeScript readers.
 * Use it from a {@link LiveQueryWsListener.QueryRunner}, or build your own envelope if you prefer.
 */
public final class WsResults {

  public static byte[] frame(List<byte[]> rows) {
    int size = Integer.BYTES;
    for (byte[] row : rows) size += Integer.BYTES + row.length;
    ByteBuffer buf = ByteBuffer.allocate(size); // big-endian by default
    buf.putInt(rows.size());
    for (byte[] row : rows) buf.putInt(row.length).put(row);
    return buf.array();
  }

  private WsResults() {}
}
