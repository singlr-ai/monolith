---
title: "Decision: FFM/libpq, not JDBC"
description: Why Monolith binds libpq directly through the Java FFM API instead of using a JDBC driver, and how we de-risk the native-memory cost rather than retreat from it.
---

A recurring question: would Monolith work on a JDBC driver instead of binding libpq through the Java
Foreign Function and Memory (FFM) API? This records the analysis and the decision.

## The honest analysis

**Most of the library is transport-agnostic and would port cleanly.** Migrations, transactions, the
durable queue, access control (it is just SQL and row-level security), `@Encrypted` (pure JCE),
observability, and even the reactive WAL tailing all sit on top of a small "run SQL / stream changes"
surface. pgjdbc has a logical-replication API (the one Debezium uses) and `LISTEN`/`NOTIFY`, so the
reactive engine, the codegen declaration model, and the operational layers survive a transport change.

**One thing changes fundamentally, and it is the headline.** A `@PgType` generates a zero-copy binary
reader over the raw libpq tuple bytes (a `MemorySegment`), and the TypeScript client reads the
**identical** binary layout. JDBC hands you a `ResultSet`, not raw bytes, so on JDBC the generated
reader would read `ResultSet.getXxx()`, the zero-copy performance would be gone, and the
"the browser reads the same wire layout" property would disappear (you would serialize for clients
separately). `PgParam`'s binary encoding would become `PreparedStatement.setXxx`.

**The trade-off is real and points both ways:**

| | FFM / libpq (current) | JDBC / pgjdbc |
|---|---|---|
| Identity | binary wire layout shared with the TypeScript client; zero-copy reads | a `ResultSet` abstraction; serialize separately for clients |
| Performance | the binary path, no per-row object churn | the driver's parsing and allocation |
| Dependencies | pure JDK, no third-party runtime | a mature, ubiquitous driver dependency |
| Safety | `Arena` / `MemorySegment` lifecycle bugs crash the JVM (SIGSEGV), not throw | memory-safe; no native crashes |
| Familiarity | bleeding-edge; fewer engineers know FFM; harder to debug | every Java shop knows it |

The sharpest point: **JDBC would eliminate the native-memory-safety risk entirely.** That is a genuine
argument, and it is why this decision deserves to be written down rather than assumed.

## The decision: keep FFM

We bind libpq through FFM, and we treat it as the primary, long-term transport.

**Why.** The binary wire layout, shared byte-for-byte with the TypeScript client, and the zero-copy
read path are not incidental; they are what make this library distinct, and they are downstream of
having the raw bytes that only the FFM/libpq path gives. Retreating to JDBC would buy safety at the cost
of the thing that makes Monolith worth building instead of reaching for one of the many competent
JDBC-based options. "It is safe today" is not a reason to stop pushing toward a better design; the
discipline is in recognizing when a push is worth making, and this one is.

**How we de-risk, instead of avoiding.** The honest answer to a native-memory risk is not to route
around it; it is to test the hell out of it until it is proven. So we treat FFM safety as something to
*earn* through engineering, not dodge:

- A sustained, high-concurrency stress and soak harness that hammers the libpq path (`execParamsBinary`,
  binary result reads, the pool) and asserts correctness, not just absence of a crash, so a use-after-
  free or a cross-`Arena` read surfaces under load rather than in production.
- Native-memory leak watching over long runs.
- A deliberate audit of every `Arena` boundary on the libpq path: one confined `Arena` per operation,
  results consumed before the arena closes, nothing shared across threads.
- Fault injection (kill the server mid-stream, drop connections) layered on top.

## When we would reconsider

Intellectual honesty, not dogma. We would revisit if: sustained soak testing surfaced native-memory
bugs we could not root-cause and fix; the performance or shared-layout advantage failed to materialize
in benchmarks; or a specific regulated deployment judged the SIGSEGV risk unacceptable regardless. In
that last case a JDBC-backed transport could ship as an *optional* alternative behind the same codegen
declarations, the parts that are transport-agnostic, while FFM remains the default. But that is a
fallback to keep in view, not the plan. The plan is to make the FFM path provably robust.
