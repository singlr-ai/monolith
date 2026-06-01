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
 * Marks the tenant-isolation column of a {@code @PgType}. The generated DDL enables and
 * {@code FORCE}s row-level security with a policy that confines every row to
 * {@code current_setting('app.tenant')}, which the application sets per transaction via
 * {@code PgSession.tenant(...)}. With the setting absent the policy is fail-closed (no rows), and
 * because RLS is forced it applies to the table owner too, so isolation cannot be bypassed by a
 * forgotten {@code WHERE} clause. Connect as a non-superuser role for it to take effect.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.RECORD_COMPONENT)
public @interface Tenant {}
