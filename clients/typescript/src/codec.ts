/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Decoders from Postgres binary wire values to JavaScript, mirroring the Java `PgCodec`. The codegen
 * emits TypeScript readers that call these for `jsonb`, `numeric`, and array columns, so a client
 * gets a parsed value rather than raw bytes. (`bytea` and `@Encrypted` columns stay `Uint8Array`,
 * since those genuinely are bytes.)
 */

const utf8 = new TextDecoder();

function view(bytes: Uint8Array): DataView {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

/** `jsonb`: a `0x01` version byte followed by UTF-8 JSON. Returns the parsed value. */
export function decodeJsonb(bytes: Uint8Array): unknown {
  if (bytes.length < 1 || bytes[0] !== 1) {
    throw new Error(`unexpected jsonb version byte: ${bytes.length === 0 ? 'none' : bytes[0]}`);
  }
  return JSON.parse(utf8.decode(bytes.subarray(1)));
}

/**
 * `numeric`: returned as a decimal string to preserve arbitrary precision (a JS number would lose
 * it). NaN and infinities come back as `'NaN'`, `'Infinity'`, `'-Infinity'`.
 */
export function decodeNumeric(bytes: Uint8Array): string {
  const v = view(bytes);
  const ndigits = v.getInt16(0, false);
  const weight = v.getInt16(2, false); // signed
  const sign = v.getUint16(4, false);
  const dscale = v.getUint16(6, false);
  if (sign === 0xc000) return 'NaN';
  if (sign === 0xd000) return 'Infinity';
  if (sign === 0xf000) return '-Infinity';

  const word = (place: number): string => {
    const i = weight - place; // word index holding base-10000 place `place`
    return (i >= 0 && i < ndigits ? v.getUint16(8 + i * 2, false) : 0).toString().padStart(4, '0');
  };

  let intDigits = '';
  for (let place = weight; place >= 0; place--) intDigits += word(place);
  intDigits = intDigits.replace(/^0+/, '') || '0';

  let fracDigits = '';
  for (let place = -1; fracDigits.length < dscale; place--) fracDigits += word(place);
  fracDigits = fracDigits.slice(0, dscale);

  const negative = sign === 0x4000 ? '-' : '';
  return dscale > 0 ? `${negative}${intDigits}.${fracDigits}` : `${negative}${intDigits}`;
}

/** Reads a 1-D array header and returns the element count (0 for an empty array). */
function arrayLength(v: DataView): number {
  return v.getInt32(0, false) === 0 ? 0 : v.getInt32(12, false);
}

/** `int4[]`. */
export function decodeInt4Array(bytes: Uint8Array): number[] {
  const v = view(bytes);
  const out: number[] = [];
  let off = 20;
  for (let i = arrayLength(v); i > 0; i--) {
    off += 4; // element length (always 4)
    out.push(v.getInt32(off, false));
    off += 4;
  }
  return out;
}

/** `int8[]`, returned as `bigint[]` to keep full 64-bit precision. */
export function decodeInt8Array(bytes: Uint8Array): bigint[] {
  const v = view(bytes);
  const out: bigint[] = [];
  let off = 20;
  for (let i = arrayLength(v); i > 0; i--) {
    off += 4; // element length (always 8)
    out.push(v.getBigInt64(off, false));
    off += 8;
  }
  return out;
}

/** `text[]`, supporting NULL elements (length -1). */
export function decodeTextArray(bytes: Uint8Array): (string | null)[] {
  const v = view(bytes);
  const out: (string | null)[] = [];
  let off = 20;
  for (let i = arrayLength(v); i > 0; i--) {
    const len = v.getInt32(off, false);
    off += 4;
    if (len === -1) {
      out.push(null);
    } else {
      out.push(utf8.decode(bytes.subarray(off, off + len)));
      off += len;
    }
  }
  return out;
}
