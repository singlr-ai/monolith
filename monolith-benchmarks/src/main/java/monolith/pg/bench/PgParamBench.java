/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.bench;

import java.lang.foreign.Arena;
import java.util.concurrent.TimeUnit;
import monolith.pg.runtime.PgParam;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Database-free microbenchmarks for binary parameter encoding: the per-operation cost of binding a row
 * of parameters into native memory and of encoding individual values. Pure CPU and allocation, so it
 * runs anywhere with no Postgres.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class PgParamBench {

  @Benchmark
  public void bindMixedRow(Blackhole bh) {
    try (var arena = Arena.ofConfined()) {
      var bound = PgParam.bind(arena, 4821, "patient-4821", 1_234_567_890L);
      bh.consume(bound.values());
      bh.consume(bound.lengths());
      bh.consume(bound.formats());
    }
  }

  @Benchmark
  public byte[] encodeInt() {
    return PgParam.encode(4821);
  }

  @Benchmark
  public byte[] encodeLong() {
    return PgParam.encode(1_234_567_890_123L);
  }

  @Benchmark
  public byte[] encodeText() {
    return PgParam.encode("a moderately sized text value for encoding");
  }
}
