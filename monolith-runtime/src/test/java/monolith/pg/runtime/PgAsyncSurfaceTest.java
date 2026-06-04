/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The async, non-pinning query scaffolding is untrusted native surface with no integration coverage
 * (see {@code Pg}'s "Async query protocol" banner and the ROADMAP). With FFM, misuse faults the JVM,
 * not a catch-able exception, so these entry points must not be public API until the reactor lands and
 * fault tests exist. They stay package-private — reachable by the streaming path in this package, not by
 * downstream code.
 */
@DisplayName("Async FFM scaffolding is not public API")
class PgAsyncSurfaceTest {

  private static final List<String> ASYNC_METHODS = List.of(
      "sendQueryParamsBinary", "consumeInput", "isBusy", "getResult", "pollReadable");

  @Test
  void asyncScaffoldingMethodsAreNotPublic() {
    for (Method m : Pg.class.getDeclaredMethods()) {
      if (ASYNC_METHODS.contains(m.getName())) {
        assertFalse(Modifier.isPublic(m.getModifiers()),
            () -> "async scaffolding method must not be public: Pg." + m.getName());
      }
    }
  }
}
