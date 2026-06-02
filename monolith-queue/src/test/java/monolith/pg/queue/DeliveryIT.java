/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Drives the sequential delivery logic directly against real Postgres. Skips when none is reachable. */
@DisplayName("Delivery against real Postgres")
class DeliveryIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final Backoff BACKOFF = Backoff.fixed(Duration.ofSeconds(1));
  private static PgPool pool;
  private static boolean available;

  @BeforeAll
  static void connect() {
    try {
      pool = new PgPool(CONNINFO, 4);
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.close();
  }

  @BeforeEach
  void freshSchema() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    onConn(c -> {
      exec(c, "DROP TABLE IF EXISTS monolith_queue, delivery_side_effect");
      Queue.install(c).getOrThrow();
      exec(c, "CREATE TABLE delivery_side_effect (id bigint)");
      return null;
    });
  }

  @Test
  @DisplayName("a successful handler acknowledges the message (separate ack)")
  void separateAckSuccess() {
    var message = enqueueAndClaim(5);

    var outcome = Delivery.process(pool, message, (c, m) -> Result.success(null), false, BACKOFF);

    assertEquals(DeliveryOutcome.SUCCEEDED, outcome);
    assertEquals("succeeded", status(message.id()));
  }

  @Test
  @DisplayName("transactional ack commits the handler's writes with the acknowledgement")
  void transactionalAckSuccess() {
    var message = enqueueAndClaim(5);

    var outcome = Delivery.process(pool, message,
        (c, m) -> insertSideEffect(c, m.id()), true, BACKOFF);

    assertEquals(DeliveryOutcome.SUCCEEDED, outcome);
    assertEquals("succeeded", status(message.id()));
    assertEquals(1, count("SELECT count(*) FROM delivery_side_effect"), "the handler write committed too");
  }

  @Test
  @DisplayName("a failing handler with attempts left schedules a retry")
  void failureWithAttemptsLeftRetries() {
    var message = enqueueAndClaim(5);

    var outcome = Delivery.process(pool, message, (c, m) -> Result.failure("nope"), false, BACKOFF);

    assertEquals(DeliveryOutcome.RETRIED, outcome);
    assertEquals("pending", status(message.id()));
  }

  @Test
  @DisplayName("a failing handler that is out of attempts is dead-lettered")
  void failureOutOfAttemptsDeadLetters() {
    var message = enqueueAndClaim(1); // claim consumes the only attempt

    var outcome = Delivery.process(pool, message, (c, m) -> Result.failure("boom"), false, BACKOFF);

    assertEquals(DeliveryOutcome.DEAD, outcome);
    assertEquals("dead", status(message.id()));
  }

  @Test
  @DisplayName("a handler that throws is treated as a failure")
  void aThrownExceptionIsAFailure() {
    var message = enqueueAndClaim(1);

    var outcome = Delivery.process(pool, message, (c, m) -> {
      throw new IllegalStateException("kaboom");
    }, false, BACKOFF);

    assertEquals(DeliveryOutcome.DEAD, outcome);
    assertEquals("dead", status(message.id()));
  }

  @Test
  @DisplayName("transactional ack rolls the handler's writes back on failure")
  void transactionalAckRollsBackOnFailure() {
    var message = enqueueAndClaim(5);

    var outcome = Delivery.process(pool, message, (c, m) -> {
      insertSideEffect(c, m.id()).getOrThrow(); // written, then the handler fails
      return Result.<Void>failure("changed my mind");
    }, true, BACKOFF);

    assertEquals(DeliveryOutcome.RETRIED, outcome);
    assertEquals("pending", status(message.id()));
    assertEquals(0, count("SELECT count(*) FROM delivery_side_effect"), "the handler write rolled back");
  }

  @Test
  @DisplayName("the outcome enum exposes its values")
  void enumExposesValues() {
    assertEquals(3, DeliveryOutcome.values().length);
    assertEquals(DeliveryOutcome.DEAD, DeliveryOutcome.valueOf("DEAD"));
  }

  // ---- helpers ----

  private static Result<Void> insertSideEffect(MemorySegment conn, long id) {
    try (Arena a = Arena.ofConfined()) {
      var p = monolith.pg.runtime.PgParam.bind(a, id);
      return Pg.execParamsBinary(a, conn, "INSERT INTO delivery_side_effect (id) VALUES ($1)",
          p.values(), p.lengths(), p.formats()).map(res -> {
            Pg.clear(res);
            return null;
          });
    }
  }

  private static DeliveredMessage enqueueAndClaim(int maxAttempts) {
    return onConn(c -> {
      Queue.enqueue(c, Message.builder().withTopic("t").withPayload("p".getBytes(StandardCharsets.UTF_8))
          .withMaxAttempts(maxAttempts).build()).getOrThrow();
      return Queue.claim(c, "t", 1, Duration.ofMinutes(1)).getOrThrow().get(0);
    });
  }

  private static String status(long id) {
    return onConn(c -> {
      try (Arena a = Arena.ofConfined()) {
        return Pg.textColumn(a, c, "SELECT status FROM monolith_queue WHERE id = " + id).getOrThrow().get(0);
      }
    });
  }

  private static int count(String sql) {
    return onConn(c -> {
      try (Arena a = Arena.ofConfined()) {
        return Integer.parseInt(Pg.textColumn(a, c, sql).getOrThrow().get(0));
      }
    });
  }

  private static <T> T onConn(java.util.function.Function<MemorySegment, T> work) {
    MemorySegment c = pool.lease().getOrThrow();
    try {
      return work.apply(c);
    } finally {
      pool.release(c);
    }
  }

  private static void exec(MemorySegment conn, String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    }
  }
}
