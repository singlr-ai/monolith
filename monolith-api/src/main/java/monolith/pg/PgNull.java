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
 * Marks a {@code @PgType} record component as nullable (Postgres {@code NULL}).
 *
 * <p>Default contract: primitives ({@code int}, {@code long}, ...) and
 * un-annotated reference/boxed components are {@code NOT NULL}. Adding
 * {@code @PgNull} to a reference or boxed component (e.g. {@code @PgNull Integer},
 * {@code @PgNull Instant}) makes it nullable. It is an error on a primitive,
 * which can never be null.
 *
 * <p>Layout impact: a record with at least one {@code @PgNull} component gets a
 * leading null bitmap of {@code ceil(N/8)} bytes (bit <i>i</i> set = component
 * <i>i</i> is NULL), mirroring Postgres's own {@code HEAP_HASNULL} tuple header.
 * A record with no nullable component carries no bitmap and is byte-identical to
 * the pre-nullability layout.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface PgNull {}
