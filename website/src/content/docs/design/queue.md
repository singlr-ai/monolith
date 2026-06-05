---
title: "Design: the transactional durable queue"
description: "Status: implemented. The rationale and contract behind monolith-queue."
---

**Status: implemented** (`monolith-queue`: `Queue`, `Worker`, `Message`; see `QueueIT` / `WorkerIT` /
`DeliveryIT`). This page is the design rationale and contract behind the feature, not a proposal. It is
the one general primitive underneath the "transactional outbox" and "durable jobs" patterns: a durable,
ordered, at-least-once message queue that lives in the same Postgres as your data, so enqueuing composes
atomically with your transactions.

## 1. Purpose and scope

Every backend that changes a database **and** causes an effect (call an API, send a message, run a
job) has the *dual-write problem*: the two are not one transaction, so a crash leaves them
inconsistent. The fix is to record the intent durably **inside the same transaction** as the data
change, then deliver it afterward. This primitive provides that, and nothing more.

**In scope (generic, belongs in a data library):**
- Enqueue a message inside a caller's transaction, atomic with their writes.
- Durable, at-least-once delivery to a handler, with per-key ordering.
- Claiming across many workers (`SKIP LOCKED`), retries with backoff, dead-lettering.
- Scheduled visibility (`runAt`), crash recovery (leases), producer idempotency.
- Low-latency dispatch via `LISTEN`/`NOTIFY`, falling back to polling.

**Out of scope (application or another tool's job):**
- The handlers and the effects themselves (calling Healthie, Twilio, Stripe).
- Multi-step workflows, sagas, compensation, a workflow DSL (that is Temporal's job).
- Cron/business scheduling policy beyond "deliver at time T".
- Exactly-once *external* effects (physically impossible; we give at-least-once + idempotency tools).

The litmus test this passes and a workflow engine fails: it is generic, it is "just Postgres," and it
**must** live in the data layer because the atomic enqueue cannot be correct anywhere else. It also
composes with the primitives already here: `Tx` (atomic enqueue), the pool (`ConnectionSource`), the
`LISTEN`/`NOTIFY` machinery in `Pg`, the observability seam, and `@Audited`/`@Encrypted`/`@Tenant`.

## 2. Guarantees (precise)

1. **Atomic enqueue.** A message enqueued inside `Tx.tx(conn, ...)` is committed if and only if the
   surrounding writes commit. No message without the state change; no state change without the message.

2. **At-least-once delivery.** Every committed message is delivered one or more times. A message is
   delivered more than once only across a crash window (a worker dies after the effect but before the
   acknowledgement). Handlers must therefore be idempotent; we make that easy (section 7). We do not
   claim exactly-once for external effects, because no system can.

3. **Per-key ordering.** Each message carries an optional `key`. Messages with the same `(topic, key)`
   are delivered in enqueue order, **one at a time**: a message is not delivered until the previous
   message for that key has reached a terminal state (succeeded or dead). Messages with different keys,
   or a `null` key, are delivered concurrently. So you get per-entity causal order (all effects for
   patient X in order) while parallelizing across entities.

   **The honest caveat.** "Enqueue order" is the order the `id` sequence is assigned, which equals
   commit order only when same-key enqueues do not overlap in time. The canonical use is safe: when
   the effect is tied to a change of an entity you also write in the transaction (you hold that row's
   lock), same-key enqueues are serialized by that lock, so commit order equals `id` order. If two
   concurrent transactions enqueue the same key without serializing on a shared row, their relative
   order is their commit order, which may differ from `id` order. Strict order under concurrent same-key
   producers requires serializing them (the row lock you already hold for correctness). A future
   "strict mode" could derive order from the WAL commit sequence (LSN); not in v1.

4. **No loss.** A message is never silently dropped. It is delivered until it succeeds or is moved to
   the dead-letter state after its attempt budget, where it stays for inspection and replay.

## 3. Data model

One table; multiple logical queues share it via `topic`. Terminal rows are kept (an effect audit
trail) and purged or archived on a retention policy.

```sql
CREATE TABLE monolith_queue (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  topic        text        NOT NULL,
  msg_key      text,                              -- ordering key; NULL = unordered, fully parallel
  payload      bytea       NOT NULL,              -- opaque; the caller serializes
  idem_key     text,                              -- producer dedup; NULL = no dedup
  metadata     jsonb       NOT NULL DEFAULT '{}', -- tenant, actor, trace context
  status       text        NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'succeeded', 'dead')),
  attempts     int         NOT NULL DEFAULT 0,
  max_attempts int         NOT NULL,
  run_at       timestamptz NOT NULL DEFAULT now(),-- visible-after (schedule / backoff)
  lease_until  timestamptz,                       -- crash-recovery lease; NULL = not in flight
  last_error   text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

-- hot claim path: only pending rows, ordered within a key
CREATE INDEX monolith_queue_claim ON monolith_queue (topic, msg_key, id) WHERE status = 'pending';

-- producer idempotency: a repeated enqueue is a no-op
CREATE UNIQUE INDEX monolith_queue_idem ON monolith_queue (topic, idem_key) WHERE idem_key IS NOT NULL;

-- retention / dead-letter scans
CREATE INDEX monolith_queue_status_updated ON monolith_queue (status, updated_at);
```

Notes:
- **In-flight is `status = 'pending'` with an active `lease_until`,** not a separate status. This keeps
  ordering simple: an in-flight message still counts as "an earlier pending message" that blocks its
  successors, which is exactly the behavior we want.
- The partial index keeps the hot index small even as succeeded rows accumulate.
- Payloads should be small (ids and a discriminator, not blobs); large bytea TOASTs and slows scans.

## 4. The claim algorithm

The heart of it. One statement claims a batch, respecting ordering, skipping locked rows, reclaiming
expired leases, and counting the attempt:

```sql
WITH due AS (
  SELECT q.id
  FROM monolith_queue q
  WHERE q.topic = $1
    AND q.status = 'pending'
    AND q.run_at <= now()
    AND (q.lease_until IS NULL OR q.lease_until < now())      -- not currently in flight
    AND (q.msg_key IS NULL OR NOT EXISTS (                    -- and it is the head of its key
          SELECT 1 FROM monolith_queue earlier
          WHERE earlier.topic  = q.topic
            AND earlier.msg_key = q.msg_key
            AND earlier.status  = 'pending'
            AND earlier.id      < q.id))
  ORDER BY q.id
  FOR UPDATE SKIP LOCKED
  LIMIT $2                                                    -- batch size
)
UPDATE monolith_queue q
SET lease_until = now() + ($3 || ' seconds')::interval,      -- the lease
    attempts    = q.attempts + 1,
    updated_at  = now()
FROM due
WHERE q.id = due.id
RETURNING q.id, q.payload, q.msg_key, q.idem_key, q.metadata, q.attempts, q.max_attempts;
```

Why it is correct:
- **Ordering.** For a non-null key, a message is claimable only when no earlier message for that key is
  still `pending`. An in-flight earlier message is `pending` (it carries a lease), so it blocks its
  successors; a not-yet-due earlier message (backing off) is also `pending` and blocks them too. So a
  key advances strictly one message at a time, and a failing head holds its key until it succeeds or
  dead-letters. A `null` key skips this clause entirely (the fast, fully-parallel path).
- **Concurrency.** `FOR UPDATE SKIP LOCKED` lets many workers claim disjoint rows with no contention.
  Two workers cannot both get the same row; a worker simply skips rows another holds.
- **Crash recovery.** The lease (`lease_until`) makes a message reclaimable after a worker dies. No
  separate reaper process: the next claim picks up expired leases.
- **Poison-pill protection.** `attempts` is incremented **at claim time**, not at failure, so a message
  that repeatedly crashes its worker still exhausts its budget and dead-letters, instead of looping
  forever. The cost is that a crash after a successful effect counts as an attempt; size `max_attempts`
  accordingly and keep handlers idempotent.

After the handler runs, the worker closes the message out:
- success: `status = 'succeeded', lease_until = NULL`
- retry (`attempts < max_attempts`): `status = 'pending', lease_until = NULL, run_at = now() + backoff, last_error = ...`
- exhausted: `status = 'dead', lease_until = NULL, last_error = ...`

## 5. Delivery lifecycle

```
                 enqueue (in caller's Tx)
                          |
                          v
   run_at>now()?  --yes-->  [scheduled]  --time-->  [pending, due]
                          |                              |
                          +---------- no ----------------+
                                                         | claim (lease, attempts++)
                                                         v
                                                   handler runs
                                            success / failure / crash
                          +-------------------+--------------------+
                          v                   v                    v
                     [succeeded]      attempts<max ? retry    crash: lease
                                       (pending, backoff)     expires -> reclaim
                                            |
                                       attempts=max
                                            v
                                        [dead] --replay--> [pending]
```

## 6. Dispatch

- **Wakeup.** `enqueue` issues `pg_notify` on a per-topic channel; workers `LISTEN` and wake
  immediately, so steady-state latency is a notify round-trip, not a poll interval. Monolith already
  has the `LISTEN`/`NOTIFY` and socket-poll primitives in `Pg`.
- **Safety poll.** Workers also poll on an interval, because notifications are not durable (a notify
  fired while no one listened is lost) and because scheduled (`runAt`) messages have no notify. The
  poll is the backstop; `NOTIFY` is the optimization.
- **Concurrency.** A worker runs up to N handler invocations at once on virtual threads (JDK 25),
  claiming in batches sized to fill its free slots. Per-key ordering caps per-key concurrency at one
  for free.
- **Horizontal scale.** Run as many worker processes as you like against the same table; `SKIP LOCKED`
  makes that safe and contention-free. No leader election, no partition assignment.
- **Graceful stop.** `close()` stops claiming and lets in-flight handlers finish; anything still
  running when the process dies is redelivered after its lease expires.

## 7. Idempotency (the two halves of "effectively once")

- **Producer dedup.** An optional `idempotencyKey` on enqueue, backed by the unique partial index, makes
  a repeated enqueue (a retried request handler) a no-op instead of a duplicate message.
- **Handler idempotency.** Because delivery is at-least-once, the handler must tolerate re-execution.
  We make it easy: the handler receives the **stable message `id`**, which it uses as the idempotency
  key to the external system (e.g. Stripe's `Idempotency-Key`, or a `WHERE NOT EXISTS` guard for a DB
  effect). At-least-once delivery plus an idempotent handler equals effectively-once.

## 8. The delivery-transaction decision (please weigh in)

There is one real fork in how the worker acknowledges a message, and it changes the guarantee:

- **Default: acknowledge in a separate transaction.** The handler runs (using its own leased
  connection if it needs one), returns a `Result`, and the worker then marks the message succeeded in a
  short separate transaction. Uniform, never holds a transaction open across slow IO, correct for the
  dominant case (external effects). Guarantee: at-least-once.

- **Opt-in: acknowledge in the handler's transaction.** For a handler whose effect is purely database
  writes, the worker wraps `handler + acknowledge` in **one** `Tx`, so the work and the ack commit
  together. This gives **exactly-once for database-side effects** (the outbox to inbox chain becomes
  exactly-once entirely within Postgres), at the cost of holding a transaction for the handler's
  duration, so it suits short, DB-only handlers.

**Decision: both, in v1.** The separate-transaction ack is the default; a worker flag
(`ackInHandlerTransaction`) switches to the transactional mode for DB-only handlers, giving
exactly-once within Postgres. That property is unique to living in the data layer and is worth the
extra surface for finance and health.

## 9. API sketch (Java)

Producer, inside a transaction:

```java
Tx.tx(conn, c -> {
  // ... domain writes ...
  return Queue.enqueue(c, Message.builder()
      .withTopic("eligibility.requested")
      .withKey(patientId.toString())        // per-key ordering (optional)
      .withIdempotencyKey(requestId)        // producer dedup (optional)
      .withRunAt(Instant.now().plus(...))   // schedule/delay (optional; default now)
      .withMaxAttempts(25)                  // optional; sane default
      .withPayload(bytes)
      .build());                            // -> Result<Long> (the message id)
});
```

Consumer, a worker:

```java
Worker worker = Queue.worker(source /* ConnectionSource */, "eligibility.requested")
    .concurrency(16)
    .lease(Duration.ofMinutes(2))                                  // > slowest handler
    .backoff(Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(10)))
    .pollInterval(Duration.ofSeconds(5))
    .onMessage(msg -> {                                            // msg: id, attempt, payload, key, idemKey, metadata
      return doTheEffect(msg);                                     // Result<Void>: success acks, failure retries/dead-letters
    })
    .start();                                                      // virtual-thread loop; LISTEN + poll
// ... later ...
worker.close();                                                    // graceful drain
```

Operations:

```java
Queue.deadLetters(conn, "eligibility.requested", limit);          // inspect
Queue.replay(conn, messageId);                                    // dead -> pending
Queue.purgeSucceeded(conn, "eligibility.requested", olderThan);   // retention
```

## 10. Composition with the rest of Monolith

- **`Tx`** is the atomic enqueue. A handler that itself enqueues more messages chains the outbox
  naturally (one effect fans out to others, each atomic).
- **Observability seam** gains queue events: `QueueEnqueued`, `QueueDelivered(attempt)`,
  `QueueSucceeded`, `QueueRetried(attempt, error)`, `QueueDeadLettered`. Queue depth and dead-letter
  count are the two metrics to watch; both fall out of the table.
- **Trace context.** `enqueue` captures the current trace/causation context into `metadata`; the worker
  restores it before invoking the handler and the seam, so an async effect links back to the request
  that caused it. The carrier is pluggable (no-op by default; the OpenTelemetry adapter populates it).
- **`@Tenant`.** The queue table can carry a tenant column with forced RLS, so a worker only ever sees
  its tenant's messages; or a single shared queue with the tenant in `metadata`. Document both; default
  to `metadata` and let strict deployments add RLS.
- **`@Encrypted`.** Payloads may carry PHI; the caller encrypts the bytes before enqueue (or a payload
  codec hook does it), so the queue stores ciphertext like every other table.
- **`@Audited`.** Terminal rows plus `attempts`/`last_error` are already an audit trail of every effect:
  what was attempted, when, how many times, and how it ended. Pair with the audit table for the
  who/why.

## 11. Failure modes, enumerated

| Failure | Outcome |
|---|---|
| Worker crashes before the handler runs | Lease expires; message reclaimed and delivered. |
| Worker crashes after the effect, before ack | Lease expires; redelivered (at-least-once); handler idempotency makes it safe. |
| Handler returns failure | Retried with backoff until `max_attempts`, then dead-lettered. |
| Poison message (always fails/crashes) | Attempts counted at claim, so it dead-letters instead of looping; its key is then unblocked. |
| Duplicate enqueue (producer retried) | `idempotencyKey` unique index makes the second a no-op. |
| Two workers race for one message | `SKIP LOCKED` guarantees only one claims it. |
| Concurrent same-key enqueues, not serialized | Delivered in commit order, which may differ from `id` order (section 2 caveat). |
| Handler exceeds the lease | Message may be redelivered while still running; set `lease` above your slowest handler, or extend it. |
| Clock skew between app servers | None: all time comes from Postgres `now()`, one clock. |
| Succeeded rows accumulate | `purgeSucceeded` / archival on a retention policy; partial index keeps the hot path fast regardless. |

## 12. Packaging and open questions

- **Module.** `monolith-queue`, depending on `monolith-runtime` (it needs `Tx`, the pool, and `Pg`'s
  `LISTEN`/`NOTIFY`). Keeps `monolith-runtime` lean and the queue opt-in, mirroring how
  `monolith-reactive` and `monolith-helidon` are separate.
- **Decisions (resolved):**
  1. Ack: separate-ack default **and** opt-in transactional mode, both in v1 (section 8).
  2. Packaging: its own `monolith-queue` module.
  3. Lease extension / handler heartbeat: **in v1** (a long handler can extend its lease mid-run).
  4. Strict-order-via-WAL: **design the hook now** (a seam for LSN-ordered delivery), implement later.

## 13. Build order (once the contract is agreed)

1. Schema + `enqueue` (transactional, idempotency) + the claim query, with an integration test that
   proves atomic-with-`Tx` and `SKIP LOCKED` exclusivity.
2. `Worker`: claim loop, handler invocation, success/retry/dead-letter transitions, backoff, virtual
   threads, graceful stop. Per-key ordering test (same key strictly serial, different keys parallel).
3. `NOTIFY` dispatch + poll backstop; crash-recovery (lease expiry) test.
4. Dead-letter inspect/replay, `purgeSucceeded`, observability events.
5. Docs (`/guides/queue/`) and the compliance composition notes.

Each step gated at 100% line and branch like the rest of the library, proven against real Postgres.