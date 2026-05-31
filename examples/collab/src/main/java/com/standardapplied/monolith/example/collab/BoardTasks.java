/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.monolith.example.collab;

import java.util.UUID;
import monolith.pg.PgQuery;

/**
 * The live relational query behind the board view: every task on one board, ordered. The processor
 * generates {@code BoardTasksReader}, {@code BoardTasksQuery.run(arena, conn, params)}, and
 * {@code BoardTasksInvalidation} (param = {@code board_id}); a write to any task on a board resolves
 * to that board's param, so only that board's subscribers are woken with the fresh result.
 */
@PgQuery(
    """
    SELECT t.id,
           t.title,
           t.done,
           coalesce(t.assignee, '') AS assignee
      FROM tasks t
     WHERE t.board_id = $1
     ORDER BY t.id""")
public record BoardTasks(UUID id, String title, boolean done, String assignee) {}
