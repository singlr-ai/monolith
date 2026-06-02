/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Result;

/**
 * What a worker runs for each delivered message. It receives the connection the worker leased (use it
 * for database work; with transactional acknowledgement that work commits atomically with marking the
 * message done) and the message. A {@link Result.Success} acknowledges the message; a
 * {@link Result.Failure} (or a thrown exception) retries it, or dead-letters it once the attempt
 * budget is spent. Because delivery is at-least-once, make the work idempotent, keyed on
 * {@link DeliveredMessage#id()}.
 */
@FunctionalInterface
public interface MessageHandler {

  Result<Void> handle(MemorySegment conn, DeliveredMessage message);
}
