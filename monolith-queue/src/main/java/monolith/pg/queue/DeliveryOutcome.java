/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

/** What a single delivery attempt resulted in. */
public enum DeliveryOutcome {
  /** The handler succeeded; the message is acknowledged. */
  SUCCEEDED,
  /** The handler failed but attempts remain; the message is scheduled to retry. */
  RETRIED,
  /** The handler failed and the attempt budget is spent; the message is dead-lettered. */
  DEAD
}
