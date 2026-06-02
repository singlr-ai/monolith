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

- [ ] **1. WAL replication-slot lifecycle.** The single highest-severity operational risk: a stalled or
  orphaned logical slot retains WAL unboundedly, fills the disk, and takes Postgres down. Needs slot
  health monitoring (retained WAL, `active`, `wal_status`), bounded retention guidance, orphan-slot
  cleanup, lost-slot detection and recovery (re-snapshot), and alerting through the observability seam.
- [ ] **2. FFM / native-memory safety.** Arena / `MemorySegment` lifecycle bugs crash the JVM (SIGSEGV),
  not throw catchable exceptions, and are invisible to `Result`. Needs long-running soak and concurrent
  stress, native-memory leak watching, and an audit of every Arena boundary on the libpq path.
- [ ] **3. Reactive correctness under failure.** A missed invalidation shows stale data (in healthcare,
  potentially the wrong record). The at-least-once and snapshot semantics must hold under fault
  injection: slot restart, LSN gaps, connection drops mid-stream, replica failover. Needs property-based
  and chaos testing, not only happy-path integration tests.

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
- [ ] **Benchmark suite** that proves the claims: the FFM binary path's throughput/latency vs JDBC,
  reactive fan-out at N subscribers by M changes/sec, queue throughput, pool under connection storms.
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
