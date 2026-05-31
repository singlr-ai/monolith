/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.monolith.example.collab;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;

/**
 * The data layer for the task board: writes through the binary {@code PQexecParams} path (no JDBC)
 * and reads the live-query result as raw row bytes. It stays free of any web type, fallible writes
 * return a {@link Result} the transport layer maps to a status, and reads return the row bytes the
 * transport frames for the wire.
 */
public final class TaskRepository {

  private static final String INSERT_TASK =
      "INSERT INTO tasks (id, board_id, title, assignee) VALUES ($1, $2, $3, $4)";
  private static final String UPDATE_DONE = "UPDATE tasks SET done = $1 WHERE id = $2";

  private final PgPool pool;

  public TaskRepository(PgPool pool) {
    this.pool = pool;
  }

  /**
   * Adds a task to a board and returns its new id. The chain reads top to bottom: lease a
   * connection, run the insert, map the result to the id, any failure (no connection, a constraint
   * violation) short-circuits to a {@link Result.Failure} carrying the reason.
   */
  public Result<UUID> createTask(NewTask task) {
    var id = UUID.randomUUID();
    var assignee = task.assignee().orElse(null);
    return withConnection((arena, conn) -> {
      var bound = PgParam.bind(arena, id, task.boardId(), task.title(), assignee);
      return Pg.execParamsBinary(arena, conn, INSERT_TASK,
          bound.values(), bound.lengths(), bound.formats()).map(res -> {
        Pg.clear(res);
        return id;
      });
    });
  }

  /** Sets a task's done flag, returning the task id on success or a failure with the reason. */
  public Result<UUID> setDone(UUID taskId, boolean done) {
    return withConnection((arena, conn) -> {
      var bound = PgParam.bind(arena, done, taskId);
      return Pg.execParamsBinary(arena, conn, UPDATE_DONE,
          bound.values(), bound.lengths(), bound.formats()).map(res -> {
        Pg.clear(res);
        return taskId;
      });
    });
  }

  /**
   * Runs the {@link BoardTasks} live query for one board and returns each row in Monolith's binary
   * layout, the bytes the wire envelope and the TypeScript reader consume. Reads are on the live
   * push path, where a failure is exceptional, so this unwraps rather than returning a {@link Result}.
   */
  public List<byte[]> taskRows(UUID boardId) {
    var conn = pool.lease().getOrThrow();
    try (var arena = Arena.ofConfined()) {
      var rows = new ArrayList<byte[]>();
      for (var row : BoardTasksQuery.run(arena, conn, boardId)) {
        rows.add(row.seg().toArray(ValueLayout.JAVA_BYTE));
      }
      return rows;
    } finally {
      pool.release(conn);
    }
  }

  /** Leases a connection, scopes a confined arena, runs {@code body}, then always releases. */
  private <T> Result<T> withConnection(BiFunction<Arena, MemorySegment, Result<T>> body) {
    return switch (pool.lease()) {
      case Result.Success<MemorySegment>(var conn) -> {
        try (var arena = Arena.ofConfined()) {
          yield body.apply(arena, conn);
        } finally {
          pool.release(conn);
        }
      }
      case Result.Failure<MemorySegment>(var error, var cause) -> Result.failure(error, cause);
    };
  }
}
