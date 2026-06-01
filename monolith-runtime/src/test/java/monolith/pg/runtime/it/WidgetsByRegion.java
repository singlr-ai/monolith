/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import java.util.UUID;
import monolith.pg.PgQuery;

/**
 * Integration-test query whose param ({@code region}) lives on a joined parent table. A change to a
 * {@code widgets} row makes the generated invalidation rule walk {@code box_id -> boxes.region} via
 * {@code PgInvalidate.resolve}, which is the join back-reference exercised by the test.
 */
@PgQuery("""
    SELECT w.id, w.name
      FROM widgets w
      JOIN boxes b ON b.id = w.box_id
     WHERE b.region = $1
     ORDER BY w.name""")
public record WidgetsByRegion(UUID id, String name) {}
