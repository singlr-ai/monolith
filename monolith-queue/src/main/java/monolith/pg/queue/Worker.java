/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import monolith.pg.runtime.ConnectionSource;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;

/**
 * Drains a topic: it claims due messages from the {@link Queue} and runs each through a
 * {@link MessageHandler} on a virtual thread, up to a concurrency limit, retrying and dead-lettering
 * per the message's attempt budget. Run as many workers (in one process or many) as you like against
 * the same topic; {@code SKIP LOCKED} keeps them from colliding.
 *
 * <p>It polls on an interval for due and scheduled messages, and renews the lease on in-flight
 * messages in the background so a slow handler does not lose its claim. {@link #close()} stops
 * claiming and waits for in-flight handlers to finish. Build one with {@link Queue#worker}.
 */
public final class Worker implements AutoCloseable {

  private final ConnectionSource source;
  private final String topic;
  private final int concurrency;
  private final Duration lease;
  private final Duration pollInterval;
  private final Backoff backoff;
  private final boolean transactionalAck;
  private final MessageHandler handler;

  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
    var t = new Thread(r, "monolith-queue-heartbeat");
    t.setDaemon(true);
    return t;
  });
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  private final AtomicInteger active = new AtomicInteger();
  private volatile boolean running;
  private Thread control;
  private MemorySegment listenConn; // held for the worker's life, LISTENing for enqueue notifications
  private String listenConninfo;    // non-null when listenConn is a dedicated unpooled connection we own
  private boolean ownsListener;     // whether to finish (true) or release to the pool (false) listenConn

  private Worker(Builder builder) {
    this.source = builder.source;
    this.topic = builder.topic;
    this.concurrency = builder.concurrency;
    this.lease = builder.lease;
    this.pollInterval = builder.pollInterval;
    this.backoff = builder.backoff;
    this.transactionalAck = builder.transactionalAck;
    this.handler = Objects.requireNonNull(builder.handler, "handler (call onMessage)");
  }

  private Worker start() {
    running = true;
    listenConn = openListener();
    try (Arena arena = Arena.ofConfined()) {
      Pg.listen(arena, listenConn, "\"monolith_queue_" + topic + "\""); // quoted channel identifier
    }
    long beatMillis = Math.max(1, lease.toMillis() / 3);
    heartbeat.scheduleAtFixedRate(this::renewLeases, beatMillis, beatMillis, TimeUnit.MILLISECONDS);
    control = Thread.ofVirtual().name("monolith-queue-" + topic).start(this::loop);
    return this;
  }

  /**
   * Opens the connection the worker {@code LISTEN}s on for its whole life. When the source exposes a
   * conninfo (a single-database {@link monolith.pg.runtime.PgPool}), this is a dedicated <em>unpooled</em>
   * connection, so the worker never ties up a pool slot — a size-1 pool stays usable for claiming and
   * delivery. Multi-database sources fall back to a pooled lease.
   */
  private MemorySegment openListener() {
    String conninfo = source.dedicatedConninfo().orElse(null);
    if (conninfo != null) {
      this.listenConninfo = conninfo;
      this.ownsListener = true;
      try (Arena arena = Arena.ofConfined()) {
        return Pg.connect(arena, conninfo).getOrThrow();
      }
    }
    return source.lease().getOrThrow();
  }

  private void loop() {
    while (running) {
      int free = concurrency - active.get();
      if (free <= 0) {
        LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        continue;
      }
      List<DeliveredMessage> batch = claimBatch(free);
      if (batch.isEmpty()) {
        awaitNotification(); // wakes on an enqueue NOTIFY, or after the poll interval as a backstop
        continue;
      }
      for (DeliveredMessage message : batch) {
        active.incrementAndGet();
        inFlight.add(message.id());
        handlers.submit(() -> {
          try {
            Delivery.process(source, message, handler, transactionalAck, backoff);
          } finally {
            inFlight.remove(message.id());
            active.decrementAndGet();
          }
        });
      }
    }
    handlers.close(); // stop claiming, then drain in-flight handlers
  }

  private List<DeliveredMessage> claimBatch(int free) {
    MemorySegment conn = source.lease().getOrThrow();
    try {
      return Queue.claim(conn, topic, free, lease).getOrThrow();
    } finally {
      source.release(conn);
    }
  }

  /** Blocks on the LISTEN connection until an enqueue notification arrives or the poll interval elapses. */
  private void awaitNotification() {
    try (Arena arena = Arena.ofConfined()) {
      Pg.waitReadable(arena, listenConn, (int) pollInterval.toMillis());
      Pg.drainNotifications(listenConn);
    }
  }

  /**
   * Sends a notification on the topic's channel so a blocked {@link #awaitNotification} returns at once.
   * On the dedicated-listener path this uses a transient <em>unpooled</em> connection, so {@link #close}
   * never blocks on an exhausted pool; otherwise it leases from the pool as before.
   */
  private void wakeListener() {
    if (ownsListener) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment conn = Pg.connect(arena, listenConninfo).getOrThrow();
        try {
          notifyTopic(arena, conn);
        } finally {
          Pg.finish(conn);
        }
      }
      return;
    }
    MemorySegment conn = source.lease().getOrThrow();
    try (Arena arena = Arena.ofConfined()) {
      notifyTopic(arena, conn);
    } finally {
      source.release(conn);
    }
  }

  private void notifyTopic(Arena arena, MemorySegment conn) {
    var p = PgParam.bind(arena, "monolith_queue_" + topic);
    Pg.execParamsBinary(arena, conn, "SELECT pg_notify($1, '')", p.values(), p.lengths(), p.formats())
        .map(res -> {
          Pg.clear(res);
          return res;
        }).getOrThrow();
  }

  private void renewLeases() {
    if (inFlight.isEmpty()) {
      return;
    }
    long[] ids = inFlight.stream().mapToLong(Long::longValue).toArray();
    MemorySegment conn = source.lease().getOrThrow();
    try {
      Queue.extendLease(conn, ids, lease).getOrThrow();
    } finally {
      source.release(conn);
    }
  }

  @Override
  public void close() {
    running = false;
    LockSupport.unpark(control);
    try {
      wakeListener(); // nudge a blocked awaitNotification; best effort, it also wakes on the poll timeout
    } catch (RuntimeException e) {
      // a wake failure (e.g. the database is down) must not stall shutdown: awaitNotification has a
      // poll-interval timeout, so the control loop still observes running=false and exits on its own.
    }
    try {
      control.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (ownsListener) {
      Pg.finish(listenConn); // we own the dedicated connection; close it rather than returning to the pool
    } else {
      source.release(listenConn);
    }
    heartbeat.close();
  }

  /** Configures and starts a worker. Required: a {@code source}, a {@code topic}, and {@link #onMessage}. */
  public static final class Builder {
    private final ConnectionSource source;
    private final String topic;
    private int concurrency = 8;
    private Duration lease = Duration.ofMinutes(1);
    private Duration pollInterval = Duration.ofSeconds(1);
    private Backoff backoff = Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(5));
    private boolean transactionalAck;
    private MessageHandler handler;

    Builder(ConnectionSource source, String topic) {
      this.source = source;
      this.topic = topic;
    }

    public Builder withConcurrency(int concurrency) {
      this.concurrency = concurrency;
      return this;
    }

    public Builder withLease(Duration lease) {
      this.lease = lease;
      return this;
    }

    public Builder withPollInterval(Duration pollInterval) {
      this.pollInterval = pollInterval;
      return this;
    }

    public Builder withBackoff(Backoff backoff) {
      this.backoff = backoff;
      return this;
    }

    /** Acknowledge each message in the handler's own transaction (exactly-once for database handlers). */
    public Builder withTransactionalAck(boolean transactionalAck) {
      this.transactionalAck = transactionalAck;
      return this;
    }

    public Builder onMessage(MessageHandler handler) {
      this.handler = handler;
      return this;
    }

    /** Builds the worker and starts draining. */
    public Worker start() {
      return new Worker(this).start();
    }
  }
}
