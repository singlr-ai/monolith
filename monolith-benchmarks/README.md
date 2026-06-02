# monolith-benchmarks

JMH microbenchmarks for the hot paths. Internal tooling: not published to Maven Central, not
coverage-gated, and kept out of the default reactor so the normal build never downloads JMH or runs a
benchmark. It builds only under the `benchmarks` profile.

## Build the runner

```sh
mvn -Pbenchmarks -pl monolith-benchmarks -am package
```

This shades a self-contained `monolith-benchmarks/target/benchmarks.jar` whose main class is the JMH
runner.

## Run

```sh
# all benchmarks, JMH defaults (5 warmup + 5 measurement iterations, 1 fork)
java --enable-native-access=ALL-UNNAMED -jar monolith-benchmarks/target/benchmarks.jar

# one class, quick smoke
java --enable-native-access=ALL-UNNAMED -jar monolith-benchmarks/target/benchmarks.jar PgParamBench -f 1 -wi 2 -i 3
```

`--enable-native-access=ALL-UNNAMED` is required for the libpq FFM path.

## Benchmarks

- **`PgParamBench`** (no database) measures binary parameter encoding: binding a row into native memory
  (`PgParam.bind`, including the per-operation `Arena` lifecycle) and encoding individual scalars.
- **`BinaryQueryBench`** (needs Postgres) measures the end-to-end FFM binary path: a point select that
  binds a binary parameter, runs it through libpq, and reads the binary result, leased from and returned
  to the pool, at concurrency. Point `MONOLITH_TEST_CONNINFO` at a database; it creates and seeds its own
  `bench_rows` table.

## Measure under constraint, not abundance

A benchmark on a fast laptop with abundant RAM flatters the code and hides allocation pressure and
leaks. Run the FFM path under a small heap with native-memory tracking on to see what a production
container sees:

```sh
java --enable-native-access=ALL-UNNAMED -Xmx256m -XX:NativeMemoryTracking=summary \
  -jar monolith-benchmarks/target/benchmarks.jar BinaryQueryBench
```

The companion `soak` Maven profile applies the same constraint to the stress test (`FfmStressIT`):

```sh
MONOLITH_STRESS_SECONDS=300 mvn -Psoak -pl monolith-runtime verify -Dtest=FfmStressIT
```
