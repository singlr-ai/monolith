/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.helidon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WsResults")
class WsResultsTest {

  @Test
  void anEmptyResultIsJustAZeroRowCount() {
    assertArrayEquals(new byte[] {0, 0, 0, 0}, WsResults.frame(List.of()));
  }

  @Test
  void eachRowIsLengthPrefixedAfterTheCount() {
    byte[] framed = WsResults.frame(List.of(new byte[] {10, 20}, new byte[] {30}));
    byte[] expected = {
        0, 0, 0, 2,        // row count = 2
        0, 0, 0, 2, 10, 20, // row 0: length 2, then bytes
        0, 0, 0, 1, 30,     // row 1: length 1, then bytes
    };
    assertArrayEquals(expected, framed);
  }
}
