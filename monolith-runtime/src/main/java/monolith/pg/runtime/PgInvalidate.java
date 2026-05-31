/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/** Runtime support for generated {@link PgInvalidationRule}s: resolve join-key values to param values. */
public final class PgInvalidate {

  /**
   * Run the generated back-reference lookup for each key value and collect the param values it
   * resolves to (e.g. each changed {@code order_id} → its order's {@code region}).
   */
  public static Set<String> resolve(PgPool pool, Set<String> keys, Function<String, String> sqlFor) {
    if (keys.isEmpty()) return Set.of();
    Set<String> out = new LinkedHashSet<>();
    MemorySegment conn = pool.lease().getOrThrow();
    try (Arena a = Arena.ofConfined()) {
      for (String k : keys) out.addAll(Pg.textColumn(a, conn, sqlFor.apply(k)).getOrThrow());
    } finally {
      pool.release(conn);
    }
    return out;
  }

  private PgInvalidate() {}
}
