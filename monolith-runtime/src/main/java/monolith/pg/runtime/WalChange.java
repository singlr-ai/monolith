/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row change decoded from the WAL (via {@code test_decoding}). Keeps the raw line and
 * extracts column values on demand; {@link #valuesOf} returns <i>every</i> value of a column
 * in the line, so under {@code REPLICA IDENTITY FULL} an update yields both old and new,
 * which a generated {@link PgInvalidationRule} uses for value-precise invalidation.
 */
public record WalChange(String table, String raw) {

  public Set<String> valuesOf(String col) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = Pattern.compile("\\b" + Pattern.quote(col) + "\\[[^\\]]*\\]:(?:'([^']*)'|(\\S+))").matcher(raw);
    while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
    return out;
  }
}
