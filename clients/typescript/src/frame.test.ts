/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseFrame } from './frame.ts';

/** Build a wire frame: [int32 rowCount] ([int32 len] [bytes])*. */
function frame(rows: number[][]): Uint8Array {
  const bytes: number[] = [];
  const put32 = (v: number) => bytes.push((v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff);
  put32(rows.length);
  for (const row of rows) {
    put32(row.length);
    bytes.push(...row);
  }
  return new Uint8Array(bytes);
}

test('an empty frame yields no rows', () => {
  assert.deepEqual(parseFrame(frame([])), []);
});

test('rows are returned in order as byte slices', () => {
  const rows = parseFrame(frame([[10, 20], [30]]));
  assert.equal(rows.length, 2);
  assert.deepEqual([...rows[0]], [10, 20]);
  assert.deepEqual([...rows[1]], [30]);
});
