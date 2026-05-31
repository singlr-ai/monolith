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
 * Binds a SQL query to its result shape in one declaration. Like {@code @PgProjection}
 * it describes a read-only result (the record's components are the SELECT columns,
 * in order), but it also carries the query text, so the processor generates both
 * the reader AND a {@code <Name>Query} class with the SQL and a typed
 * {@code run(Arena, conn, Object... params)} that binds parameters, executes in
 * binary, bridges each row, and returns {@code List<<Name>Reader>}.
 *
 * <p>This collapses the SQL, the result shape, and the execution into a single
 * source, so the projection and its {@code SELECT} can't drift apart. Parameters
 * are Postgres-positional ({@code $1, $2, ...}) and passed in order to {@code run}.
 *
 * <p>The ordinal contract (component order/types ↔ SELECT column list) is still
 * the developer's to get right; a future increment can validate it against a dev
 * database at build time. Mark a component {@code @PgNull} where the query can
 * produce {@code NULL}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PgQuery {

  /** The SQL, with positional parameters {@code $1, $2, ...}. */
  String value();
}
