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
 * replication connection for the whole lifecycle: it opens, streams, and reconnects on that connection
 * alone. {@link #close()} signals the loop to stop, waits for it to fully terminate, and only then drops
 * the slot and its publication — on its own admin connection, as the sole remaining actor. Because the
 * loop thread is already dead by then, no reconnect can resurrect a slot being dropped and no two threads
 * ever race the same DDL (a leaked slot retains WAL without bound, the highest-severity operational risk).
 *
 * <p>Requires {@code wal_level = logical}. Close it to stop streaming and drop the slot.
 */
public final class Invalidator implements AutoCloseable {

  /** Bounds every connect the loop makes, so a server outage cannot wedge reconnect or shutdown. */
  private static final int CONNECT_TIMEOUT_SECONDS = 5;

  /** Upper bound on how long {@link #close()} waits for the loop to stop and drop the slot. */
  private static final long SHUTDOWN_JOIN_MILLIS = 15_000;

  /** Teardown retries per drop (slot, then publication) and the pause between them: ~5s of settling. */
  private static final int TEARDOWN_DROP_ATTEMPTS = 50;
  private static final long TEARDOWN_DROP_BACKOFF_MILLIS = 100;

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
      // Stop streaming and release the slot by closing the connection; close() drops the slot once the
      // loop has fully terminated, so the drop never races a reconnect still in flight on this thread.
      closeStreamQuietly();
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

  /**
   * Drop the slot and its publication, reliably but best effort. Called by {@link #close()} once the loop
   * thread has terminated (so it is the sole actor) or by the constructor's failure path. The server
   * releases the slot asynchronously after the stream connection closes, and the publication drop can lose
   * a brief lock race with the detaching walsender, so retry each until it is gone (a leaked slot retains
   * WAL). Give up quietly if the server is unreachable: the orphan is then reclaimed by
   * {@code max_slot_wal_keep_size} or {@link Wal#dropInactive}, and shutdown must not fail on a down DB.
   */
  private void dropSlot() {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment admin = Pg.connect(a, conninfo).getOrThrow();
      try {
        // Drop the slot and the publication on independent retry budgets. They must not share one: the
        // slot can stay active for seconds while a walsender detaches, and coupling the two let a slow
        // slot drop starve the publication drop of retries, leaking the publication. The slot goes first
        // because it retains WAL and because dropping it frees the walsender that contends for the
        // publication's lock, so DROP PUBLICATION then succeeds promptly.
        retryDrop(() -> Wal.dropSlotOnly(admin, slot));
        retryDrop(() -> Wal.dropPublication(admin, slot));
      } finally {
        Pg.finish(admin);
      }
    } catch (RuntimeException unreachableAtShutdown) {
      // the server is down: the orphaned slot is reclaimed by max_slot_wal_keep_size or
      // Wal.dropInactive. We must not throw, or close() would fail because the database is unreachable.
    }
  }

  /**
   * Run one teardown drop, retrying for ~5s as the server settles (a walsender detaching, a brief lock
   * race on the publication). Uninterruptible: a stray interrupt on the calling thread is deferred (the
   * retries still run the full budget) and restored at the end, so nothing can cut a drop short and leak a
   * WAL-retaining slot or its publication.
   */
  private void retryDrop(Runnable drop) {
    boolean interrupted = false;
    try {
      for (int attempt = 0; attempt < TEARDOWN_DROP_ATTEMPTS; attempt++) {
        try {
          drop.run();
          return;
        } catch (RuntimeException settling) {
          try {
            Thread.sleep(TEARDOWN_DROP_BACKOFF_MILLIS);
          } catch (InterruptedException e) {
            interrupted = true; // defer: do not let close()'s interrupt abort teardown and leak the slot
          }
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
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
      thread.join(SHUTDOWN_JOIN_MILLIS); // wait for the loop to stop streaming and release the slot
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    // The loop thread has terminated (or, in the pathological case, the join elapsed): drop the slot and
    // publication here, as the sole remaining actor. Running on the caller's thread — not the interrupted
    // loop thread — there is no interrupt to cut the drop short and no reconnect left to resurrect the slot.
    dropSlot();
  }
}
