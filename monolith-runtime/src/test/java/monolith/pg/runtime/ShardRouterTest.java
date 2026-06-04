/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShardRouter")
class ShardRouterTest {

  /** A no-op connection source that just records whether it was closed. */
  private static final class FakeSource implements ConnectionSource {
    boolean closed;

    @Override public Result<MemorySegment> lease() { return Result.success(MemorySegment.NULL); }
    @Override public void release(MemorySegment conn) { }
    @Override public void close() { closed = true; }
  }

  @Test
  void byHashRoutesEachTenantToOneShardDeterministically() {
    var shards = Map.<String, ConnectionSource>of("s0", new FakeSource(), "s1", new FakeSource(),
        "s2", new FakeSource());
    var router = ShardRouter.byHash(shards);

    var first = router.shardFor("acme");
    assertSame(first, router.shardFor("acme"), "the same tenant always maps to the same shard");
    assertTrue(shards.containsValue(first), "and to one of the configured shards");
  }

  @Test
  void aSourceWithoutASingleDatabaseHasNoDedicatedConninfo() {
    // The interface default: a plain source (and any multi-database source) exposes no conninfo for a
    // dedicated side connection, so consumers like a queue worker fall back to a pooled lease.
    assertTrue(new FakeSource().dedicatedConninfo().isEmpty());
  }

  @Test
  void anExplicitRouteFunctionIsHonored() {
    var only = new FakeSource();
    var router = new ShardRouter(Map.of("eu", only), tenant -> "eu");
    assertSame(only, router.shardFor("anyone"));
  }

  @Test
  void aRouteToAnUnknownShardIsRejected() {
    var router = new ShardRouter(Map.of("eu", new FakeSource()), tenant -> "us");
    var ex = assertThrows(IllegalArgumentException.class, () -> router.shardFor("bob"));
    assertTrue(ex.getMessage().contains("us"));
  }

  @Test
  void shardKeysExposeTheConfiguredShards() {
    var router = new ShardRouter(
        Map.of("a", new FakeSource(), "b", new FakeSource()), tenant -> "a");
    assertEquals(Set.of("a", "b"), router.shardKeys());
  }

  @Test
  void closingClosesEveryShard() {
    var a = new FakeSource();
    var b = new FakeSource();
    try (var router = new ShardRouter(Map.of("a", a, "b", b), tenant -> "a")) {
      assertTrue(router.shardKeys().contains("a"));
    }
    assertTrue(a.closed);
    assertTrue(b.closed);
  }
}
