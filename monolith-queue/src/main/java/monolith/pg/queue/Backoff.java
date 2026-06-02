/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.time.Duration;

/**
 * How long to wait before retrying a failed message, as a function of which attempt just failed
 * (1-based). Supply your own (for example to add jitter) or use a factory.
 */
@FunctionalInterface
public interface Backoff {

  Duration delayFor(int attempt);

  /** The same delay after every failure. */
  static Backoff fixed(Duration delay) {
    return attempt -> delay;
  }

  /** {@code base} after the first failure, doubling each time, capped at {@code max}. */
  static Backoff exponential(Duration base, Duration max) {
    return attempt -> {
      long cap = max.toMillis();
      long delay = base.toMillis();
      for (int i = 1; i < attempt && delay < cap; i++) {
        delay = Math.min(cap, delay * 2);
      }
      return Duration.ofMillis(Math.min(delay, cap));
    };
  }
}
