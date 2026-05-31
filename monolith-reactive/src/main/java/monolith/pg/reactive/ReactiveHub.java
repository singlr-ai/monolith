/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import monolith.pg.runtime.PgInvalidationRule;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.WalChange;

/**
 * Routes change-feed events to live-query subscribers. Subscribers register against a query and a
 * param value (e.g. {@code "OrderSummaryByRegion"}, {@code "EU"}); when a {@link WalChange} arrives,
 * each query's generated {@link PgInvalidationRule} maps it to the affected param values, and only
 * the matching subscribers' listeners fire. For a joined query the rule resolves the change's
 * join-key up to the param via a back-reference lookup, so invalidation is value-precise and
 * join-aware over real relational schemas.
 *
 * <p>Framework-agnostic: a listener is any callback (re-run the query, push a WebSocket frame, etc.).
 * Concurrent by construction, subscribe/unsubscribe and {@link #apply} can run on different threads.
 */
public final class ReactiveHub {

  /** Called when a subscriber's query result may have changed. */
  @FunctionalInterface
  public interface Listener {
    void onInvalidate();
  }

  /** A live subscription; pass it to {@link #unsubscribe} to remove it. */
  public static final class Subscription {
    final String query;
    final String param;
    final Listener listener;

    Subscription(String query, String param, Listener listener) {
      this.query = query;
      this.param = param;
      this.listener = listener;
    }
  }

  private final PgPool pool;
  private final List<PgInvalidationRule> rules;
  private final Map<String, Map<String, Set<Subscription>>> buckets = new ConcurrentHashMap<>();

  public ReactiveHub(PgPool pool, List<PgInvalidationRule> rules) {
    this.pool = pool;
    this.rules = rules;
  }

  public Subscription subscribe(String query, String param, Listener listener) {
    Subscription s = new Subscription(query, param, listener);
    buckets.computeIfAbsent(query, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(param, k -> ConcurrentHashMap.newKeySet())
        .add(s);
    return s;
  }

  public void unsubscribe(Subscription s) {
    Map<String, Set<Subscription>> byParam = buckets.get(s.query);
    if (byParam != null) {
      Set<Subscription> subs = byParam.get(s.param);
      if (subs != null) subs.remove(s);
    }
  }

  /** Apply one change-feed event: wake exactly the subscribers each rule marks stale. */
  public void apply(WalChange change) {
    for (PgInvalidationRule rule : rules) {
      for (String param : rule.affectedParams(change.table(), change::valuesOf, pool)) {
        Map<String, Set<Subscription>> byParam = buckets.get(rule.query());
        if (byParam == null) continue;
        Set<Subscription> subs = byParam.get(param);
        if (subs == null) continue;
        for (Subscription s : subs) {
          try {
            s.listener.onInvalidate();
          } catch (RuntimeException ignore) {
            // a dead subscriber; the transport's close path removes it
          }
        }
      }
    }
  }
}
