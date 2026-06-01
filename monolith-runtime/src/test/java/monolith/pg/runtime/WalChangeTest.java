/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WalChange")
class WalChangeTest {

  @Test
  void exposesTableAndRawLine() {
    var change = new WalChange("orders", "table public.orders: INSERT: id[integer]:1");
    assertEquals("orders", change.table());
    assertEquals("table public.orders: INSERT: id[integer]:1", change.raw());
  }

  @Test
  void extractsAQuotedColumnValue() {
    var change = new WalChange("orders",
        "table public.orders: INSERT: id[uuid]:'a1' region[text]:'EU'");
    assertEquals(Set.of("EU"), change.valuesOf("region"));
  }

  @Test
  void extractsAnUnquotedColumnValue() {
    var change = new WalChange("orders", "table public.orders: INSERT: n[integer]:5");
    assertEquals(Set.of("5"), change.valuesOf("n"));
  }

  @Test
  void returnsBothValuesUnderReplicaIdentityFull() {
    var change = new WalChange("orders",
        "table public.orders: UPDATE: old-key: region[text]:'EU' new-tuple: region[text]:'US'");
    assertEquals(Set.of("EU", "US"), change.valuesOf("region"));
  }

  @Test
  void returnsEmptyForAColumnNotInTheLine() {
    var change = new WalChange("orders", "table public.orders: INSERT: id[integer]:1");
    assertTrue(change.valuesOf("region").isEmpty());
  }
}
