/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import monolith.pg.runtime.PgInvalidationRule;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.WalChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReactiveHub")
class ReactiveHubTest {

  /** A rule that maps a change on {@code table} to a fixed param set, ignoring the (unused) pool. */
  private static PgInvalidationRule rule(String query, String table, Set<String> params) {
    return new PgInvalidationRule() {
      @Override public String query() { return query; }
      @Override public String[] tables() { return new String[] {table}; }
      @Override public Set<String> affectedParams(
          String changedTable, Function<String, Set<String>> valuesOf, PgPool pool) {
        return changedTable.equals(table) ? params : Set.of();
      }
    };
  }

  private static WalChange change(String table) {
    return new WalChange(table, java.util.Map.of());
  }

  @Test
  void wakesTheMatchingParamAndLeavesOthersAlone() {
    var hub = new ReactiveHub(null, List.of(rule("Q", "t", Set.of("A"))));
    var a = new AtomicInteger();
    var b = new AtomicInteger();
    hub.subscribe("Q", "A", a::incrementAndGet);
    hub.subscribe("Q", "B", b::incrementAndGet);

    hub.apply(change("t"));

    assertEquals(1, a.get());
    assertEquals(0, b.get());
  }

  @Test
  void anAffectedParamWithNoSubscriberIsSkipped() {
    var hub = new ReactiveHub(null, List.of(rule("Q", "t", Set.of("A", "ghost"))));
    var a = new AtomicInteger();
    hub.subscribe("Q", "A", a::incrementAndGet);

    assertDoesNotThrow(() -> hub.apply(change("t"))); // "ghost" has no bucket -> skipped
    assertEquals(1, a.get());
  }

  @Test
  void aChangeForAQueryNobodySubscribedToIsIgnored() {
    var hub = new ReactiveHub(null, List.of(rule("Q", "t", Set.of("A"))));
    var other = new AtomicInteger();
    hub.subscribe("Other", "A", other::incrementAndGet); // bucket exists, but not for "Q"

    hub.apply(change("t"));
    assertEquals(0, other.get());
  }

  @Test
  void aChangeToAnUnrelatedTableWakesNobody() {
    var hub = new ReactiveHub(null, List.of(rule("Q", "t", Set.of("A"))));
    var a = new AtomicInteger();
    hub.subscribe("Q", "A", a::incrementAndGet);

    hub.apply(change("unrelated")); // rule returns empty set
    assertEquals(0, a.get());
  }

  @Test
  void aThrowingSubscriberIsIsolatedFromTheOthers() {
    var hub = new ReactiveHub(null, List.of(rule("Q", "t", Set.of("A"))));
    var healthy = new AtomicInteger();
    hub.subscribe("Q", "A", () -> { throw new RuntimeException("boom"); });
    hub.subscribe("Q", "A", healthy::incrementAndGet);

    assertDoesNotThrow(() -> hub.apply(change("t")));
    assertEquals(1, healthy.get());
  }

  @Test
  void unsubscribeStopsDelivery() {
    var hub = new ReactiveHub(null, List.of(rule("Q", "t", Set.of("A"))));
    var a = new AtomicInteger();
    var sub = hub.subscribe("Q", "A", a::incrementAndGet);

    hub.unsubscribe(sub);
    hub.apply(change("t"));
    assertEquals(0, a.get());
  }

  @Test
  void unsubscribeIsANoOpForAnUnknownQueryOrParam() {
    var hub = new ReactiveHub(null, List.of());
    hub.subscribe("Q", "A", () -> { });

    // unknown query (no bucket) and known query but unknown param: both must be silent no-ops
    assertDoesNotThrow(() -> hub.unsubscribe(
        new ReactiveHub.Subscription("ZZZ", "A", () -> { })));
    assertDoesNotThrow(() -> hub.unsubscribe(
        new ReactiveHub.Subscription("Q", "unknown-param", () -> { })));
  }
}
