/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import monolith.pg.runtime.MonolithEvent;

/**
 * Reactive-layer events, emitted through the same {@link monolith.pg.runtime.Observability} seam as the
 * runtime's events (they extend {@link MonolithEvent.Extension}). The {@link SlotMonitor} emits the slot
 * health events; the {@link Invalidator} emits the stream lifecycle events, so an adapter can alert on a
 * slot's retained WAL, on a lost slot, and on a change feed that is dropping and reconnecting (flapping).
 */
public sealed interface ReactiveEvent extends MonolithEvent.Extension {

  /** One poll of a replication slot's health: how much WAL it retains, its status, and whether attached. */
  record SlotHealthChecked(String slot, long retainedBytes, String walStatus, boolean active)
      implements ReactiveEvent {}

  /** A slot was found lost (invalidated): the change feed has a gap and subscribers must re-query. */
  record SlotLost(String slot) implements ReactiveEvent {}

  /** The replication stream dropped (network reset, failover, a terminated walsender); reconnect follows. */
  record StreamDropped(String slot, String error) implements ReactiveEvent {}

  /**
   * The replication stream reconnected after a drop. When {@code gap} is true the slot itself was lost
   * and had to be recreated, so the feed had a gap and every subscriber was re-queried; when false the
   * stream resumed from the slot's confirmed LSN and missed nothing. A high {@code gap} rate is the
   * signal worth alerting on.
   */
  record StreamReconnected(String slot, boolean gap) implements ReactiveEvent {}
}
