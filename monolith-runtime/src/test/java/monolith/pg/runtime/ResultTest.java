/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Result")
class ResultTest {

  @Nested
  @DisplayName("factories and predicates")
  class FactoriesAndPredicates {

    @Test
    void successCarriesItsValueAndReportsSuccess() {
      Result<String> r = Result.success("ok");
      assertInstanceOf(Result.Success.class, r);
      assertEquals("ok", ((Result.Success<String>) r).value());
      assertTrue(r.isSuccess());
      assertFalse(r.isFailure());
    }

    @Test
    void failureWithErrorOnlyHasNullCause() {
      Result<String> r = Result.failure("boom");
      var f = assertInstanceOf(Result.Failure.class, r);
      assertEquals("boom", f.error());
      assertEquals(null, f.cause());
      assertFalse(r.isSuccess());
      assertTrue(r.isFailure());
    }

    @Test
    void failureWithCauseKeepsBoth() {
      var cause = new IllegalStateException("why");
      var f = assertInstanceOf(Result.Failure.class, Result.failure("boom", cause));
      assertEquals("boom", f.error());
      assertSame(cause, f.cause());
    }
  }

  @Nested
  @DisplayName("side effects (ifSuccess / ifFailure)")
  class SideEffects {

    @Test
    void ifSuccessRunsOnlyForSuccessAndReturnsSelf() {
      var seen = new AtomicReference<String>();
      Result<String> r = Result.success("v");
      assertSame(r, r.ifSuccess(seen::set));
      assertEquals("v", seen.get());
    }

    @Test
    void ifSuccessIsSkippedForFailure() {
      var seen = new AtomicReference<String>();
      Result<String> r = Result.failure("e");
      assertSame(r, r.ifSuccess(seen::set));
      assertEquals(null, seen.get());
    }

    @Test
    void ifFailureRunsOnlyForFailureAndReturnsSelf() {
      var seen = new AtomicReference<String>();
      Result<String> r = Result.failure("e");
      assertSame(r, r.ifFailure(f -> seen.set(f.error())));
      assertEquals("e", seen.get());
    }

    @Test
    void ifFailureIsSkippedForSuccess() {
      var seen = new AtomicReference<String>();
      Result<String> r = Result.success("v");
      assertSame(r, r.ifFailure(f -> seen.set(f.error())));
      assertEquals(null, seen.get());
    }
  }

  @Nested
  @DisplayName("map / flatMap")
  class Transforms {

    @Test
    void mapTransformsASuccess() {
      assertEquals(3, Result.success("abc").map(String::length).getOrThrow());
    }

    @Test
    void mapPropagatesAFailureUntouched() {
      var cause = new RuntimeException("c");
      Result<Integer> r = Result.<String>failure("e", cause).map(String::length);
      var f = assertInstanceOf(Result.Failure.class, r);
      assertEquals("e", f.error());
      assertSame(cause, f.cause());
    }

    @Test
    void flatMapChainsASuccess() {
      Result<Integer> r = Result.success("abc").flatMap(s -> Result.success(s.length()));
      assertEquals(3, r.getOrThrow());
    }

    @Test
    void flatMapShortCircuitsOnFailureWithoutCallingTheFunction() {
      Result<Integer> r = Result.<String>failure("e").flatMap(s -> {
        throw new AssertionError("must not run on a failure");
      });
      assertEquals("e", assertInstanceOf(Result.Failure.class, r).error());
    }

    @Test
    void flatMapPropagatesAFailureReturnedByTheFunction() {
      Result<Integer> r = Result.success("abc").flatMap(s -> Result.failure("downstream"));
      assertEquals("downstream", assertInstanceOf(Result.Failure.class, r).error());
    }
  }

  @Nested
  @DisplayName("getOrThrow")
  class GetOrThrow {

    @Test
    void returnsTheValueOnSuccess() {
      assertEquals("v", Result.success("v").getOrThrow());
    }

    @Test
    void throwsWithCauseWhenPresent() {
      var cause = new IllegalStateException("root");
      var ex = assertThrows(RuntimeException.class, () -> Result.failure("e", cause).getOrThrow());
      assertEquals("e", ex.getMessage());
      assertSame(cause, ex.getCause());
    }

    @Test
    void throwsWithoutCauseWhenAbsent() {
      var ex = assertThrows(RuntimeException.class, () -> Result.failure("e").getOrThrow());
      assertEquals("e", ex.getMessage());
      assertEquals(null, ex.getCause());
    }
  }
}
