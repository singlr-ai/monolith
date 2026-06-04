/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

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

  /**
   * Allowed topic shape: an identifier-safe channel name with no quoting tricks. A topic becomes part of
   * a {@code LISTEN} channel identifier ({@code "monolith_queue_" + topic}); restricting it to this
   * charset means it can never break out of the quoted identifier. The 48-char cap keeps the prefixed
   * channel name within Postgres's 63-byte {@code NOTIFY} channel limit.
   */
  private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,48}");

  public Message {
    requireValidTopic(topic);
    Objects.requireNonNull(payload, "payload");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
    }
  }

  /**
   * Validates a queue topic, the single boundary for every topic that reaches SQL (as a {@code LISTEN}
   * channel identifier or a bound parameter). Returns the topic so callers can validate inline.
   */
  static String requireValidTopic(String topic) {
    Objects.requireNonNull(topic, "topic");
    if (!TOPIC.matcher(topic).matches()) {
      throw new IllegalArgumentException(
          "topic must match [A-Za-z0-9._-]{1,48} (an identifier-safe channel name): \"" + topic + "\"");
    }
    return topic;
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
