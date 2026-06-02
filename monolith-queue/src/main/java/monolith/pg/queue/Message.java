/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.time.Instant;
import java.util.Objects;

/**
 * A message to enqueue: a {@code topic} (which logical queue), an opaque binary {@code payload}, and
 * optional delivery controls. Build one with {@link #builder()}.
 *
 * <ul>
 *   <li>{@code key}: the ordering key. Messages with the same {@code (topic, key)} are delivered in
 *       order, one at a time; a {@code null} key means no ordering (fully parallel).
 *   <li>{@code idempotencyKey}: producer deduplication. A second enqueue with the same
 *       {@code (topic, idempotencyKey)} is a no-op.
 *   <li>{@code runAt}: deliver no earlier than this instant ({@code null} means now).
 *   <li>{@code maxAttempts}: how many delivery attempts before the message is dead-lettered.
 * </ul>
 */
public record Message(
    String topic, String key, byte[] payload, String idempotencyKey, Instant runAt, int maxAttempts) {

  /** Default attempt budget before a message is dead-lettered. */
  public static final int DEFAULT_MAX_ATTEMPTS = 25;

  public Message {
    Objects.requireNonNull(topic, "topic");
    if (topic.isBlank()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    Objects.requireNonNull(payload, "payload");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A fluent builder; {@code topic} and {@code payload} are required, the rest optional. */
  public static final class Builder {
    private String topic;
    private String key;
    private byte[] payload;
    private String idempotencyKey;
    private Instant runAt;
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    public Builder withTopic(String topic) {
      this.topic = topic;
      return this;
    }

    public Builder withKey(String key) {
      this.key = key;
      return this;
    }

    public Builder withPayload(byte[] payload) {
      this.payload = payload;
      return this;
    }

    public Builder withIdempotencyKey(String idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    public Builder withRunAt(Instant runAt) {
      this.runAt = runAt;
      return this;
    }

    public Builder withMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Message build() {
      return new Message(topic, key, payload, idempotencyKey, runAt, maxAttempts);
    }
  }
}
