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
 * Marks a {@code @PgType} for an immutable audit trail. The generated DDL adds an append-only
 * {@code <name>_audit} table and a trigger that records who ({@code app.actor}, set via
 * {@code PgSession.actor(...)}), what ({@code INSERT}/{@code UPDATE}/{@code DELETE}), when, and the
 * old and new row as {@code jsonb} for every write. A second trigger rejects any {@code UPDATE} or
 * {@code DELETE} on the audit table, so the trail cannot be altered after the fact.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Audited {}
