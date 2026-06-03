---
title: "FFM native-memory safety audit"
description: What we audited on the libpq/FFM path for native-memory correctness, the result, and the hardening it produced.
---

Monolith binds libpq through the Java Foreign Function and Memory API, so a lifecycle bug crashes the
JVM (SIGSEGV) instead of throwing a catchable exception. The [transport decision](/design/transport/)
is to keep FFM and earn its safety by testing and auditing it. This records a deliberate audit of every
`Arena` and `MemorySegment` boundary on the libpq path.

## What was audited

Every call site of the result-bearing and connection-bearing `Pg` methods across all modules, against
four hazards:

1. **Result leaks.** A `PGresult` from `execParamsBinary` / `execPrepared` is libpq-owned native memory
   that must be `PQclear`ed. A missed clear on the success path is a native-memory leak that no `Result`
   reports and a constrained heap would not catch (the leak is off-heap).
2. **Use-after-free.** Reading a result cell (`getbytes`) after the result is cleared, or reading libpq
   buffers after they are freed.
3. **Cross-thread connection use.** A libpq connection is single-threaded; touching one from two threads
   at once is a crash.
4. **Arena lifetime.** A parameter segment must outlive the synchronous libpq call that reads it, and a
   result read must happen before its arena-independent handle is cleared.

## Result

The discipline held. Every `execParamsBinary` / `execPrepared` success path clears its result (in a
`finally`, a `.map` that clears before returning, or an inline clear); every `getbytes` precedes its
`clear`; libpq-allocated buffers (`getCopyData`, `drainNotifications`) are `PQfreemem`d. Connections are
confined to one thread: the pool leases exclusively, and the replication and queue-listen connections
are each owned by a single control thread, while worker handlers and the heartbeat lease their own. The
design is sound by construction: a result handle is libpq-owned, not arena-owned, and libpq copies
parameter bytes during the synchronous call, so an arena closing after the call cannot strand a result.

## Hardening it produced

**Standby-reply flush is no longer fire-and-forget.** The audit found that `sendCopyData` ignored the
`PQflush` return. A standby status reply carries the confirmed LSN that advances the replication slot,
so under high WAL throughput a full send buffer (PQflush returning "would block") could silently drop
the feedback, stall slot advancement, and let WAL accumulate, which is the highest-severity operational
risk in the reactive design. It now follows the canonical libpq async-flush pattern: on a full buffer it
waits for write-readiness and flushes again until the reply is sent or the connection errors.

## Open item

The async query protocol entry points (`sendQueryParamsBinary`, `getResult`, `isBusy`) are defined but
unused. They are scaffolding for a future async reactor that dispatches without blocking so a submitting
virtual thread can unmount instead of pinning its carrier for the query round trip (see
[Async, non-pinning query dispatch](/roadmap) on the roadmap). The decision is to keep them and wire the
generated query path onto them behind a tested reactor, treating them as untrusted native surface until
that work, with its own fault injection and soak, lands.

## What remains

Auditing for correctness is necessary, not sufficient. The native path still needs the sustained
constrained-JVM soak and native-memory leak watching (the `soak` profile and the performance workflow),
and fault injection (kill the server mid-stream, induce send-buffer backpressure) to drive the branches
an audit can only reason about.
