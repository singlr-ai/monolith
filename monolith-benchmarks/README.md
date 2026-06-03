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
- **`CachedQueryBench`** (needs Postgres) is the generated `@PgQuery` path as it runs today: the point
  select through the `PreparedCache` on a pooled connection (the plan prepared once per connection and
  reused across checkouts).
- **`JdbcQueryBench`** (needs Postgres) is the pgjdbc + HikariCP baseline: the identical point select,
  pool size, and concurrency through the idiomatic JDBC stack, so the FFM path is compared against the
  alternative rather than against itself.
- **`ComplexQueryBench`** (needs Postgres) isolates prepared-plan reuse on a join-and-aggregate query on
  a dedicated connection: re-parse every call vs reuse a plan, with no pool reset to confound it.

Point `MONOLITH_TEST_CONNINFO` at a database; each creates and seeds its own table.

## Findings

A representative local run (Postgres 18, 8 threads, 16-connection pool; absolute numbers vary by
machine, the ratios are the point):

| Path | µs/op |
|---|---|
| pooled point select, before optimization | ~487 |
| pooled point select, after optimization (`CachedQueryBench`) | ~278 |
| dedicated prepared, no pool reset (`PreparedQueryBench`) | ~140 |
| pgjdbc + HikariCP (prepared, no reset) | ~133 |
| complex query, re-parse every call | ~375 |
| complex query, prepared plan reused | ~334 |

The honest conclusions, in the order the benchmark taught them:

1. **The FFM transport is not the bottleneck.** On a dedicated connection the FFM prepared path ties
   pgjdbc (~140 vs ~133 us/op). The transport decision holds.
2. **The pool's reset round trips, not the parse, dominated the pooled point select.** The original ~487
   was two extra round trips on every release (`ROLLBACK` then `DISCARD ALL`). Skipping the rollback when
   the connection is already idle (a client-side `PQtransactionStatus` check) and keeping prepared
   statements and their plans across the reset cut it to ~278, about 1.75x, with no loss of isolation.
3. **Prepared-plan reuse pays where the planner works.** On a trivial point select the parse cost is lost
   in the network round trip, so reuse is invisible (~278 either way). On a join-and-aggregate it is
   ~11% (375 to 334), and on every call it is server CPU not spent re-planning, which is what matters
   under load and for the reactive layer that re-runs the same query.
4. **The last ~140 us to pgjdbc is the price of a safe default.** It is the one session-reset round trip
   `PgPool` still runs for isolation between checkouts, which HikariCP forgoes by default. Monolith keeps
   it; the reset is now as cheap as a safe reset can be.

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
