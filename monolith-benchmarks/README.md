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
- **`BinaryQueryBench`** (needs Postgres) measures the end-to-end idiomatic FFM path: a point select
  that binds a binary parameter, runs it through libpq with `execParamsBinary`, and reads the binary
  result, leased from and returned to the pool, at concurrency. This is the generated path as it runs
  today.
- **`PreparedQueryBench`** (needs Postgres) measures the FFM prepared ceiling: `execPrepared` reusing a
  server-prepared plan on a dedicated connection the pool never resets, the fair match for a pgjdbc
  `PreparedStatement`.
- **`JdbcQueryBench`** (needs Postgres) is the pgjdbc + HikariCP baseline: the identical point select,
  pool size, and concurrency through the idiomatic JDBC stack, so the FFM path is compared against the
  alternative rather than against itself.

Point `MONOLITH_TEST_CONNINFO` at a database; each creates and seeds its own table.

## Findings

A representative local run (Postgres 18, 8 threads, 16-connection pool; absolute numbers vary by
machine, the ratios are the point):

| Path | µs/op | relative |
|---|---|---|
| FFM idiomatic (`execParamsBinary`, re-parses each call) | ~487 | 3.7x slower |
| FFM prepared (`execPrepared`, plan reused) | ~138 | ~tie |
| pgjdbc + HikariCP (prepared) | ~133 | baseline |

Two conclusions, both honest:

1. **The FFM transport is not the bottleneck.** When both reuse a prepared plan, the FFM path ties
   pgjdbc (~138 vs ~133 us/op). The native path itself is competitive; the transport decision holds.
2. **The pool's reset policy is the real lever.** `PgPool` sends `DISCARD ALL` on release, so a
   server-prepared statement cannot survive a checkout, and the generated `execParamsBinary` path
   re-parses the SQL every call. That, not FFM, is the entire ~3.5x gap. The optimization worth chasing
   is prepared-plan reuse across checkouts (a prepared-aware lease, a per-connection statement cache, or
   a reset that keeps prepared statements), not a change of transport.

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
