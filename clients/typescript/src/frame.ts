/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Split a Monolith live-query result frame into its row byte-slices. The wire envelope, produced by
 * the server's `WsResults.frame`, is big-endian:
 *
 *     [int32 rowCount] ( [int32 byteLength] [row bytes] )*
 *
 * Each returned slice is a view into `data` (no copy); hand it to a generated `<Name>Reader`.
 */
export function parseFrame(data: Uint8Array): Uint8Array[] {
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const rowCount = view.getInt32(0, false);
  const rows: Uint8Array[] = [];
  let offset = 4;
  for (let i = 0; i < rowCount; i++) {
    const length = view.getInt32(offset, false);
    offset += 4;
    rows.push(data.subarray(offset, offset + length));
    offset += length;
  }
  return rows;
}
