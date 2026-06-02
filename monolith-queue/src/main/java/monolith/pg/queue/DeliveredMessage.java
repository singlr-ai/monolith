/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

/**
 * A message handed to a handler: its durable {@code id} (use it as the idempotency key to an external
 * system, since delivery is at-least-once), the {@code topic} it came from, its ordering {@code key}
 * and producer {@code idempotencyKey} (both possibly {@code null}), the opaque {@code payload}, and
 * which {@code attempt} this is (1-based) out of {@code maxAttempts}.
 */
public record DeliveredMessage(
    long id, String topic, String key, byte[] payload, String idempotencyKey,
    int attempt, int maxAttempts) {}
