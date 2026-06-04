/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sentinel that turns "the database harness was not running" into a hard build failure instead of a
 * silent skip. Every other {@code *IT} self-skips when no Postgres is reachable, so a local or CI
 * {@code mvn verify} could pass the coverage gate while exercising none of the FFM, WAL, grants,
 * compliance, or queue paths that carry the project's highest risk.
 *
 * <p>This test is inert by default, so a contributor without a database is not blocked. CI (and anyone
 * running {@code -Pci}) sets {@code -Dmonolith.requireDb=true}; under that flag a missing or
 * misconfigured database fails the build, guaranteeing the database-backed tests actually ran. It also
 * checks {@code wal_level = logical}, the precondition the reactive/WAL tests need to run rather than skip.
 */
@DisplayName("Database harness is present when required")
class RequireDatabaseIT {

  private static final boolean REQUIRED = Boolean.getBoolean("monolith.requireDb");

  private static final String CONNINFO = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  @Test
  @DisplayName("when -Dmonolith.requireDb=true, a logical-decoding Postgres must be reachable")
  void databaseHarnessIsReachableAndLogical() {
    assumeTrue(REQUIRED,
        "set -Dmonolith.requireDb=true (the ci profile does) to require the database harness");

    PgPool pool = null;
    try {
      pool = new PgPool(CONNINFO, 1); // throws if the handshake fails — that is the hard failure we want
      MemorySegment conn = pool.lease().getOrThrow();
      try (Arena a = Arena.ofConfined()) {
        var rows = Pg.textColumn(a, conn, "SHOW wal_level").getOrThrow();
        assertTrue(!rows.isEmpty(), "SHOW wal_level returned no row from " + CONNINFO);
        assertEquals("logical", rows.get(0),
            "the database integration tests require wal_level=logical; got '" + rows.get(0) + "'");
      } finally {
        pool.release(conn);
      }
    } catch (RuntimeException e) {
      throw new AssertionError("monolith.requireDb is set but no usable Postgres is reachable at "
          + CONNINFO + " — the database integration tests would silently skip. Start the CI harness"
          + " (postgres:18 with wal_level=logical) or unset the flag. Cause: " + e.getMessage(), e);
    } finally {
      if (pool != null) pool.close();
    }
  }
}
