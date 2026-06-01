/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.MemorySegment;

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

  @Override
  void close();
}
