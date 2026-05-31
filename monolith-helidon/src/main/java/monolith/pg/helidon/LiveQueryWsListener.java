/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.helidon;

import io.helidon.common.buffers.BufferData;
import io.helidon.websocket.WsListener;
import io.helidon.websocket.WsSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import monolith.pg.reactive.ReactiveHub;

/**
 * A Helidon {@link WsListener} that exposes Monolith live queries over a WebSocket. Drop it onto any
 * endpoint of your own {@code WsRouting}, the adapter never registers routes for you.
 *
 * <p>Protocol: a client opens the socket and sends a single text frame {@code "<QueryName>:<param>"}
 * (e.g. {@code "OrderSummaryByRegion:EU"}). The session subscribes to that query+param on the
 * {@link ReactiveHub}; the current result is pushed immediately, and the registered
 * {@link QueryRunner} is re-run and pushed on every change the generated invalidation rule maps to
 * that param. Each push is the full current result (a live relational query is a materialized view,
 * not an incremental stream). Closing the socket unsubscribes.
 *
 * <p>One instance is shared across all sessions on the endpoint; per-session state (the
 * subscription) is tracked internally and cleaned up on close/error. Build with {@link #builder}:
 *
 * <pre>{@code
 * var live = LiveQueryWsListener.builder(hub)
 *     .query("OrderSummaryByRegion", region -> WsResults.frame(orderSummaryRows(pool, region)))
 *     .build();
 *
 * WebServer.builder()
 *     .routing(r -> r.get("/health", (req, res) -> res.send("ok")))   // your own HTTP routes
 *     .addRouting(WsRouting.builder().endpoint("/live-query", live))   // your own WS endpoint
 *     .build()
 *     .start();
 * }</pre>
 */
public final class LiveQueryWsListener implements WsListener {

  /** Produces the full current result bytes for a query, given its bound param value. */
  @FunctionalInterface
  public interface QueryRunner {
    byte[] run(String param);
  }

  private final ReactiveHub hub;
  private final Map<String, QueryRunner> runners;
  private final Map<WsSession, ReactiveHub.Subscription> subs = new ConcurrentHashMap<>();

  private LiveQueryWsListener(ReactiveHub hub, Map<String, QueryRunner> runners) {
    this.hub = hub;
    this.runners = runners;
  }

  public static Builder builder(ReactiveHub hub) {
    return new Builder(hub);
  }

  @Override
  public void onMessage(WsSession session, String text, boolean last) {
    if (subs.containsKey(session)) return; // one subscription per session; ignore extra frames
    int colon = text.indexOf(':');
    if (colon < 0) {
      session.close(1008, "expected '<QueryName>:<param>'");
      return;
    }
    String query = text.substring(0, colon);
    String param = text.substring(colon + 1).trim();
    QueryRunner runner = runners.get(query);
    if (runner == null) {
      session.close(1008, "unknown query: " + query);
      return;
    }
    ReactiveHub.Subscription sub = hub.subscribe(query, param, () -> push(session, runner, param));
    subs.put(session, sub);
    push(session, runner, param); // deliver the current result immediately
  }

  @Override
  public void onClose(WsSession session, int status, String reason) {
    ReactiveHub.Subscription sub = subs.remove(session);
    if (sub != null) hub.unsubscribe(sub);
  }

  @Override
  public void onError(WsSession session, Throwable t) {
    onClose(session, 0, "");
  }

  private void push(WsSession session, QueryRunner runner, String param) {
    synchronized (session) {
      try {
        session.send(BufferData.create(runner.run(param)), true);
      } catch (RuntimeException e) {
        // session closing/closed between invalidation and send, the close path cleans up
      }
    }
  }

  /** Collects the {@code query name -> runner} bindings for a {@link LiveQueryWsListener}. */
  public static final class Builder {
    private final ReactiveHub hub;
    private final Map<String, QueryRunner> runners = new HashMap<>();

    private Builder(ReactiveHub hub) {
      this.hub = hub;
    }

    /** Bind a query name (the {@code @PgQuery} record's simple name) to its result producer. */
    public Builder query(String name, QueryRunner runner) {
      runners.put(name, runner);
      return this;
    }

    public LiveQueryWsListener build() {
      return new LiveQueryWsListener(hub, Map.copyOf(runners));
    }
  }
}
