/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
 * <p>Requires {@code wal_level = logical}. Close it to stop streaming and drop the slot.
 */
public final class Invalidator implements AutoCloseable {

  private final String conninfo;
  private final String slot;
  private final ReactiveHub hub;
  private volatile WalStream stream; // replaced on reconnect; only the loop thread reassigns it
  private final Thread thread;
  private volatile boolean running = true;

  public Invalidator(String conninfo, ReactiveHub hub, String slot) {
    this.conninfo = conninfo;
    this.hub = hub;
    this.slot = slot;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment admin = Pg.connect(a, conninfo).getOrThrow();
      Wal.recreate(admin, slot);
      Pg.finish(admin);
    }
    this.stream = new WalStream(conninfo, slot);
    this.thread = Thread.ofPlatform().name("monolith-wal-" + slot).daemon(true).start(this::loop);
  }

  private void loop() {
    while (running) {
      try {
        stream.poll(200, hub::apply); // event-driven; blocks on the replication socket
        stream.confirm();              // advance the slot (release WAL)
      } catch (RuntimeException e) {
        if (running) {
          System.err.println("[monolith reactive] stream error, reconnecting: " + e.getMessage());
          reconnect();
        }
      }
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

  private void closeStreamQuietly() {
    try {
      stream.close();
    } catch (RuntimeException ignore) {
      // the connection is already dead; nothing more to release
    }
  }

  /** Sleep for the backoff; return false if interrupted (the Invalidator is closing). */
  private boolean sleep(long ms) {
    try {
      Thread.sleep(ms);
      return running;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
    try {
      thread.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    stream.close();
    try (Arena a = Arena.ofConfined()) {
      MemorySegment admin = Pg.connect(a, conninfo).getOrThrow();
      Wal.drop(admin, slot);
      Pg.finish(admin);
    }
  }
}
