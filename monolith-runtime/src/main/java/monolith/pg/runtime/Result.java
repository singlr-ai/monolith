/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A success-or-failure value that makes recoverable outcomes explicit in a method's return type
 * instead of as a thrown exception, so callers handle both arms with an exhaustive {@code switch}.
 *
 * @param <T> the success value type
 */
public sealed interface Result<T> {

  /** A successful outcome carrying its value. */
  record Success<T>(T value) implements Result<T> {}

  /** A failed outcome carrying a human-readable error and an optional underlying cause. */
  record Failure<T>(String error, Exception cause) implements Result<T> {

    public Failure(String error) {
      this(error, null);
    }
  }

  static <T> Result<T> success(T value) {
    return new Success<>(value);
  }

  static <T> Result<T> failure(String error) {
    return new Failure<>(error);
  }

  static <T> Result<T> failure(String error, Exception cause) {
    return new Failure<>(error, cause);
  }

  default boolean isSuccess() {
    return this instanceof Success<T>;
  }

  default boolean isFailure() {
    return this instanceof Failure<T>;
  }

  default Result<T> ifSuccess(Consumer<T> action) {
    if (this instanceof Success<T>(var value)) {
      action.accept(value);
    }
    return this;
  }

  default Result<T> ifFailure(Consumer<Failure<T>> action) {
    if (this instanceof Failure<T> f) {
      action.accept(f);
    }
    return this;
  }

  default <U> Result<U> map(Function<T, U> fn) {
    return switch (this) {
      case Success<T>(var value) -> new Success<>(fn.apply(value));
      case Failure<T>(var error, var cause) -> new Failure<>(error, cause);
    };
  }

  /** Chains another fallible step onto a success, short-circuiting on the first failure. */
  default <U> Result<U> flatMap(Function<T, Result<U>> fn) {
    return switch (this) {
      case Success<T>(var value) -> fn.apply(value);
      case Failure<T>(var error, var cause) -> new Failure<>(error, cause);
    };
  }

  /** Returns the success value, or throws a {@link RuntimeException} carrying the failure. */
  default T getOrThrow() {
    return switch (this) {
      case Success<T>(var value) -> value;
      case Failure<T>(var error, var cause) ->
          throw cause != null ? new RuntimeException(error, cause) : new RuntimeException(error);
    };
  }
}
