/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReactiveEvent")
class ReactiveEventTest {

  @Test
  @DisplayName("a slot health check exposes its fields")
  void slotHealthCheckedExposesFields() {
    var event = new ReactiveEvent.SlotHealthChecked("app_feed", 2048, "reserved", true);
    assertEquals("app_feed", event.slot());
    assertEquals(2048, event.retainedBytes());
    assertEquals("reserved", event.walStatus());
    assertTrue(event.active());
  }

  @Test
  @DisplayName("a lost-slot event carries the slot")
  void slotLostCarriesTheSlot() {
    assertEquals("app_feed", new ReactiveEvent.SlotLost("app_feed").slot());
  }

  @Test
  @DisplayName("a stream-dropped event carries the slot and the error")
  void streamDroppedExposesFields() {
    var event = new ReactiveEvent.StreamDropped("app_feed", "copy stream ended");
    assertEquals("app_feed", event.slot());
    assertEquals("copy stream ended", event.error());
  }

  @Test
  @DisplayName("a stream-reconnected event distinguishes a gap from a clean resume")
  void streamReconnectedExposesGap() {
    assertTrue(new ReactiveEvent.StreamReconnected("app_feed", true).gap());
    assertFalse(new ReactiveEvent.StreamReconnected("app_feed", false).gap());
    assertEquals("app_feed", new ReactiveEvent.StreamReconnected("app_feed", false).slot());
  }
}
