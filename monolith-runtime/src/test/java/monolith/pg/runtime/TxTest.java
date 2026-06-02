/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import monolith.pg.runtime.Tx.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The retry policy's validation, which needs no database. */
@DisplayName("Tx.Retry")
class TxTest {

  @Test
  @DisplayName("rejects fewer than one attempt")
  void rejectsZeroAttempts() {
    var e = assertThrows(IllegalArgumentException.class, () -> new Retry(0, Duration.ofMillis(1)));
    assertEquals("maxAttempts must be at least 1: 0", e.getMessage());
  }

  @Test
  @DisplayName("rejects a null backoff")
  void rejectsNullBackoff() {
    assertThrows(NullPointerException.class, () -> new Retry(3, null));
  }

  @Test
  @DisplayName("rejects a negative backoff")
  void rejectsNegativeBackoff() {
    var e = assertThrows(IllegalArgumentException.class, () -> new Retry(3, Duration.ofMillis(-1)));
    assertEquals("backoff must not be negative: PT-0.001S", e.getMessage());
  }

  @Test
  @DisplayName("accepts a valid policy and exposes a default")
  void acceptsValid() {
    var policy = new Retry(5, Duration.ZERO);
    assertEquals(5, policy.maxAttempts());
    assertEquals(3, Retry.DEFAULT.maxAttempts());
  }
}
