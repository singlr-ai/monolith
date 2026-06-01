# Scaling Monolith

Monolith builds on Postgres, so it scales the way a Postgres application scales: one primary takes
writes, and you add capacity around it. Some of this is library code you call, and some is deployment
topology you stand up. This page is honest about which is which.

The application nodes are stateless: they hold pools and a `ReactiveHub`, not data. You scale by
running more of them and by scaling Postgres underneath.

## Read replicas (library: `PgReplicaSet`)

Read-heavy workloads scale by routing reads to streaming replicas while writes stay on the primary.

```java
var set = new PgReplicaSet(primaryPool, List.of(replicaPoolA, replicaPoolB));

var writer = set.primary();   // writes, and reads that must be immediately consistent
var reader = set.reader();    // round-robin replica (the primary if there are none)
```

Both arms are a `ConnectionSource`, the same interface `PgPool` implements, so the rest of your code
does not care whether it holds a pool, a replica, or a shard.

**Read-your-writes.** Replicas lag, so a read of your own just-written row from a replica may not see
it yet. When that matters, either read from `set.primary()`, or wait for the replica to replay past
the write's WAL position:

```sql
-- after the write, on the primary:
SELECT pg_current_wal_lsn();
-- before the dependent read, on the replica, poll until true:
SELECT pg_last_wal_replay_lsn() >= '0/1A2B3C4'::pg_lsn;
```

This is an application choice (which reads need it), so Monolith leaves the policy to you rather than
forcing a wait on every read.

## Tenant sharding (library: `ShardRouter`)

For shared-nothing horizontal scale, give each tenant its own database or cluster. No query spans
shards, so there is no cross-shard coordination.

```java
var shards = Map.<String, ConnectionSource>of(
    "shard-a", poolA, "shard-b", poolB, "shard-c", poolC);

var router = ShardRouter.byHash(shards);          // or supply your own tenant -> shard function
var pool   = router.shardFor(tenantId);            // the tenant's shard
```

Pair this with `@Tenant` row-level security and you get both physical isolation (separate shards) and
logical isolation (RLS within a shard).

## More nodes and the reactive feed (deployment)

A logical replication slot is consumed by one process, so when you run N application nodes you have
two options:

- **A slot per node.** Each node runs its own `Invalidator` on its own slot. Simple, no extra moving
  parts; the cost is that each node decodes the WAL independently, so it suits a modest number of
  nodes.
- **A fan-out gateway.** One process tails a single slot and forwards changes to the nodes (over
  `LISTEN`/`NOTIFY` or a message bus); each node's `ReactiveHub` applies what it receives. This keeps
  WAL decoding to one process for a large fleet. The gateway is deployment-specific, not in this repo.

## Failover (operational)

With a single writer, availability rests on promoting a standby when the primary fails. That is an
operational procedure (use Patroni, repmgr, or a managed Postgres): the standby is promoted, writers
repoint to the new primary, and each node recreates its replication slot there to resume the feed.
Monolith's part is small: open new connections and recreate the slot. The automation and fencing are
the platform's job, not the library's.

## What's a library feature vs. topology

| Concern | In this repo |
|---|---|
| Route reads to replicas | `PgReplicaSet` |
| Shard tenants | `ShardRouter` |
| Read-your-writes consistency | documented pattern (your policy) |
| Fan-out to many nodes | slot-per-node works today; a gateway is deployment-specific |
| Failover / promotion | operational (Patroni / repmgr / managed Postgres) |
