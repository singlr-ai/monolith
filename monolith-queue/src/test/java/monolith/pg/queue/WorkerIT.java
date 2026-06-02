/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgPool;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Drives the full threaded worker against real Postgres. Skips when none is reachable. */
@DisplayName("Worker against real Postgres")
class WorkerIT {

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final Backoff FAST = Backoff.fixed(Duration.ofMillis(50));
  private static PgPool pool;
  private static boolean available;

  @BeforeAll
  static void connect() {
    try {
      pool = new PgPool(CONNINFO, 12);
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
      exec(c, "DROP TABLE IF EXISTS monolith_queue, worker_side_effect");
      Queue.install(c).getOrThrow();
      exec(c, "CREATE TABLE worker_side_effect (id bigint)");
      return null;
    });
  }

  @Test
  @DisplayName("it delivers every enqueued message")
  void deliversEveryMessage() {
    for (int i = 0; i < 10; i++) enqueue("t", null, "m" + i, 5);

    try (Worker ignored = worker("t").onMessage((c, m) -> Result.success(null)).start()) {
      awaitUntil(() -> count("status = 'succeeded'") == 10, "all messages succeeded");
    }
  }

  @Test
  @DisplayName("it retries a failing message and then succeeds")
  void retriesThenSucceeds() {
    enqueue("t", null, "flaky", 5);
    var deliveries = new ConcurrentHashMap<Long, AtomicInteger>();

    try (Worker ignored = worker("t").withBackoff(FAST).onMessage((c, m) -> {
      int n = deliveries.computeIfAbsent(m.id(), k -> new AtomicInteger()).incrementAndGet();
      return n == 1 ? Result.failure("first attempt fails") : Result.success(null);
    }).start()) {
      awaitUntil(() -> count("status = 'succeeded'") == 1, "the flaky message eventually succeeded");
    }
    assertEquals(2, onlyAttempts(), "it took two attempts");
  }

  @Test
  @DisplayName("it dead-letters a message that exhausts its attempts")
  void deadLettersWhenExhausted() {
    enqueue("t", null, "poison", 2);

    try (Worker ignored = worker("t").withBackoff(FAST)
        .onMessage((c, m) -> Result.failure("always fails")).start()) {
      awaitUntil(() -> count("status = 'dead'") == 1, "the poison message was dead-lettered");
    }
    assertEquals(2, onlyAttempts(), "after exactly two attempts");
  }

  @Test
  @DisplayName("it delivers same-key messages strictly in order")
  void preservesPerKeyOrder() {
    for (int i = 0; i < 6; i++) enqueue("t", "same", String.valueOf(i), 5);
    List<String> order = new CopyOnWriteArrayList<>();

    try (Worker ignored = worker("t").withConcurrency(6)
        .onMessage((c, m) -> {
          order.add(new String(m.payload(), StandardCharsets.UTF_8));
          return Result.success(null);
        }).start()) {
      awaitUntil(() -> count("status = 'succeeded'") == 6, "all six delivered");
    }
    assertEquals(List.of("0", "1", "2", "3", "4", "5"), order, "same key is strictly ordered");
  }

  @Test
  @DisplayName("transactional ack commits the handler's writes with the message")
  void transactionalAckCommitsHandlerWrites() {
    enqueue("t", null, "txn", 5);

    try (Worker ignored = worker("t").withTransactionalAck(true)
        .onMessage((c, m) -> insertSideEffect(c, m.id())).start()) {
      awaitUntil(() -> count("status = 'succeeded'") == 1, "the message succeeded");
    }
    assertEquals(1, sideEffects(), "the handler write committed with the acknowledgement");
  }

  @Test
  @DisplayName("close waits for an in-flight handler to finish")
  void closeWaitsForInFlight() throws InterruptedException {
    enqueue("t", null, "slow", 5);
    var started = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);

    Worker worker = worker("t").onMessage((c, m) -> {
      started.countDown();
      await(proceed);
      return Result.success(null);
    }).start();

    assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "the handler started");
    Thread releaser = new Thread(() -> {
      sleep(200);
      proceed.countDown();
    });
    releaser.start();
    worker.close(); // blocks until the in-flight handler finishes
    releaser.join();

    assertEquals(1, count("status = 'succeeded'"), "the in-flight message completed before close returned");
  }

  // ---- helpers ----

  private Worker.Builder worker(String topic) {
    return Queue.worker(pool, topic).withPollInterval(Duration.ofMillis(50)).withLease(Duration.ofSeconds(30));
  }

  private static void enqueue(String topic, String key, String payload, int maxAttempts) {
    onConn(c -> Queue.enqueue(c, Message.builder().withTopic(topic).withKey(key)
        .withPayload(payload.getBytes(StandardCharsets.UTF_8)).withMaxAttempts(maxAttempts).build())
        .getOrThrow());
  }

  private static Result<Void> insertSideEffect(MemorySegment conn, long id) {
    try (Arena a = Arena.ofConfined()) {
      var p = monolith.pg.runtime.PgParam.bind(a, id);
      return Pg.execParamsBinary(a, conn, "INSERT INTO worker_side_effect (id) VALUES ($1)",
          p.values(), p.lengths(), p.formats()).map(res -> {
            Pg.clear(res);
            return null;
          });
    }
  }

  private static int count(String where) {
    return onConn(c -> {
      try (Arena a = Arena.ofConfined()) {
        return Integer.parseInt(
            Pg.textColumn(a, c, "SELECT count(*) FROM monolith_queue WHERE " + where).getOrThrow().get(0));
      }
    });
  }

  private static int onlyAttempts() {
    return onConn(c -> {
      try (Arena a = Arena.ofConfined()) {
        return Integer.parseInt(Pg.textColumn(a, c, "SELECT attempts FROM monolith_queue").getOrThrow().get(0));
      }
    });
  }

  private static int sideEffects() {
    return onConn(c -> {
      try (Arena a = Arena.ofConfined()) {
        return Integer.parseInt(
            Pg.textColumn(a, c, "SELECT count(*) FROM worker_side_effect").getOrThrow().get(0));
      }
    });
  }

  private static void awaitUntil(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      sleep(25);
    }
    fail("timed out waiting for: " + what);
  }

  private static void assertTrue(boolean condition, String message) {
    org.junit.jupiter.api.Assertions.assertTrue(condition, message);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
