/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PgReplicaSet")
class PgReplicaSetTest {

  private static final class FakeSource implements ConnectionSource {
    boolean closed;

    @Override public Result<MemorySegment> lease() { return Result.success(MemorySegment.NULL); }
    @Override public void release(MemorySegment conn) { }
    @Override public void close() { closed = true; }
  }

  @Test
  void writesGoToThePrimary() {
    var primary = new FakeSource();
    var set = new PgReplicaSet(primary, List.of(new FakeSource()));
    assertSame(primary, set.primary());
  }

  @Test
  void readsRoundRobinAcrossTheReplicas() {
    var r0 = new FakeSource();
    var r1 = new FakeSource();
    var set = new PgReplicaSet(new FakeSource(), List.of(r0, r1));

    assertSame(r0, set.reader());
    assertSame(r1, set.reader());
    assertSame(r0, set.reader()); // wraps around
    assertSame(r1, set.reader());
  }

  @Test
  void readsFallBackToThePrimaryWithNoReplicas() {
    var primary = new FakeSource();
    var set = new PgReplicaSet(primary, List.of());
    assertSame(primary, set.reader());
  }

  @Test
  void closingClosesThePrimaryAndEveryReplica() {
    var primary = new FakeSource();
    var replica = new FakeSource();
    try (var set = new PgReplicaSet(primary, List.of(replica))) {
      assertSame(primary, set.primary());
    }
    assertTrue(primary.closed);
    assertTrue(replica.closed);
  }
}
