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
   * Relations that grant <b>read</b> ({@code SELECT}), for example {@code {"viewer", "care_team"}}. When
   * {@link #read} or {@link #write} is non-empty the codegen emits command-specific policies
   * ({@code FOR SELECT} vs {@code FOR INSERT/UPDATE/DELETE}) so a grant only authorizes the action its
   * relation is mapped to — a {@code viewer} can read but not write. Each command's policy matches an
   * {@code allow} grant whose {@code relation} is in the corresponding set (and no such {@code deny}).
   *
   * <p>An action whose relation set is empty is <b>fail-closed</b>: with {@code read} empty no grant can
   * read, with {@code write} empty no grant can write. To use the simpler relation-agnostic model — a
   * single {@code FOR ALL} policy where any {@code allow} grant, regardless of relation, authorizes every
   * command — leave both empty and set {@link #relationAgnostic()}. A bare {@code @AccessControlled} (no
   * relations and no opt-in) is <b>rejected at compile time</b>, so a read relation never silently
   * authorizes a write.
   */
  String[] read() default {};

  /** Relations that grant <b>write</b> ({@code INSERT}, {@code UPDATE}, {@code DELETE}). See {@link #read}. */
  String[] write() default {};

  /**
   * Opt into the relation-agnostic model: a single {@code FOR ALL} policy where any matching {@code allow}
   * grant authorizes every command, regardless of relation. Only consulted when {@link #read} and
   * {@link #write} are both empty. This must be set <em>explicitly</em> — it is the coarse "a grant is full
   * access" mode, appropriate for instance ACLs without a read/write distinction, and naming it forces
   * that to be a deliberate choice rather than an accidental default.
   */
  boolean relationAgnostic() default false;

  /**
   * An optional attribute condition AND-ed into the policy, a SQL boolean over this type's own columns
   * (by their generated snake_case names) and {@code current_setting(...)}, for example
   * {@code "status <> 'archived'"} or {@code "region = current_setting('app.region', true)"}. It is
   * trusted developer SQL, embedded into the generated policy; the codegen rejects statement-breaking
   * input (a {@code ;}, a SQL comment, or unbalanced parentheses) but does not otherwise parse it. A
   * condition over this table's columns is the common case; a subquery to another table is also valid
   * (the connection role then needs {@code SELECT} on it), though for hot paths prefer materializing
   * the relationship into grants. See {@code docs/ACCESS.md}.
   */
  String where() default "";
}
