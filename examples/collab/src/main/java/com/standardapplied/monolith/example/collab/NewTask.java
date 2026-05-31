/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.monolith.example.collab;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A validated request to add a task to a board: the inbound HTTP params after they have been
 * checked, so everything past this point can trust the values. Construction fails fast on a missing
 * board, a blank title, or a null assignee optional.
 *
 * @param boardId the board the task belongs to
 * @param title the task title; non-blank
 * @param assignee the assignee, if any
 */
public record NewTask(UUID boardId, String title, Optional<String> assignee) {

  public NewTask {
    Objects.requireNonNull(boardId, "boardId must not be null");
    Objects.requireNonNull(title, "title must not be null");
    if (title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    Objects.requireNonNull(assignee, "assignee optional must not be null");
  }
}
