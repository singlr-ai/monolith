/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import monolith.pg.runtime.MonolithEvent;

/**
 * Reactive-layer events, emitted through the same {@link monolith.pg.runtime.Observability} seam as the
 * runtime's events (they extend {@link MonolithEvent.Extension}). The {@link SlotMonitor} emits these so
 * an adapter can alert on a replication slot's retained WAL and on a lost slot.
 */
public sealed interface ReactiveEvent extends MonolithEvent.Extension {

  /** One poll of a replication slot's health: how much WAL it retains, its status, and whether attached. */
  record SlotHealthChecked(String slot, long retainedBytes, String walStatus, boolean active)
      implements ReactiveEvent {}

  /** A slot was found lost (invalidated): the change feed has a gap and subscribers must re-query. */
  record SlotLost(String slot) implements ReactiveEvent {}
}
