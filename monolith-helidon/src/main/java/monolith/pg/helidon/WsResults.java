/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.helidon;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bos);
      out.writeInt(rows.size());
      for (byte[] row : rows) {
        out.writeInt(row.length);
        out.write(row);
      }
      return bos.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e); // ByteArrayOutputStream never throws
    }
  }

  private WsResults() {}
}
