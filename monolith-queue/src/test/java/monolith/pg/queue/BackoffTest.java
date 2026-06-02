/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Backoff")
class BackoffTest {

  @Test
  @DisplayName("fixed returns the same delay every time")
  void fixedIsConstant() {
    var backoff = Backoff.fixed(Duration.ofSeconds(3));
    assertEquals(Duration.ofSeconds(3), backoff.delayFor(1));
    assertEquals(Duration.ofSeconds(3), backoff.delayFor(9));
  }

  @Test
  @DisplayName("exponential doubles from the base and caps at the max")
  void exponentialDoublesAndCaps() {
    var backoff = Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(10));
    assertEquals(Duration.ofSeconds(1), backoff.delayFor(1));  // base
    assertEquals(Duration.ofSeconds(2), backoff.delayFor(2));  // base * 2
    assertEquals(Duration.ofSeconds(8), backoff.delayFor(4));  // base * 8
    assertEquals(Duration.ofSeconds(10), backoff.delayFor(5)); // 16 capped to 10
    assertEquals(Duration.ofSeconds(10), backoff.delayFor(99)); // still capped
  }

  @Test
  @DisplayName("exponential never exceeds the max even when the base already does")
  void exponentialHonoursMaxBelowBase() {
    var backoff = Backoff.exponential(Duration.ofSeconds(10), Duration.ofSeconds(5));
    assertEquals(Duration.ofSeconds(5), backoff.delayFor(1));
  }
}
