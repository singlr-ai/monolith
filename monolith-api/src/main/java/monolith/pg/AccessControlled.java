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
 * Marks a {@code @PgType} as access-controlled by grants. The generated DDL enables and {@code FORCE}s
 * row-level security with a policy that allows a row only when an {@code allow} grant matches the
 * current actor (itself or a role it holds) for this resource, and no {@code deny} grant does, so a
 * {@code deny} always wins. The actor is set per transaction via {@code PgSession.actor(...)}; with it
 * absent the policy is fail-closed (no rows). The grant and role tables are created by
 * {@code Grants.install(...)}, which must run before this type's DDL is applied, and the application
 * writes grants through the {@code Grants} API.
 *
 * <p>One mechanism expresses RBAC (grant a role with the {@code Grants.ALL} resource id), instance
 * ACLs (grant an actor a specific row), ownership, and consent (a {@code deny}). It composes with
 * {@code @Tenant}: when both are present the tenant policy is emitted as {@code RESTRICTIVE}, so a row
 * must both belong to the tenant and be granted. See {@code docs/design/ACCESS.md}.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AccessControlled {

  /** The resource name used in grants. Defaults to the generated table name. */
  String resource() default "";

  /** The row-identifying component matched against a grant's resource id. Defaults to {@code "id"}. */
  String id() default "id";

  /**
   * An optional attribute condition AND-ed into the policy, a SQL boolean over this type's own columns
   * (by their generated snake_case names) and {@code current_setting(...)}, for example
   * {@code "status <> 'archived'"} or {@code "region = current_setting('app.region', true)"}. It is
   * trusted developer SQL, embedded into the generated policy; the codegen rejects statement-breaking
   * input (a {@code ;}, a SQL comment, or unbalanced parentheses) but does not otherwise parse it, so
   * keep it to a predicate over this table's columns, no subqueries or cross-table reads.
   */
  String where() default "";
}
