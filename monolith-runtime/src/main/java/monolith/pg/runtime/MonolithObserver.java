/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

/**
 * The observability seam: a single sink the runtime hands its {@link MonolithEvent}s to. It is a
 * functional interface with no third-party types, so the core stays pure JDK; an adapter (for
 * OpenTelemetry, Micrometer, or a log line) lives in its own module, implements this, and is the only
 * place those dependencies are pulled. Install one with {@link Observability#use}.
 *
 * <p>This matters because Monolith goes through libpq over FFM and never touches JDBC, so an
 * OpenTelemetry auto-instrumentation agent (which hooks {@code java.sql}) sees none of its queries or
 * transactions. Emitting through this seam is the only way they appear in a trace.
 */
@FunctionalInterface
public interface MonolithObserver {

  /** Handle one event. Called on the thread doing the work, so an implementation must not block. */
  void onEvent(MonolithEvent event);

  /** The default: discards every event. {@link Observability} short-circuits before reaching it. */
  MonolithObserver NOOP = event -> {};
}
