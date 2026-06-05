/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.Result;
import monolith.pg.runtime.Tx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exercises the queue's producer and claim primitives against real Postgres. Skips with no database. */
@DisplayName("Queue against real Postgres")
class QueueIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final Duration LEASE = Duration.ofMinutes(1);
  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment conn;
  private static boolean available;

  @BeforeAll
  static void connect() {
    try {
      Result<MemorySegment> c = Pg.connect(ARENA, CONNINFO);
      if (c.isFailure()) return;
      conn = c.getOrThrow();
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (conn != null) Pg.finish(conn);
    ARENA.close();
  }

  @BeforeEach
  void freshSchema() {
    assumeTrue(available, "no Postgres reachable at " + CONNINFO);
    exec("DROP TABLE IF EXISTS monolith_queue");
    Queue.install(conn).getOrThrow();
  }

  @Test
  @DisplayName("enqueue stores a message and returns its id")
  void enqueueStoresAMessage() {
    long id = Queue.enqueue(conn, msg("t", null, "hello", null)).getOrThrow();

    assertTrue(id > 0);
    assertEquals(1, count("SELECT count(*) FROM monolith_queue"));
  }

  @Test
  @DisplayName("the queue table rejects structurally invalid rows even from a direct writer")
  void schemaConstraintsRejectInvalidRows() {
    // Defense in depth: a row the Java API would reject must also be rejected by the database, so a
    // migration / admin script / bulk loader cannot smuggle a structurally invalid queue row past it.
    assertTrue(tryExec("INSERT INTO monolith_queue (topic, payload, max_attempts) "
        + "VALUES ('has space', '\\x00'::bytea, 5)").isFailure(), "an invalid topic shape is rejected");
    assertTrue(tryExec("INSERT INTO monolith_queue (topic, payload, max_attempts) "
        + "VALUES ('evil\"; --', '\\x00'::bytea, 5)").isFailure(), "an injection-shaped topic is rejected");
    assertTrue(tryExec("INSERT INTO monolith_queue (topic, payload, max_attempts) "
        + "VALUES ('ok', '\\x00'::bytea, 0)").isFailure(), "max_attempts must be >= 1");
    assertTrue(tryExec("INSERT INTO monolith_queue (topic, payload, max_attempts, attempts) "
        + "VALUES ('ok', '\\x00'::bytea, 5, -1)").isFailure(), "attempts must be >= 0");
    // a well-formed direct insert still works
    assertTrue(tryExec("INSERT INTO monolith_queue (topic, payload, max_attempts) "
        + "VALUES ('ok.topic-1', '\\x00'::bytea, 5)").isSuccess(), "a valid row is accepted");
  }

  @Test
  @DisplayName("enqueue is atomic with the surrounding transaction")
  void enqueueIsAtomicWithTx() {
    Tx.tx(conn, c -> {
      Queue.enqueue(c, msg("t", null, "rolled-back", null)).getOrThrow();
      return Result.<Void>failure("the work failed after enqueue"); // forces ROLLBACK
    });
    assertEquals(0, count("SELECT count(*) FROM monolith_queue"), "the enqueue rolled back with the work");

    Tx.tx(conn, c -> Queue.enqueue(c, msg("t", null, "committed", null)).map(id -> null)).getOrThrow();
    assertEquals(1, count("SELECT count(*) FROM monolith_queue"), "the enqueue committed with the work");
  }

  @Test
  @DisplayName("a repeated idempotency key does not enqueue twice")
  void deduplicatesByIdempotencyKey() {
    long first = Queue.enqueue(conn, msg("t", null, "once", "req-1")).getOrThrow();
    long second = Queue.enqueue(conn, msg("t", null, "again", "req-1")).getOrThrow();

    assertEquals(first, second, "the second enqueue returns the existing id");
    assertEquals(1, count("SELECT count(*) FROM monolith_queue"));
  }

  @Test
  @DisplayName("claim returns the head of each key, skips blocked successors, and runs null keys in parallel")
  void claimRespectsPerKeyOrdering() {
    Queue.enqueue(conn, msg("t", "k", "a", "idem-a")).getOrThrow();  // head of key k
    Queue.enqueue(conn, msg("t", "k", "b", null)).getOrThrow();       // blocked behind a
    Queue.enqueue(conn, msg("t", "k2", "c", null)).getOrThrow();      // head of key k2
    Queue.enqueue(conn, msg("t", null, "d", null)).getOrThrow();      // unordered

    List<DeliveredMessage> claimed = Queue.claim(conn, "t", 10, LEASE).getOrThrow();

    Map<String, DeliveredMessage> byPayload = claimed.stream()
        .collect(Collectors.toMap(m -> new String(m.payload(), StandardCharsets.UTF_8), Function.identity()));
    assertEquals(java.util.Set.of("a", "c", "d"), byPayload.keySet(), "b is blocked behind a");

    DeliveredMessage a = byPayload.get("a");
    assertEquals("t", a.topic());
    assertEquals("k", a.key());
    assertEquals("idem-a", a.idempotencyKey());
    assertEquals(1, a.attempt());
    assertEquals(Message.DEFAULT_MAX_ATTEMPTS, a.maxAttempts());
    assertTrue(a.id() > 0);

    assertNull(byPayload.get("d").key(), "an unordered message has no key");
    assertNull(byPayload.get("d").idempotencyKey());
  }

  @Test
  @DisplayName("a claimed message is leased and not handed out again")
  void aClaimedMessageIsLeased() {
    Queue.enqueue(conn, msg("t", null, "only", null)).getOrThrow();

    assertEquals(1, Queue.claim(conn, "t", 10, LEASE).getOrThrow().size());
    assertEquals(0, Queue.claim(conn, "t", 10, LEASE).getOrThrow().size(), "still leased, not reclaimable");
  }

  @Test
  @DisplayName("claiming an empty topic returns nothing")
  void claimingAnEmptyTopicReturnsNothing() {
    assertTrue(Queue.claim(conn, "t", 10, LEASE).getOrThrow().isEmpty());
  }

  @Test
  @DisplayName("extendLease pushes a claimed message's lease further out")
  void extendLeasePushesTheLeaseOut() {
    Queue.enqueue(conn, msg("t", null, "slow", null)).getOrThrow();
    long id = Queue.claim(conn, "t", 1, Duration.ofMinutes(1)).getOrThrow().get(0).id();

    assertEquals("f", leaseBeyond(id, "30 minutes"), "the one-minute lease is well within 30 minutes");
    Queue.extendLease(conn, new long[] {id}, Duration.ofHours(1)).getOrThrow();
    assertEquals("t", leaseBeyond(id, "30 minutes"), "the lease now reaches beyond 30 minutes");
  }

  @Test
  @DisplayName("a claimed message whose lease expires is reclaimed (crash recovery)")
  void anExpiredLeaseIsReclaimed() {
    Queue.enqueue(conn, msg("t", null, "orphaned", null)).getOrThrow();

    var first = Queue.claim(conn, "t", 1, Duration.ofSeconds(1)).getOrThrow();
    assertEquals(1, first.size());
    assertEquals(1, first.get(0).attempt());
    assertTrue(Queue.claim(conn, "t", 1, Duration.ofSeconds(1)).getOrThrow().isEmpty(), "still leased");

    sleep(Duration.ofSeconds(2)); // the worker "crashed" holding the lease; let it expire

    var reclaimed = Queue.claim(conn, "t", 1, Duration.ofSeconds(1)).getOrThrow();
    assertEquals(1, reclaimed.size(), "the orphaned message is reclaimed");
    assertEquals(2, reclaimed.get(0).attempt(), "and the redelivery counts as a second attempt");
  }

  @Test
  @DisplayName("dead-lettered messages can be listed and replayed with a fresh budget")
  void deadLettersAndReplay() {
    Queue.enqueue(conn, msg("t", "k", "poison", "idem-p")).getOrThrow();
    long id = Queue.claim(conn, "t", 1, LEASE).getOrThrow().get(0).id();
    Queue.markDead(conn, id, "exploded").getOrThrow();

    var dead = Queue.deadLetters(conn, "t", 10).getOrThrow();
    assertEquals(1, dead.size());
    var dm = dead.get(0);
    assertEquals(id, dm.id());
    assertEquals("k", dm.key());
    assertEquals("poison", new String(dm.payload(), StandardCharsets.UTF_8));
    assertEquals(1, dm.attempts());
    assertEquals("exploded", dm.lastError());

    Queue.replay(conn, id).getOrThrow();
    assertTrue(Queue.deadLetters(conn, "t", 10).getOrThrow().isEmpty(), "no longer dead");
    var again = Queue.claim(conn, "t", 1, LEASE).getOrThrow();
    assertEquals(1, again.get(0).attempt(), "the replayed message starts over at attempt 1");
  }

  @Test
  @DisplayName("listing dead letters is empty when there are none")
  void deadLettersEmptyWhenNone() {
    assertTrue(Queue.deadLetters(conn, "t", 10).getOrThrow().isEmpty());
  }

  @Test
  @DisplayName("purgeSucceeded deletes finished messages and reports the count")
  void purgeSucceededDeletesFinished() {
    Queue.enqueue(conn, msg("t", null, "done", null)).getOrThrow();
    long id = Queue.claim(conn, "t", 1, LEASE).getOrThrow().get(0).id();
    Queue.markSucceeded(conn, id).getOrThrow();

    assertEquals(0, Queue.purgeSucceeded(conn, "t", Duration.ofHours(1)).getOrThrow(), "too recent to purge");
    assertEquals(1, Queue.purgeSucceeded(conn, "t", Duration.ZERO).getOrThrow(), "purged with no age floor");
    assertEquals(0, count("SELECT count(*) FROM monolith_queue"));
  }

  @Test
  @DisplayName("enqueue and claim surface a failure when the table is missing")
  void surfaceFailureWhenTableMissing() {
    exec("DROP TABLE monolith_queue");

    assertFalse(Queue.enqueue(conn, msg("t", null, "x", null)).isSuccess());
    assertFalse(Queue.claim(conn, "t", 10, LEASE).isSuccess());
  }

  // ---- helpers ----

  private static Message msg(String topic, String key, String payload, String idem) {
    return Message.builder()
        .withTopic(topic)
        .withKey(key)
        .withPayload(payload.getBytes(StandardCharsets.UTF_8))
        .withIdempotencyKey(idem)
        .build();
  }

  private static int count(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Integer.parseInt(Pg.textColumn(a, conn, sql).getOrThrow().get(0));
    }
  }

  private static String leaseBeyond(long id, String interval) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, conn,
          "SELECT lease_until > now() + interval '" + interval + "' FROM monolith_queue WHERE id = " + id)
          .getOrThrow().get(0);
    }
  }

  private static void exec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, sql).getOrThrow();
    }
  }

  private static Result<Void> tryExec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, conn, sql);
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
