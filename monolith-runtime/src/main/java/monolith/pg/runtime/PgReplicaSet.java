/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Splits reads from writes across a primary and its streaming read replicas, to scale read-heavy
 * workloads. Writes (and any read that must be immediately consistent) go to {@link #primary};
 * other reads go to a replica chosen round-robin via {@link #reader}, falling back to the primary
 * when no replicas are configured.
 *
 * <p>Replicas lag the primary, so a read of your own just-written row from a replica may not see it
 * yet. When that matters, either read from {@link #primary}, or wait for the replica to catch up to
 * the write's LSN (the read-your-writes pattern documented in {@code docs/SCALING.md}).
 */
public final class PgReplicaSet implements AutoCloseable {

  private final ConnectionSource primary;
  private final List<ConnectionSource> replicas;
  private final AtomicInteger next = new AtomicInteger();

  public PgReplicaSet(ConnectionSource primary, List<ConnectionSource> replicas) {
    this.primary = primary;
    this.replicas = List.copyOf(replicas);
  }

  /** The primary, for writes and strongly-consistent reads. */
  public ConnectionSource primary() {
    return primary;
  }

  /** A replica for reads, chosen round-robin; the primary when there are no replicas. */
  public ConnectionSource reader() {
    if (replicas.isEmpty()) {
      return primary;
    }
    return replicas.get(Math.floorMod(next.getAndIncrement(), replicas.size()));
  }

  @Override
  public void close() {
    primary.close();
    replicas.forEach(ConnectionSource::close);
  }
}
