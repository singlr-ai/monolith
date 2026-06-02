/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import monolith.pg.runtime.MonolithEvent;

/**
 * What the queue did, emitted through the same {@link monolith.pg.runtime.Observability} seam as the
 * runtime's events (it extends {@link MonolithEvent.Extension}). Watch enqueue rate against success
 * rate for throughput, and dead-letter rate for trouble.
 */
public sealed interface QueueEvent extends MonolithEvent.Extension {

  /** A message was enqueued onto {@code topic}. */
  record Enqueued(String topic, long id) implements QueueEvent {}

  /** A message was delivered successfully on its {@code attempts}-th attempt. */
  record Succeeded(String topic, long id, int attempts) implements QueueEvent {}

  /** A delivery failed with attempts left, so the message will be retried. */
  record Retried(String topic, long id, int attempt, String error) implements QueueEvent {}

  /** A delivery failed with no attempts left, so the message was dead-lettered. */
  record DeadLettered(String topic, long id, int attempts, String error) implements QueueEvent {}
}
