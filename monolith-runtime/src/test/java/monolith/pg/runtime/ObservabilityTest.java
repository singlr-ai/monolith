/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The observability holder and event records, which need no database. */
@DisplayName("Observability")
class ObservabilityTest {

  @BeforeEach
  @AfterEach
  void reset() {
    Observability.reset();
  }

  @Test
  @DisplayName("starts disabled with the no-op observer that discards events")
  void defaultIsNoopAndDisabled() {
    assertFalse(Observability.enabled());
    Observability.emit(new MonolithEvent.ConnectionLeased(0)); // routes to NOOP, does nothing
  }

  @Test
  @DisplayName("installing an observer enables it and routes events to it")
  void installsAndRoutes() {
    var seen = new ArrayList<MonolithEvent>();
    Observability.use(seen::add);

    assertTrue(Observability.enabled());
    var event = new MonolithEvent.TransactionCommitted(1);
    Observability.emit(event);
    assertEquals(List.of(event), seen);
  }

  @Test
  @DisplayName("reset restores the no-op observer")
  void resetRestoresNoop() {
    Observability.use(event -> { throw new AssertionError("should not be called after reset"); });
    Observability.reset();

    assertFalse(Observability.enabled());
    Observability.emit(new MonolithEvent.PoolExhausted(Duration.ZERO));
  }

  @Test
  @DisplayName("a null observer is rejected")
  void rejectsNull() {
    assertThrows(NullPointerException.class, () -> Observability.use(null));
  }

  @Test
  @DisplayName("each event exposes its fields")
  void eventsExposeTheirFields() {
    assertEquals(2, new MonolithEvent.TransactionCommitted(2).attempts());

    var retried = new MonolithEvent.TransactionRetried(1, "40001");
    assertEquals(1, retried.attempt());
    assertEquals("40001", retried.sqlState());

    var rolledBack = new MonolithEvent.TransactionRolledBack(3, "23505");
    assertEquals(3, rolledBack.attempts());
    assertEquals("23505", rolledBack.sqlState());

    assertEquals(500L, new MonolithEvent.ConnectionLeased(500).waitNanos());
    assertEquals(Duration.ofSeconds(1), new MonolithEvent.PoolExhausted(Duration.ofSeconds(1)).waited());
  }
}
