/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import java.util.UUID;
import monolith.pg.PgNull;
import monolith.pg.PgQuery;

/**
 * Integration-test query: widgets in one box, including a nullable column so the generated reader
 * exercises {@code PgBridge}'s null-bitmap and variable-length paths. The param sits directly on the
 * base table, so the generated invalidation rule reads {@code box_id} without a join lookup.
 */
@PgQuery("SELECT id, name, qty, note FROM widgets WHERE box_id = $1 ORDER BY name")
public record WidgetsByBox(UUID id, String name, int qty, @PgNull String note) {}
