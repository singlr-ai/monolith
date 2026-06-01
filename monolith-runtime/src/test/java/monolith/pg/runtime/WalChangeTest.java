/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WalChange")
class WalChangeTest {

  @Test
  void exposesItsTableAndColumnValues() {
    var change = new WalChange("orders", Map.of("region", Set.of("EU", "US")));
    assertEquals("orders", change.table());
    assertEquals(Set.of("EU", "US"), change.valuesOf("region"));
  }

  @Test
  void returnsAnEmptySetForAColumnNotInTheChange() {
    assertTrue(new WalChange("orders", Map.of()).valuesOf("missing").isEmpty());
  }
}
