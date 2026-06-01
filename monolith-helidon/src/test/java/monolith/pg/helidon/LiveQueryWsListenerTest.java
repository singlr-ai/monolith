/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.helidon;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.common.buffers.BufferData;
import io.helidon.common.socket.SocketContext;
import io.helidon.websocket.WsSession;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import monolith.pg.reactive.ReactiveHub;
import monolith.pg.runtime.PgInvalidationRule;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.WalChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LiveQueryWsListener")
class LiveQueryWsListenerTest {

  /** Captures sends and closes; can be told to fail sends to exercise the push catch path. */
  private static final class FakeSession implements WsSession {
    int sends;
    Integer closeCode;
    String closeReason;
    boolean failSend;

    @Override public WsSession send(String text, boolean last) { return record(); }
    @Override public WsSession send(BufferData data, boolean last) { return record(); }
    @Override public WsSession ping(BufferData d) { return this; }
    @Override public WsSession pong(BufferData d) { return this; }
    @Override public WsSession close(int code, String reason) {
      closeCode = code; closeReason = reason; return this;
    }
    @Override public WsSession terminate() { return this; }
    @Override public SocketContext socketContext() { return null; }

    private WsSession record() {
      sends++;
      if (failSend) throw new RuntimeException("session closed");
      return this;
    }
  }

  private static PgInvalidationRule rule(String query, String table, Set<String> params) {
    return new PgInvalidationRule() {
      @Override public String query() { return query; }
      @Override public String[] tables() { return new String[] {table}; }
      @Override public Set<String> affectedParams(
          String t, Function<String, Set<String>> valuesOf, PgPool pool) {
        return t.equals(table) ? params : Set.of();
      }
    };
  }

  private record Fixture(ReactiveHub hub, LiveQueryWsListener listener, AtomicInteger runs) {}

  private static Fixture fixture(PgInvalidationRule... rules) {
    var hub = new ReactiveHub(null, List.of(rules));
    var runs = new AtomicInteger();
    var listener = LiveQueryWsListener.builder(hub)
        .query("Q", param -> {
          runs.incrementAndGet();
          return WsResults.frame(List.of());
        })
        .build();
    return new Fixture(hub, listener, runs);
  }

  @Test
  void aValidFrameSubscribesAndPushesTheCurrentResult() {
    var f = fixture();
    var s = new FakeSession();

    f.listener().onMessage(s, "Q:p1", true);

    assertEquals(1, f.runs().get()); // runner executed once
    assertEquals(1, s.sends);        // current result pushed
    assertNull(s.closeCode);
  }

  @Test
  void asecondFrameOnTheSameSessionIsIgnored() {
    var f = fixture();
    var s = new FakeSession();
    f.listener().onMessage(s, "Q:p1", true);

    f.listener().onMessage(s, "Q:p2", true); // one subscription per session

    assertEquals(1, f.runs().get());
    assertEquals(1, s.sends);
  }

  @Test
  void aFrameWithoutAColonIsRejected() {
    var s = new FakeSession();
    fixture().listener().onMessage(s, "no-colon", true);
    assertEquals(1008, s.closeCode);
    assertEquals(0, s.sends);
  }

  @Test
  void anUnknownQueryIsRejected() {
    var s = new FakeSession();
    fixture().listener().onMessage(s, "Nope:p", true);
    assertEquals(1008, s.closeCode);
    assertTrue(s.closeReason.contains("Nope"));
  }

  @Test
  void anInvalidationPushesAFreshResult() {
    var f = fixture(rule("Q", "t", Set.of("p1")));
    var s = new FakeSession();
    f.listener().onMessage(s, "Q:p1", true); // 1 push (current)

    f.hub().apply(new WalChange("t", java.util.Map.of())); // wakes the subscriber

    assertEquals(2, s.sends); // pushed again on the change
  }

  @Test
  void closingUnsubscribesSoNoFurtherPushesArrive() {
    var f = fixture(rule("Q", "t", Set.of("p1")));
    var s = new FakeSession();
    f.listener().onMessage(s, "Q:p1", true);

    f.listener().onClose(s, 1000, "bye");
    f.hub().apply(new WalChange("t", java.util.Map.of()));

    assertEquals(1, s.sends); // only the initial push, none after close
  }

  @Test
  void closingAnUnknownSessionIsANoOp() {
    var f = fixture();
    assertDoesNotThrow(() -> f.listener().onClose(new FakeSession(), 1000, ""));
  }

  @Test
  void onErrorUnsubscribesTheSession() {
    var f = fixture(rule("Q", "t", Set.of("p1")));
    var s = new FakeSession();
    f.listener().onMessage(s, "Q:p1", true);

    f.listener().onError(s, new RuntimeException("boom"));
    f.hub().apply(new WalChange("t", java.util.Map.of()));

    assertEquals(1, s.sends);
  }

  @Test
  void aFailingSendDuringPushIsSwallowed() {
    var f = fixture();
    var s = new FakeSession();
    s.failSend = true;
    assertDoesNotThrow(() -> f.listener().onMessage(s, "Q:p1", true));
    assertEquals(1, f.runs().get()); // runner ran; the send failure did not escape
  }
}
