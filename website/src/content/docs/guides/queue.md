---
title: "Durable queue"
description: "monolith-queue is a durable, ordered, at-least-once message queue that lives in the same Postgres as your data."
---

`monolith-queue` is a durable, ordered, at-least-once message queue that lives in the same Postgres as
your data. Because you enqueue *inside a transaction*, the message commits atomically with your writes,
which is the property that makes it a reliable **transactional outbox** (handler does an external
effect) and a **durable job queue** (handler does work) with the same machinery. There is no separate
broker to run, deploy, or keep consistent with your database.

See [`/design/queue/`](/design/queue/) for the precise guarantees and the claim algorithm.

## Setup

Create the table once at startup (idempotent):

```java
var conn = pool.lease().getOrThrow();
try {
  Queue.install(conn).getOrThrow();
} finally {
  pool.release(conn);
}
```

## Producing: enqueue inside your transaction

```java
Tx.tx(conn, c -> {
  // ... your domain writes (create the order, debit the account) ...
  return Queue.enqueue(c, Message.builder()
      .withTopic("eligibility.requested")
      .withKey(patientId.toString())     // per-key ordering (optional)
      .withIdempotencyKey(requestId)     // producer dedup (optional)
      .withPayload(payloadBytes)         // opaque bytes you serialize
      .build());
});
// the message exists if and only if the writes committed: no dual-write window
```

- **Ordering.** Messages with the same `(topic, key)` are delivered one at a time, in order. Different
  keys (or no key) run in parallel. Key by the entity an effect concerns (a patient, an account) and
  you get per-entity causal order with cross-entity throughput.
- **Idempotency.** A repeated enqueue with the same `idempotencyKey` is a no-op, so a retried request
  handler does not produce a duplicate message.
- **Scheduling.** `withRunAt(instant)` delivers no earlier than that time (a delay, a reminder).

## Consuming: a worker

```java
Worker worker = Queue.worker(pool, "eligibility.requested")
    .withConcurrency(16)
    .onMessage((c, message) -> {
      // your work; c is a leased connection. Return success to acknowledge,
      // failure (or throw) to retry, then dead-letter once attempts run out.
      return submitEligibilityCheck(message.payload());
    })
    .start();

// ... on shutdown ...
worker.close(); // stops claiming and waits for in-flight handlers to finish
```

Run as many workers as you like, in one process or many, against the same topic; `SKIP LOCKED` keeps
them from colliding, with no leader election. A worker wakes immediately on an enqueue (via
`LISTEN`/`NOTIFY`) and falls back to its poll interval for scheduled and retried messages.

Tuning: `withLease` (how long a claim is held before a crashed worker's message is reclaimed; the
worker renews it in the background for slow handlers), `withBackoff` (`Backoff.exponential(...)` or
your own), `withPollInterval`.

### At-least-once, and how to reach effectively-once

Delivery is **at-least-once**: a worker that crashes after the effect but before acknowledging will
redeliver. Make handlers idempotent, keyed on `message.id()` (use it as the idempotency key to the
external system: Stripe's `Idempotency-Key`, a `WHERE NOT EXISTS` guard, and so on).

For a handler whose effect is **purely database writes**, ask for exactly-once within Postgres:

```java
Queue.worker(pool, "topic").withTransactionalAck(true)
    .onMessage((c, m) -> insertProjection(c, m))   // this write and the ack commit together
    .start();
```

With `withTransactionalAck(true)` the handler's writes and the acknowledgement commit in one
transaction, so the message cannot be acknowledged without the write, nor the write applied twice.

## Dead letters

A message that fails its whole attempt budget is moved to the dead-letter state, not dropped:

```java
List<DeadMessage> dead = Queue.deadLetters(conn, "topic", 100).getOrThrow(); // inspect (id, error, ...)
Queue.replay(conn, deadId).getOrThrow();      // once fixed, return it to pending with fresh attempts
int purged = Queue.purgeSucceeded(conn, "topic", Duration.ofDays(7)).getOrThrow(); // retention
```

Note: strict per-key ordering means a poison message at the head of a key blocks that key until it
succeeds or dead-letters. Size `maxAttempts` accordingly and watch the dead-letter count.

## Observability

The queue emits `QueueEvent.Enqueued`, `Succeeded`, `Retried`, and `DeadLettered` through the same
[`Observability`](/guides/observability/) seam as the runtime (they extend `MonolithEvent.Extension`), so a
single installed observer sees both:

```java
Observability.use(event -> {
  switch (event) {
    case QueueEvent.DeadLettered d -> metrics.counter("queue.dead", "topic", d.topic()).increment();
    case QueueEvent.Succeeded s    -> metrics.counter("queue.ok", "topic", s.topic()).increment();
    default -> { }
  }
});
```

Enqueue rate against success rate is your throughput; dead-letter rate is your trouble. It costs
nothing when no observer is installed.

## Composing with compliance

- **PHI in payloads.** Encrypt the bytes before `enqueue` (the same key you use for `@Encrypted`
  fields), so the queue stores ciphertext like every other table.
- **Tenancy.** Put the tenant in the payload or add a tenant column with forced row-level security so
  a worker only ever sees its tenant's messages.
- **Audit.** Terminal rows plus `attempts` and `last_error` are already a durable record of every
  effect attempted, when, how many times, and how it ended.

## Operational notes

- Each worker holds one connection for its `LISTEN`, plus transient connections for claiming,
  handling, and the heartbeat. Size the pool for `concurrency` handlers in flight plus a small margin.
- Keep payloads small (ids and a discriminator, not blobs); reference large data, do not embed it.
- Topic names become part of a `NOTIFY` channel; use plain identifiers (avoid `"`).