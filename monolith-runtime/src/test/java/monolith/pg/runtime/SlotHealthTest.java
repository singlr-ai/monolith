/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlotHealth")
class SlotHealthTest {

  @Test
  @DisplayName("exposes its fields and reports a healthy slot")
  void exposesFieldsForAHealthySlot() {
    var healthy = new SlotHealth(true, true, "reserved", 4096);
    assertTrue(healthy.exists());
    assertTrue(healthy.active());
    assertEquals("reserved", healthy.walStatus());
    assertEquals(4096, healthy.retainedBytes());
    assertFalse(healthy.isLost());
  }

  @Test
  @DisplayName("a lost slot is detected")
  void detectsALostSlot() {
    assertTrue(new SlotHealth(true, false, "lost", 1_000_000).isLost());
  }

  @Test
  @DisplayName("an absent slot exists is false and is not lost")
  void absentSlotIsNotLost() {
    var absent = new SlotHealth(false, false, "none", 0);
    assertFalse(absent.exists());
    assertFalse(absent.isLost());
  }
}
