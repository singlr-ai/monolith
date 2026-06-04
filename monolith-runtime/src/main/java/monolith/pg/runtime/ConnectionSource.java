/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Anything that hands out exclusive Postgres connections: a {@link PgPool}, one shard of a
 * {@link ShardRouter}, or a member of a {@link PgReplicaSet}. Routing over this interface (rather
 * than a concrete pool) is what lets the scaling helpers compose pools without depending on how the
 * connections are made.
 */
public interface ConnectionSource extends AutoCloseable {

  /** Lease an exclusive connection, or a {@link Result.Failure} if none is available. */
  Result<MemorySegment> lease();

  /** Return a leased connection to the source. */
  void release(MemorySegment conn);

  /**
   * A conninfo for a dedicated, <em>unpooled</em> side connection — such as a queue worker's lifelong
   * {@code LISTEN} channel — when this source targets a single database. A long-lived consumer that
   * leased from the pool instead would tie up a slot for its whole life (a size-1 pool would starve).
   * Empty for multi-database sources (e.g. a shard router), whose consumers fall back to a pooled lease.
   */
  default Optional<String> dedicatedConninfo() {
    return Optional.empty();
  }

  @Override
  void close();
}
