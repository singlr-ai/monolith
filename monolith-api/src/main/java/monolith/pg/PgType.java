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
 * Marks a {@code record} as the single source of truth for a Postgres composite
 * type. The annotation processor reads the record's components, in declaration
 * order, and generates:
 *
 * <ul>
 *   <li>{@code <Name>Reader.java}, a reader record over a {@link java.lang.foreign.MemorySegment}
 *       in Postgres binary layout (big-endian).</li>
 *   <li>{@code <Name>Builder.java}, encodes the fields into a fresh segment.</li>
 *   <li>{@code <name>.sql}, composite-type + table DDL fragment.</li>
 *   <li>{@code <name>.ts}, a TypeScript reader over a {@code DataView}
 *       with identical offsets.</li>
 *   <li>{@code schema.lock}, a committed ordinal snapshot; a CI check
 *       fails the build if declaration order drifts without an intentional bump.</li>
 * </ul>
 *
 * <p>Ordinals are the record-component declaration order. There is no second
 * source of truth: reorder a component and you reorder the wire layout, which
 * the {@code schema.lock} CI check catches.
 *
 * <p>Supported component types: {@code UUID}, {@code String},
 * {@code int}/{@code Integer}, {@code long}/{@code Long}, {@code boolean}/{@code Boolean},
 * {@code double}/{@code Double}, {@code byte[]}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface PgType {

  /**
   * Postgres composite-type / table name. Defaults to the snake_case form of the
   * record's simple name (e.g. {@code Document} -> {@code document}).
   */
  String value() default "";
}
