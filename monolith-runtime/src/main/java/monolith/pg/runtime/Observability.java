/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.util.Objects;

/**
 * Holds the process-wide {@link MonolithObserver} and routes events to it. Until one is installed the
 * observer is {@link MonolithObserver#NOOP}, and {@link #enabled()} lets the runtime skip building an
 * event entirely, so observability costs a single reference comparison when switched off.
 *
 * <pre>{@code
 * Observability.use(event -> switch (event) {            // an adapter, in its own module
 *   case MonolithEvent.TransactionRetried r -> meter.increment("tx.retries");
 *   default -> {}
 * });
 * }</pre>
 */
public final class Observability {

  private static volatile MonolithObserver observer = MonolithObserver.NOOP;

  /** Install the observer that receives every event from now on. */
  public static void use(MonolithObserver newObserver) {
    observer = Objects.requireNonNull(newObserver, "observer");
  }

  /** Restore the no-op observer, discarding events again (mainly for tests). */
  public static void reset() {
    observer = MonolithObserver.NOOP;
  }

  /** Whether an observer is installed. Guard event construction on a hot path with this. */
  public static boolean enabled() {
    return observer != MonolithObserver.NOOP;
  }

  /** Hand an event to the installed observer. */
  public static void emit(MonolithEvent event) {
    observer.onEvent(event);
  }

  private Observability() {}
}
