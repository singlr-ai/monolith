/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Sets the per-transaction session context that the generated compliance features read: the tenant
 * for {@code @Tenant} row-level-security policies and the actor for {@code @Audited} triggers. Both
 * use {@code set_config(..., is_local => true)}, so the value is scoped to the current transaction
 * and never leaks to a pooled connection's next user. Call them right after {@code BEGIN}, before the
 * statements that should see the tenant or be attributed to the actor. Values are bound as
 * parameters, so they are injection-safe.
 */
public final class PgSession {

  /** Confine the current transaction to {@code tenant} for {@code @Tenant} RLS policies. */
  public static void tenant(Arena arena, MemorySegment conn, String tenant) {
    setLocal(arena, conn, "app.tenant", tenant);
  }

  /** Attribute writes in the current transaction to {@code actor} in the audit trail. */
  public static void actor(Arena arena, MemorySegment conn, String actor) {
    setLocal(arena, conn, "app.actor", actor);
  }

  private static void setLocal(Arena arena, MemorySegment conn, String key, String value) {
    PgParam.Bound p = PgParam.bind(arena, key, value);
    Pg.clear(Pg.execParamsBinary(arena, conn, "SELECT set_config($1, $2, true)",
        p.values(), p.lengths(), p.formats()).getOrThrow());
  }

  private PgSession() {}
}
