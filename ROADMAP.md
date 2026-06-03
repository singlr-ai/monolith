# Roadmap: from v0.2 to production-grade

Monolith v0.2 is a feature-complete, 100%-covered, reactive-relational data platform. This document is
the honest path from there to "a healthtech company runs this in production." The guiding truth: **100%
test coverage is necessary, not sufficient.** Coverage proves the code does what the tests say against
one healthy local Postgres. Production-readiness is proven by running it *broken* (load, soak, chaos),
by owning the operational footguns, and by committing to stability.

## Two bars, not one

- **(A) A known company runs it, with the maintainer owning operations.** Achievable soon, because the
  maintainer controls both sides (builds the library and runs the engagement). Bar: the needed features
  are battle-tested, the footguns are handled, the compliance gate is closed, and someone owns the
  on-call risk.
- **(B) Arbitrary companies adopt it in production.** A higher, longer bar: API stability to 1.0, broad
  battle-testing, a contributor base and governance, ecosystem integrations, security audits, a track
  record, and support. **(B) is earned by doing (A) well and publicly.**

Aim at (A) first. The fastest route to production-grade is to run a *bounded* first module on a real
workload with the maintainer operating it, then harden from what breaks, and expand.

## Now: kill the existential risks

These cause outages or wrong data. In regulated/healthcare use that is unacceptable, and they are the
most differentiating and least-proven parts of the design. None are covered by the current unit/IT
suite, which runs against a single healthy Postgres.

- [x] **1. WAL replication-slot lifecycle.** Done: `Wal.health` / `SlotHealth` (retained WAL, `active`,
  `wal_status`, `isLost`), a `SlotMonitor` that alerts through the observability seam, `Wal.dropInactive`
  orphan cleanup, and `ReactiveHub.invalidateAll` lost-slot recovery; bounded retention via
  `max_slot_wal_keep_size`, documented in the Live Queries runbook.
- [~] **2. FFM / native-memory safety.** Arena / `MemorySegment` lifecycle bugs crash the JVM (SIGSEGV),
  not throw catchable exceptions, and are invisible to `Result`. We keep FFM (see the
  [transport decision](/design/transport/)) and earn its safety by testing it. In progress: `FfmStressIT`
  hammers the binary path under concurrency with verified reads (soak via `MONOLITH_STRESS_SECONDS`), and
  the `soak` Maven profile runs it under a constrained JVM (`-Xmx256m`, native-memory tracking) so a leak
  surfaces here instead of hiding behind a dev box's abundant RAM. The `perf` workflow runs the JMH
  benchmarks, the constrained soak, and a native-memory summary on demand and nightly. The Arena-boundary
  audit is done (see [FFM safety](/design/ffm-safety/)): the result-clear and single-thread discipline
  held, and it hardened the standby-reply flush so backpressure cannot silently stall slot advancement.
  Remaining: wire a longer soak into CI, native-memory leak watching over those runs, fault injection
  (kill the server mid-stream, induce send-buffer backpressure), and a decision on the unused async query
  entry points.
- [~] **3. Reactive correctness under failure.** A missed invalidation shows stale data (in healthcare,
  potentially the wrong record). The at-least-once and snapshot semantics must hold under fault
  injection. Done: connection drops mid-stream and the LSN-gap (lost-slot) case. The `Invalidator`
  reconnects (it used to spin on the dead socket and silently stop), resuming from the slot's confirmed
  LSN and re-querying everyone only when the slot was genuinely lost, and it emits `StreamDropped` /
  `StreamReconnected` so flapping is observable. `ReactiveFaultIT` proves both paths deterministically (a
  clean drop reconnects with `gap == false`; a forced lost slot recovers with `gap == true`), and
  `ReactiveChaosIT` drives a randomized, fixed-seed storm of writes, kills, and slot drops and asserts
  the invariant that the feed always recovers. Remaining: true multi-node replica failover, which needs a
  primary plus replica harness, not a single Postgres.

## Next: close the compliance gate (HIPAA)

A hard regulatory requirement for a healthcare deployment, not a nice-to-have.

- [ ] **KMS adapter.** The `KeyProvider` SPI exists; a real AWS/GCP KMS adapter so the key-encryption key
  never lives in-process.
- [ ] **Read-access audit.** HIPAA logs who *viewed* PHI, not only who changed it. `@Audited` is
  writes-only today.
- [ ] **PHI-safe observability.** The OpenTelemetry adapter (the seam exists) with redaction so PHI never
  reaches a log, trace, or metric label.
- [ ] **Key rotation operationalized** and a documented retention / legal-hold story.

## Next: operability and performance

- [ ] **Observability adapter** (OpenTelemetry / Micrometer) shipped, plus health/readiness checks and
  dashboards for slots, pool, and queue depth.
- [ ] **Runbooks** for the operational risks (slot stall, pool exhaustion, failover, queue backlog).
- [~] **Benchmark suite** that proves the claims. Started: a JMH module (`monolith-benchmarks`, behind the
  `benchmarks` profile) measures the FFM binary path's throughput/latency and parameter encoding. Remaining:
  reactive fan-out at N subscribers by M changes/sec, queue throughput, and the pool under connection
  storms, all run under a constrained JVM rather than on an unbounded dev box. (Done: the JDBC baseline.
  Finding below.)
- [ ] **Prepared-plan reuse.** The JDBC baseline showed the FFM path ties pgjdbc when both reuse a
  prepared plan (~138 vs ~133 us/op), but `PgPool` sends `DISCARD ALL` on release, so a server-prepared
  statement cannot survive a checkout and the generated `execParamsBinary` path re-parses every call,
  running about 3.5x slower than it needs to. A prepared-aware lease or a per-connection statement cache
  closes the gap without changing transport. This is a performance win the benchmark made visible.
- [ ] **Async, non-pinning query dispatch.** The synchronous `execParamsBinary` path pins a virtual
  thread's carrier for the whole query round trip, because an FFM downcall cannot unmount. The async
  primitives already exist (`sendQueryParamsBinary` / `getResult` / `pollReadable`) for a reactor that
  dispatches without blocking, parks the submitting virtual thread, and multiplexes every in-flight query
  on one poll loop, so N concurrent queries cost one reactor thread plus N unmounted virtual threads,
  not N pinned carriers. The scaffolding is in place but unwired and untested; wire the generated query
  path onto it behind a tested reactor, with the same fault injection and soak the synchronous path gets,
  or it stays latent. This is the scalability story a JDBC transport could not tell.
- [ ] **API stability and a road to 1.0.** A semver discipline and a deprecation policy. "The API will
  change" is disqualifying for production; a public 1.0 plan is what lets a company commit.

## Later: adoption and trust (bar B)

- [ ] **A Spring Boot / Quarkus starter.** Most Java shops are Spring; "bring your own main" is real
  adoption friction.
- [ ] **A test-support module** (Testcontainers-based) so users can test their `@PgType` / `@PgQuery`.
- [ ] **`SECURITY.md`** and a disclosure process; signed releases (done: GPG signing in the release
  profile); an SBOM.
- [ ] **Governance and the bus-factor reckoning.** One maintainer is fine for bar (A) with an ownership
  relationship; bar (B) needs contributors, governance, and a commercial-support option.
- [ ] **A reference customer / case study** (the first production engagement becomes the proof for B).

## Sequencing

Build in severity order: the three existential risks first (they cause outages and data corruption),
then the HIPAA gate (a regulatory gate), then operability, benchmarks, and the 1.0 stability plan.
Features wait; *proving it does not break* and *closing the compliance gate* is the work. In parallel,
deploy one bounded module on a real workload so production teaches us what to harden next.
