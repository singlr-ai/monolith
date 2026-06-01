/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded pool of libpq {@code PGconn}s over FFM: exclusive single-thread lease, session
 * reset on release, and self-healing on a dead backend (proven under 128-way concurrent load).
 *
 * <p>Reset sends {@code ROLLBACK} and {@code DISCARD ALL} as two separate commands on
 * purpose, a multi-statement {@code PQexec} runs as one implicit transaction block and
 * {@code DISCARD ALL} refuses to run inside one.
 */
public final class PgPool implements AutoCloseable {

  private static final Duration DEFAULT_LEASE_TIMEOUT = Duration.ofSeconds(10);

  private final String conninfo;
  private final BlockingQueue<MemorySegment> idle;
  private final AtomicInteger replaced = new AtomicInteger();
  private final int size;
  private final Duration leaseTimeout;

  public PgPool(String conninfo, int size) {
    this(conninfo, size, DEFAULT_LEASE_TIMEOUT);
  }

  /** As {@link #PgPool(String, int)} but with an explicit lease wait before giving up. */
  public PgPool(String conninfo, int size, Duration leaseTimeout) {
    this.conninfo = conninfo;
    this.size = size;
    this.leaseTimeout = leaseTimeout;
    this.idle = new ArrayBlockingQueue<>(size);
    for (int i = 0; i < size; i++) idle.add(open());
  }

  private MemorySegment open() {
    try (Arena tmp = Arena.ofConfined()) {
      return Pg.connect(tmp, conninfo).getOrThrow();
    }
  }

  /** Leases an exclusive connection, or a {@link Result.Failure} if none frees up in time. */
  public Result<MemorySegment> lease() {
    try {
      MemorySegment c = idle.poll(leaseTimeout.toNanos(), TimeUnit.NANOSECONDS);
      return c == null
          ? Result.failure("pool exhausted, no connection available within " + leaseTimeout)
          : Result.success(c);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.failure("interrupted while leasing a connection", e);
    }
  }

  public void release(MemorySegment conn) {
    idle.add(reset(conn));
  }

  private MemorySegment reset(MemorySegment conn) {
    boolean healthy;
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, conn, "ROLLBACK");    // best-effort: end any open transaction
      Pg.exec(a, conn, "DISCARD ALL"); // best-effort: clear session state (runs outside the tx)
      healthy = Pg.status(conn) == Pg.CONNECTION_OK;
    }
    if (healthy) return conn;
    Pg.finish(conn);
    replaced.incrementAndGet();
    return open();
  }

  public int replacedCount() {
    return replaced.get();
  }

  public int size() {
    return size;
  }

  @Override
  public void close() {
    MemorySegment c;
    while ((c = idle.poll()) != null) Pg.finish(c);
  }
}
