/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.ConnectionSource;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Tx;

/**
 * Delivers one message to a handler and records the outcome. This is the sequential heart of a
 * {@link Worker}, kept separate from the threading so it can be reasoned about and tested on its own.
 *
 * <p>With {@code transactionalAck}, the handler's work and the acknowledgement commit in one
 * transaction (exactly-once for database-only handlers). Otherwise the handler runs and the
 * acknowledgement is a separate transaction (at-least-once, the right default for external effects).
 * A handler failure (returned or thrown) retries the message, or dead-letters it once its attempts
 * are spent.
 */
final class Delivery {

  static DeliveryOutcome process(
      ConnectionSource source, DeliveredMessage message, MessageHandler handler,
      boolean transactionalAck, Backoff backoff) {
    MemorySegment conn = source.lease().getOrThrow();
    try {
      Result<Void> result = transactionalAck
          ? Tx.tx(conn, c -> runHandler(c, message, handler)
              .flatMap(ignored -> Queue.markSucceeded(c, message.id())))
          : runHandler(conn, message, handler);
      boolean observed = Observability.enabled();
      return switch (result) {
        case Result.Success<Void> ignored -> {
          if (!transactionalAck) {
            Queue.markSucceeded(conn, message.id()).getOrThrow();
          }
          if (observed) {
            Observability.emit(new QueueEvent.Succeeded(message.topic(), message.id(), message.attempt()));
          }
          yield DeliveryOutcome.SUCCEEDED;
        }
        case Result.Failure<Void> failed -> {
          if (message.attempt() < message.maxAttempts()) {
            Queue.markPending(conn, message.id(), backoff.delayFor(message.attempt()), failed.error())
                .getOrThrow();
            if (observed) {
              Observability.emit(new QueueEvent.Retried(
                  message.topic(), message.id(), message.attempt(), failed.error()));
            }
            yield DeliveryOutcome.RETRIED;
          }
          Queue.markDead(conn, message.id(), failed.error()).getOrThrow();
          if (observed) {
            Observability.emit(new QueueEvent.DeadLettered(
                message.topic(), message.id(), message.attempt(), failed.error()));
          }
          yield DeliveryOutcome.DEAD;
        }
      };
    } finally {
      source.release(conn);
    }
  }

  /** Invokes the handler, turning a thrown exception into a failure so it retries rather than escaping. */
  private static Result<Void> runHandler(
      MemorySegment conn, DeliveredMessage message, MessageHandler handler) {
    try {
      return handler.handle(conn, message);
    } catch (Exception e) {
      return Result.failure("handler threw: " + e, e);
    }
  }

  private Delivery() {}
}
