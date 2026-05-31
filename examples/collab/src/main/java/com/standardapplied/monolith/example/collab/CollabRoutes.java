/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.monolith.example.collab;

import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRouting;
import java.util.UUID;
import java.util.function.Consumer;
import monolith.pg.runtime.Result;

/**
 * The example's own HTTP write surface, expressed as a {@link Consumer} of a Helidon routing
 * builder so the application installs it onto a server it owns, the libraries never register a
 * route. Each handler validates input (a bad request fails before touching the database), calls the
 * {@link TaskRepository}, and maps the returned {@link Result} to a status with an exhaustive
 * {@code switch}.
 */
public final class CollabRoutes implements Consumer<HttpRouting.Builder> {

  private final TaskRepository tasks;

  public CollabRoutes(TaskRepository tasks) {
    this.tasks = tasks;
  }

  @Override
  public void accept(HttpRouting.Builder routing) {
    routing
        .post("/boards/{boardId}/tasks", (req, res) -> {
          NewTask task;
          try {
            task = new NewTask(
                UUID.fromString(req.path().pathParameters().get("boardId")),
                req.query().first("title").orElse(""),
                req.query().first("assignee").asOptional());
          } catch (IllegalArgumentException e) {
            res.status(Status.BAD_REQUEST_400).send(e.getMessage());
            return;
          }
          switch (tasks.createTask(task)) {
            case Result.Success<UUID>(var id) -> res.status(Status.CREATED_201).send(id.toString());
            case Result.Failure<UUID> f -> res.status(Status.INTERNAL_SERVER_ERROR_500).send(f.error());
          }
        })
        .put("/tasks/{taskId}/done", (req, res) -> {
          UUID taskId;
          try {
            taskId = UUID.fromString(req.path().pathParameters().get("taskId"));
          } catch (IllegalArgumentException e) {
            res.status(Status.BAD_REQUEST_400).send("invalid task id");
            return;
          }
          var done = req.query().first("value").map(Boolean::parseBoolean).orElse(Boolean.TRUE);
          switch (tasks.setDone(taskId, done)) {
            case Result.Success<UUID> ok -> res.status(Status.NO_CONTENT_204).send();
            case Result.Failure<UUID> f -> res.status(Status.INTERNAL_SERVER_ERROR_500).send(f.error());
          }
        })
        .get("/health", (req, res) -> res.send("ok"));
  }
}
