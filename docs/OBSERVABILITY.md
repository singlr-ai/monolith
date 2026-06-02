# Observability

Monolith emits what it does (transactions, pool leases) through a seam you can plug a metrics or
tracing backend into. The seam is deliberately zero-dependency: the core stays pure JDK, and the
third-party libraries (OpenTelemetry, Micrometer) live only in the adapter you choose to add.

## Why a seam, and not "just turn on the OpenTelemetry agent"

OpenTelemetry's Java auto-instrumentation works by hooking well-known libraries, `java.sql` (JDBC)
chief among them. Monolith does not use JDBC: it calls libpq directly over the FFM API. So the agent
sees none of its queries or transactions; attaching it gives you HTTP spans with nothing underneath.
The only way Monolith's work shows up in a trace is for Monolith to emit it, which is what this seam
is for.

## The interface

A `MonolithObserver` is a functional interface that receives a sealed `MonolithEvent`:

```java
public interface MonolithObserver {
  void onEvent(MonolithEvent event);
}
```

The events today:

| Event | When |
|---|---|
| `TransactionCommitted(attempts)` | a `Tx` transaction committed (`attempts` is 1 when it never retried) |
| `TransactionRetried(attempt, sqlState)` | a transient conflict is about to be retried |
| `TransactionRolledBack(attempts, sqlState)` | a transaction rolled back and was not retried |
| `ConnectionLeased(waitNanos)` | the pool handed out a connection after waiting `waitNanos` |
| `PoolExhausted(waited)` | a lease gave up after `waited` with no connection free |

Because the events are a sealed set, an adapter handles them with an exhaustive `switch` and gets a
compile error when a new one is added.

## Installing one

```java
Observability.use(event -> {
  switch (event) {
    case MonolithEvent.TransactionRetried r  -> metrics.counter("monolith.tx.retries").increment();
    case MonolithEvent.TransactionRolledBack r -> metrics.counter("monolith.tx.rollbacks").increment();
    case MonolithEvent.ConnectionLeased l    -> metrics.timer("monolith.pool.wait").record(l.waitNanos(), NANOS);
    case MonolithEvent.PoolExhausted e       -> metrics.counter("monolith.pool.exhausted").increment();
    default -> { }
  }
});
```

Install it once at startup. There is one observer per process; `Observability.reset()` restores the
default.

## It costs nothing when off

Until you install an observer, `Observability.enabled()` is false and the runtime never builds an
event, so the only overhead on the hot path (leasing a connection) is a single reference comparison.
You pay for observability only when you ask for it.

## The adapter pattern

Keeping the core dependency-free is the point, so a real backend goes in its own module that depends
on its own libraries and nothing of yours leaks into the core:

```
monolith-runtime         (pure JDK: defines MonolithObserver + MonolithEvent)
monolith-opentelemetry   (depends on the OpenTelemetry API; maps events to spans + metrics)
monolith-micrometer      (depends on Micrometer; maps events to meters)
```

This is the same shape as `monolith-helidon`: an optional adapter, pulled in only by the app that
wants it, with the core none the wiser. An OpenTelemetry adapter would depend on the OpenTelemetry
**API** only and let your application supply the SDK, exporter, or agent, so even the adapter stays
light.
