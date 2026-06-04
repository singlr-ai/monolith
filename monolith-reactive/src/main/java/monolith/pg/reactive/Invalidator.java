/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.SlotHealth;
import monolith.pg.runtime.Wal;
import monolith.pg.runtime.WalStream;

/**
 * Drives a {@link ReactiveHub} from the Postgres write-ahead log. Owns a logical replication slot
 * and a streaming {@link WalStream}; one thread blocks on the replication socket and feeds each
 * change to the hub as it arrives (sub-poll-interval latency), with built-in backpressure, if a
 * subscriber callback is slow the drain pauses and TCP flow control pauses the server.
 *
 * <p>A libpq connection is single-threaded, so the loop thread is the <em>sole</em> owner of the
 * replication connection and the slot for the whole lifecycle, including teardown: it opens, reconnects,
 * and finally drops the slot itself. {@link #close()} only signals the loop and waits for it, so no
 * connection is ever touched by two threads and a reconnect in flight cannot resurrect a slot that is
 * being dropped (a leaked slot retains WAL without bound, the highest-severity operational risk).
 *
 * <p>Requires {@code wal_level = logical}. Close it to stop streaming and drop the slot.
 */
public final class Invalidator implements AutoCloseable {

  /** Bounds every connect the loop makes, so a server outage cannot wedge reconnect or shutdown. */
  private static final int CONNECT_TIMEOUT_SECONDS = 5;

  /** Upper bound on how long {@link #close()} waits for the loop to stop and drop the slot. */
  private static final long SHUTDOWN_JOIN_MILLIS = 15_000;

  private final String conninfo;
  private final String slot;
  private final ReactiveHub hub;
  private volatile WalStream stream; // owned by the loop thread; null between a drop and a reconnect
  private final Thread thread;
  private volatile boolean running = true;

  public Invalidator(String conninfo, ReactiveHub hub, String slot) {
    this.conninfo = withConnectTimeout(conninfo);
    this.hub = hub;
    this.slot = slot;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment admin = Pg.connect(a, this.conninfo).getOrThrow();
      try {
        Wal.recreate(admin, slot);
      } finally {
        Pg.finish(admin);
      }
    }
    try {
      this.stream = new WalStream(this.conninfo, slot);
    } catch (RuntimeException cannotStream) {
      dropSlot(); // created the slot but cannot stream from it: do not leak it
      throw cannotStream;
    }
    this.thread = Thread.ofPlatform().name("monolith-wal-" + slot).daemon(true).start(this::loop);
  }

  private void loop() {
    try {
      while (running) {
        try {
          stream.poll(200, hub::apply); // event-driven; blocks on the replication socket
          stream.confirm();              // advance the slot (release WAL)
        } catch (RuntimeException e) {
          if (running) {
            Observability.emit(new ReactiveEvent.StreamDropped(slot, e.getMessage()));
            reconnect();
          }
        }
      }
    } finally {
      teardown(); // the loop thread drops its own slot, so close() never has to touch it
    }
  }

  /**
   * Rebuild the dropped stream so a connection loss (network reset, failover, a terminated walsender)
   * does not silently stop the feed. Retries with backoff until it reconnects or the Invalidator is
   * closing. The slot is server-side state that survives a client drop, so the new stream resumes from
   * the slot's confirmed LSN and replays any changes made during the gap. Only when the slot itself was
   * lost (it outran its retention budget, so changes were dropped) does recovery re-query every
   * subscriber, the same semantics as {@code SlotMonitor}'s lost-slot recovery.
   */
  private void reconnect() {
    closeStreamQuietly();
    long backoffMs = 100;
    while (running) {
      if (!sleep(backoffMs)) return; // interrupted by close()
      try {
        boolean gap = ensureSlot();
        stream = new WalStream(conninfo, slot);
        if (gap) hub.invalidateAll();
        Observability.emit(new ReactiveEvent.StreamReconnected(slot, gap));
        return;
      } catch (RuntimeException retry) {
        backoffMs = Math.min(backoffMs * 2, 2000);
      }
    }
  }

  /** Ensure the slot is usable, recreating it if it was lost or has vanished. True if it was recreated. */
  private boolean ensureSlot() {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment admin = Pg.connect(a, conninfo).getOrThrow();
      try {
        SlotHealth health = Wal.health(admin, slot);
        if (!health.exists() || health.isLost()) {
          Wal.recreate(admin, slot);
          return true;
        }
        return false;
      } finally {
        Pg.finish(admin);
      }
    }
  }

  /** Final teardown on the loop thread once the loop exits: close the stream and drop the slot reliably. */
  private void teardown() {
    Thread.interrupted(); // clear the interrupt close() set, so the cleanup retries below can pause
    closeStreamQuietly();
    dropSlot();
  }

  /**
   * Drop the slot and its publication, reliably but best effort. The server releases the slot
   * asynchronously after {@link #closeStreamQuietly} ends the stream, and the publication drop can lose
   * a brief lock race with the detaching walsender, so retry until both are gone (a leaked slot retains
   * WAL). Give up quietly if the server is unreachable: the orphan is then reclaimed by
   * {@code max_slot_wal_keep_size} or {@link Wal#dropInactive}, and shutdown must not fail on a down DB.
   */
  private void dropSlot() {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment admin = Pg.connect(a, conninfo).getOrThrow();
      try {
        for (int attempt = 0; attempt < 30; attempt++) { // ~3s, as the walsender finishes detaching
          try {
            Wal.drop(admin, slot);
            return; // slot and publication both gone
          } catch (RuntimeException settling) {
            if (!pause(100)) return; // interrupted: give up, best effort
          }
        }
      } finally {
        Pg.finish(admin);
      }
    } catch (RuntimeException unreachableAtShutdown) {
      // the server is down: the orphaned slot is reclaimed by max_slot_wal_keep_size or
      // Wal.dropInactive. We must not throw, or close() would fail because the database is unreachable.
    }
  }

  /** Uninterruptible pause for the teardown retries; returns false if interrupted. */
  private static boolean pause(long ms) {
    try {
      Thread.sleep(ms);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Close the current stream and forget it, so teardown after an interrupted reconnect cannot double-close. */
  private void closeStreamQuietly() {
    WalStream current = stream;
    stream = null;
    if (current != null) {
      try {
        current.close();
      } catch (RuntimeException alreadyDead) {
        // the connection is already gone; nothing more to release
      }
    }
  }

  /** Sleep for the backoff; return false if interrupted or closing (the Invalidator is stopping). */
  private boolean sleep(long ms) {
    try {
      Thread.sleep(ms);
      return running;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Append a bounded connect timeout to the connection string (keyword format, which the reactive layer
   * already assumes, since {@link WalStream} appends {@code replication=database}), unless the caller set
   * one. This is what lets the loop exit, and {@link #close()} return, promptly even mid-reconnect to a
   * server that is down.
   */
  private static String withConnectTimeout(String conninfo) {
    return conninfo.contains("connect_timeout")
        ? conninfo
        : conninfo + " connect_timeout=" + CONNECT_TIMEOUT_SECONDS;
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt(); // break the reconnect backoff; native calls end on their own connect timeout
    try {
      thread.join(SHUTDOWN_JOIN_MILLIS); // the loop drops the slot in its finally; just wait for it
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
