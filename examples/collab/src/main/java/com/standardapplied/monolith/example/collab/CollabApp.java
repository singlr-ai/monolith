/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.monolith.example.collab;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.websocket.WsRouting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import monolith.pg.helidon.LiveQueryWsListener;
import monolith.pg.helidon.WsResults;
import monolith.pg.reactive.Invalidator;
import monolith.pg.reactive.ReactiveHub;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgInvalidationRule;
import monolith.pg.runtime.PgPool;

/**
 * Composition root for the collab example: it wires the Monolith libraries together and starts a
 * server, doing nothing else itself. The pool, schema, reactive hub, WAL-tailing invalidator, HTTP
 * routes ({@link CollabRoutes}), and the live-query WebSocket endpoint (the {@code monolith-helidon}
 * adapter) are assembled here so each collaborator keeps a single responsibility.
 */
public final class CollabApp {

  private static final String SCHEMA_RESOURCE = "/collab-schema.sql";
  private static final String LIVE_QUERY = "BoardTasks";

  public static void main(String[] args) {
    var config = CollabConfig.newBuilder()
        .withPort(args.length > 0 ? Integer.parseInt(args[0]) : CollabConfig.defaults().port())
        .build();
    start(config);
  }

  /** Wires the application from {@code config} and starts it; returns the running server. */
  public static WebServer start(CollabConfig config) {
    var pool = new PgPool(config.conninfo(), config.poolSize());
    applySchema(pool);

    var repository = new TaskRepository(pool);
    var hub = new ReactiveHub(pool, List.<PgInvalidationRule>of(new BoardTasksInvalidation()));
    var invalidator = new Invalidator(config.conninfo(), hub, config.slot());

    var liveQueries = LiveQueryWsListener.builder(hub)
        .query(LIVE_QUERY, boardId -> WsResults.frame(repository.taskRows(UUID.fromString(boardId))))
        .build();

    var server = WebServer.builder()
        .host(config.host())
        .port(config.port())
        .routing(new CollabRoutes(repository))
        .addRouting(WsRouting.builder().endpoint("/live-query", liveQueries))
        .build()
        .start();

    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
      server.stop();
      invalidator.close();
      pool.close();
    }));

    System.out.printf("Collab example on http://%s:%d  (POST /boards/{id}/tasks, "
        + "PUT /tasks/{id}/done, ws /live-query)%n", config.host(), server.port());
    return server;
  }

  private static void applySchema(PgPool pool) {
    var conn = pool.lease().getOrThrow();
    try (var arena = Arena.ofConfined()) {
      Pg.exec(arena, conn, schemaSql()).getOrThrow();
    } finally {
      pool.release(conn);
    }
  }

  private static String schemaSql() {
    try (var in = CollabApp.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing classpath resource " + SCHEMA_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + SCHEMA_RESOURCE, e);
    }
  }

  private CollabApp() {}
}
