/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Sets the per-transaction session context that generated policies and triggers read: the tenant for
 * {@code @Tenant} row-level security, the actor for {@code @Audited} and {@code @AccessControlled}, and
 * any other attribute a policy or {@code @AccessControlled(where = ...)} needs (a membership tier, a
 * region, a clearance level). Hydrate those once per request from wherever they live, a users table, a
 * JWT claim, a cache, an external service, and a policy reads them with {@code current_setting}, so a
 * per-row check needs no join.
 *
 * <p>All use {@code set_config(..., is_local => true)}, so a value is scoped to the current transaction
 * and never leaks to a pooled connection's next user. Call them right after {@code BEGIN}, before the
 * statements that should see the context. Values are bound as parameters, so they are injection-safe.
 */
public final class PgSession {

  /** Confine the current transaction to {@code tenant} for {@code @Tenant} RLS policies. */
  public static void tenant(Arena arena, MemorySegment conn, String tenant) {
    set(arena, conn, "app.tenant", tenant);
  }

  /** Set the acting user for {@code @Audited} attribution and {@code @AccessControlled} policies. */
  public static void actor(Arena arena, MemorySegment conn, String actor) {
    set(arena, conn, "app.actor", actor);
  }

  /**
   * Set an arbitrary namespaced session value for this transaction, for a policy or
   * {@code @AccessControlled(where = ...)} to read via {@code current_setting(key, true)}. The key must
   * be a custom, dotted setting such as {@code "app.tier"} (Postgres requires the namespace).
   */
  public static void set(Arena arena, MemorySegment conn, String key, String value) {
    PgParam.Bound p = PgParam.bind(arena, key, value);
    Pg.clear(Pg.execParamsBinary(arena, conn, "SELECT set_config($1, $2, true)",
        p.values(), p.lengths(), p.formats()).getOrThrow());
  }

  private PgSession() {}
}
