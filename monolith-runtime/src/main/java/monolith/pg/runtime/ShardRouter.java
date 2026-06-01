/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Routes each tenant to its own shard for shared-nothing horizontal scaling: every shard is an
 * independent Postgres database (or cluster) with its own {@link ConnectionSource}, and no query
 * ever spans shards. You supply the shards and a function from a tenant to a shard key, or use
 * {@link #byHash} to spread tenants evenly. Combine with {@code @Tenant} row-level security to get
 * both physical (shard) and logical (RLS) isolation.
 */
public final class ShardRouter implements AutoCloseable {

  private final Map<String, ConnectionSource> shards;
  private final Function<String, String> route;

  public ShardRouter(Map<String, ConnectionSource> shards, Function<String, String> route) {
    this.shards = Map.copyOf(shards);
    this.route = route;
  }

  /** A router that spreads tenants evenly across the shards by hashing (keys sorted for stability). */
  public static ShardRouter byHash(Map<String, ConnectionSource> shards) {
    List<String> keys = shards.keySet().stream().sorted().toList();
    return new ShardRouter(shards, tenant -> keys.get(Math.floorMod(tenant.hashCode(), keys.size())));
  }

  /** The connection source for the shard that owns {@code tenant}. */
  public ConnectionSource shardFor(String tenant) {
    String key = route.apply(tenant);
    ConnectionSource shard = shards.get(key);
    if (shard == null) {
      throw new IllegalArgumentException("no shard '" + key + "' for tenant '" + tenant + "'");
    }
    return shard;
  }

  public Set<String> shardKeys() {
    return shards.keySet();
  }

  @Override
  public void close() {
    shards.values().forEach(ConnectionSource::close);
  }
}
