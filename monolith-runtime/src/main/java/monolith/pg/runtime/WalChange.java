/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.util.Map;
import java.util.Set;

/**
 * One decoded row change from the WAL: the table it touched and, per column, the value(s) the
 * change carried. An update under {@code REPLICA IDENTITY FULL} carries both the old and the new
 * value of a column, so {@link #valuesOf} returns a set. A generated {@link PgInvalidationRule}
 * reads the columns it needs for value-precise invalidation.
 */
public record WalChange(String table, Map<String, Set<String>> columns) {

  /** All values this change carried for {@code column} (empty if the column was absent or NULL). */
  public Set<String> valuesOf(String column) {
    return columns.getOrDefault(column, Set.of());
  }
}
