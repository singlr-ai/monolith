/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Pg;
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
  private final WalStream stream;
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
        if (running) System.err.println("[monolith reactive] " + e.getMessage());
      }
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
