/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Message")
class MessageTest {

  @Test
  @DisplayName("the builder sets every field")
  void builderSetsEveryField() {
    var when = Instant.parse("2026-06-01T00:00:00Z");
    var message = Message.builder()
        .withTopic("eligibility")
        .withKey("patient-1")
        .withPayload(new byte[] {1, 2, 3})
        .withIdempotencyKey("req-1")
        .withRunAt(when)
        .withMaxAttempts(7)
        .build();

    assertEquals("eligibility", message.topic());
    assertEquals("patient-1", message.key());
    assertArrayEquals(new byte[] {1, 2, 3}, message.payload());
    assertEquals("req-1", message.idempotencyKey());
    assertEquals(when, message.runAt());
    assertEquals(7, message.maxAttempts());
  }

  @Test
  @DisplayName("optional fields default and may be null")
  void optionalFieldsDefault() {
    var message = Message.builder().withTopic("t").withPayload(new byte[0]).build();

    assertNull(message.key());
    assertNull(message.idempotencyKey());
    assertNull(message.runAt());
    assertEquals(Message.DEFAULT_MAX_ATTEMPTS, message.maxAttempts());
  }

  @Test
  @DisplayName("a null or blank topic is rejected")
  void rejectsBadTopic() {
    assertThrows(NullPointerException.class,
        () -> Message.builder().withPayload(new byte[0]).build());
    assertThrows(IllegalArgumentException.class,
        () -> Message.builder().withTopic("  ").withPayload(new byte[0]).build());
  }

  @Test
  @DisplayName("a topic that is not an identifier-safe channel name is rejected at construction")
  void rejectsUnsafeTopic() {
    // A topic becomes part of a LISTEN channel identifier; anything that could break out of the quoted
    // identifier (a quote, a semicolon, whitespace) or overrun the 63-byte channel limit must be rejected.
    for (String bad : java.util.List.of(
        "evil\"; DROP TABLE monolith_queue; --",
        "has space",
        "quote\"inside",
        "a".repeat(49))) {
      assertThrows(IllegalArgumentException.class,
          () -> Message.builder().withTopic(bad).withPayload(new byte[0]).build(),
          "should reject unsafe topic: " + bad);
    }
    // The same boundary holds at the worker entry point, before any connection is touched.
    assertThrows(IllegalArgumentException.class,
        () -> monolith.pg.queue.Queue.worker(null, "evil\"; --"));
  }

  @Test
  @DisplayName("identifier-safe topics with dots, dashes, and underscores are accepted")
  void acceptsSafeTopics() {
    for (String ok : java.util.List.of("eligibility", "claims.v2", "patient-events", "t", "a".repeat(48))) {
      assertEquals(ok, Message.builder().withTopic(ok).withPayload(new byte[0]).build().topic());
    }
  }

  @Test
  @DisplayName("a null payload is rejected")
  void rejectsNullPayload() {
    assertThrows(NullPointerException.class, () -> Message.builder().withTopic("t").build());
  }

  @Test
  @DisplayName("a non-positive attempt budget is rejected")
  void rejectsBadMaxAttempts() {
    var ex = assertThrows(IllegalArgumentException.class,
        () -> Message.builder().withTopic("t").withPayload(new byte[0]).withMaxAttempts(0).build());
    assertEquals("maxAttempts must be at least 1: 0", ex.getMessage());
  }
}
