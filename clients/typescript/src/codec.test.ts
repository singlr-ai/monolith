/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  decodeInt4Array,
  decodeInt8Array,
  decodeJsonb,
  decodeNumeric,
  decodeTextArray,
} from './codec.ts';

/** A big-endian byte builder, matching the Postgres wire order. */
class Bytes {
  private a: number[] = [];
  u8(v: number) { this.a.push(v & 0xff); return this; }
  i16(v: number) { this.a.push((v >> 8) & 0xff, v & 0xff); return this; }
  i32(v: number) { this.a.push((v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff); return this; }
  i64(v: bigint) { for (let s = 56n; s >= 0n; s -= 8n) this.a.push(Number((v >> s) & 0xffn)); return this; }
  raw(b: Uint8Array) { this.a.push(...b); return this; }
  done() { return new Uint8Array(this.a); }
}

function numeric(ndigits: number, weight: number, sign: number, dscale: number, words: number[]) {
  const b = new Bytes().i16(ndigits).i16(weight).i16(sign).i16(dscale);
  for (const w of words) b.i16(w);
  return b.done();
}

test('jsonb decodes to the parsed value', () => {
  const wire = new Uint8Array([1, ...new TextEncoder().encode('{"a":[1,2]}')]);
  assert.deepEqual(decodeJsonb(wire), { a: [1, 2] });
});

test('jsonb rejects a wrong or missing version byte', () => {
  assert.throws(() => decodeJsonb(new Uint8Array([2, 33])), /version byte: 2/);
  assert.throws(() => decodeJsonb(new Uint8Array([])), /none/);
});

test('numeric decodes to a precise decimal string', () => {
  assert.equal(decodeNumeric(numeric(2, 0, 0x0000, 2, [123, 4500])), '123.45');
  assert.equal(decodeNumeric(numeric(1, 0, 0x4000, 0, [42])), '-42');         // negative, no fraction
  assert.equal(decodeNumeric(numeric(0, 0, 0x0000, 0, [])), '0');             // zero
  assert.equal(decodeNumeric(numeric(1, 1, 0x0000, 0, [5])), '50000');        // trailing zero words
  assert.equal(decodeNumeric(numeric(1, -1, 0x0000, 2, [500])), '0.05');      // pure fraction
});

test('numeric special values', () => {
  assert.equal(decodeNumeric(numeric(0, 0, 0xc000, 0, [])), 'NaN');
  assert.equal(decodeNumeric(numeric(0, 0, 0xd000, 0, [])), 'Infinity');
  assert.equal(decodeNumeric(numeric(0, 0, 0xf000, 0, [])), '-Infinity');
});

test('int4[] decodes including the empty array', () => {
  assert.deepEqual(decodeInt4Array(new Bytes().i32(0).i32(0).i32(23).done()), []);
  const wire = new Bytes().i32(1).i32(0).i32(23).i32(3).i32(1)
    .i32(4).i32(1).i32(4).i32(-2).i32(4).i32(3).done();
  assert.deepEqual(decodeInt4Array(wire), [1, -2, 3]);
});

test('int8[] decodes to bigints', () => {
  const wire = new Bytes().i32(1).i32(0).i32(20).i32(2).i32(1)
    .i32(8).i64(9000000000n).i32(8).i64(-2n).done();
  assert.deepEqual(decodeInt8Array(wire), [9000000000n, -2n]);
});

test('text[] decodes including NULL elements', () => {
  const a = new TextEncoder().encode('a');
  const ccc = new TextEncoder().encode('ccc');
  const wire = new Bytes().i32(1).i32(1).i32(25).i32(3).i32(1)
    .i32(1).raw(a).i32(-1).i32(3).raw(ccc).done();
  assert.deepEqual(decodeTextArray(wire), ['a', null, 'ccc']);
});
