/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.reactive;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import monolith.pg.runtime.ConnectionSource;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.SlotHealth;
import monolith.pg.runtime.Wal;

/**
 * Polls a replication slot's {@link SlotHealth} on an interval and emits it through the
 * {@link Observability} seam, so the operational risk that matters most for the reactive feed, a
 * stalled consumer's slot retaining WAL until the disk fills, becomes a metric you can alert on. Emits
 * {@link ReactiveEvent.SlotHealthChecked} each poll (watch {@code retainedBytes}) and
 * {@link ReactiveEvent.SlotLost} when the slot has been invalidated.
 *
 * <p>Install an observer to receive the events; with none installed the monitor does nothing. On a
 * lost slot, recreate it ({@code Wal.recreate}) and call {@link ReactiveHub#invalidateAll} so every
 * subscriber re-queries past the gap.
 */
public final class SlotMonitor implements AutoCloseable {

  private final ConnectionSource source;
  private final String slot;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    var thread = new Thread(r, "monolith-slot-monitor");
    thread.setDaemon(true);
    return thread;
  });

  private SlotMonitor(ConnectionSource source, String slot) {
    this.source = source;
    this.slot = slot;
  }

  /** Start polling {@code slot} every {@code interval}; emits health through the observability seam. */
  public static SlotMonitor start(ConnectionSource source, String slot, Duration interval) {
    var monitor = new SlotMonitor(source, slot);
    long millis = Math.max(1, interval.toMillis());
    monitor.scheduler.scheduleAtFixedRate(monitor::check, 0, millis, TimeUnit.MILLISECONDS);
    return monitor;
  }

  private void check() {
    if (!Observability.enabled()) {
      return;
    }
    MemorySegment conn = source.lease().getOrThrow();
    try {
      SlotHealth health = Wal.health(conn, slot);
      Observability.emit(new ReactiveEvent.SlotHealthChecked(
          slot, health.retainedBytes(), health.walStatus(), health.active()));
      if (health.isLost()) {
        Observability.emit(new ReactiveEvent.SlotLost(slot));
      }
    } finally {
      source.release(conn);
    }
  }

  @Override
  public void close() {
    scheduler.close();
  }
}
