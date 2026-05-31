/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg;

/**
 * Marks a {@code @PgType} component as Postgres {@code jsonb}. Wraps the JSON
 * text so the codegen can distinguish it from a plain {@code String} (which maps
 * to {@code text}). The wire form is the jsonb binary: a {@code 0x01} version
 * byte followed by the UTF-8 JSON text.
 *
 * <p>Note: Postgres normalizes jsonb on storage (object keys reordered, whitespace
 * collapsed, duplicate keys dropped), so the text read back may differ from the
 * text written while being semantically identical.
 */
public record Json(String value) {}
