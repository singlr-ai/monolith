/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code record} as a <b>read-only query result shape</b>, a projection
 * that exists in no single table (a join, an aggregate, a hand-picked column
 * list). The processor generates a reader + layout metadata for it exactly as for
 * a {@code @PgType}, but emits <b>no DDL and no builder</b>: there is nothing to
 * create or write, only bytes to read.
 *
 * <p>The component order and types must match the {@code SELECT} column list,
 * in order, the same ordinal contract a {@code @PgType} has with its table,
 * extended to a query. Mark a component {@code @PgNull} when the query can
 * produce {@code NULL} for it (e.g. an outer join or an ungrouped aggregate).
 *
 * <p>This is the mechanism that lets the binary-passthrough model carry an
 * arbitrary join/aggregate result from Postgres to the client without ever
 * decoding to an intermediate object graph.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PgProjection {

  /** Optional logical name (for {@code schema.lock} / diagnostics). Defaults to the record name. */
  String value() default "";
}
