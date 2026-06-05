# Security

Monolith is **experimental software (v0.x)**. It provides security and compliance _primitives_ in the
database; it is **not** a turnkey HIPAA, SOC 2, or PCI control set. This document states plainly what is
shipped, what is not, and where the responsibility boundary sits, so no one mistakes a building block for
a finished control.

## Reporting a vulnerability

Please report suspected vulnerabilities privately to **security@standardapplied.com** (or open a GitHub
security advisory). Do not file public issues for security reports. We aim to acknowledge within a few
business days. As an experimental project there is no formal SLA yet.

## What is shipped

- **Field encryption (`@Encrypted`).** Envelope encryption for annotated fields, with a pluggable
  `KeyProvider`. Each value carries a fresh data key under AES-256-GCM, and the ciphertext is **bound to
  its field context** (`table.column`) as associated data, so a blob copied to another row/column or
  table fails to decrypt rather than silently substituting (wire version 3; version-2 data stays
  readable). **Default key custody is in-process**: `PgCrypto` initializes a `LocalKeyProvider` that
  reads the data-encryption key from the `MONOLITH_FIELD_KEY` environment variable. This is appropriate
  for development and self-managed deployments that control the process environment; it is **not** an
  HSM/KMS integration.
- **Forced row-level security (`@AccessControlled`, `@Tenant`).** Generates Postgres `FORCE ROW LEVEL
  SECURITY` policies over a unified grant model (allow/deny, roles, resource/`*`). Tenant isolation is
  emitted as a `RESTRICTIVE` policy so it ANDs with the grant check. Policies are keyed on
  `current_setting('app.actor')` / `app.tenant`, which **the application must set** per request/transaction.
- **Write audit trails (`@Audited`).** Append-only audit table plus a write-capturing trigger and an
  immutability guard. This captures **changes** (insert/update/delete), not reads.
- **SQL-identifier safety for replication.** Logical-replication slot and publication names are validated
  against a strict identifier pattern before they reach privileged admin SQL (`Wal.validateSlot`), so a
  slot name cannot inject into or break the replication/admin path.
- **Generated policy SQL escaping.** `@AccessControlled` literal values (e.g. `resource`) are
  SQL-escaped during code generation so an annotation value cannot terminate a string literal or break
  out of a generated policy.

## What is NOT yet shipped

These are tracked in the [roadmap](ROADMAP.md) under "close the compliance gate (HIPAA)". Until they
land, do not represent a deployment as HIPAA-ready on the basis of this library alone:

- **KMS/HSM key custody.** No managed-KMS `KeyProvider` adapter; the default key lives in the process
  environment. Key rotation is not operationalized.
- **Read-access audit.** `@Audited` records writes, not who *viewed* data — which HIPAA requires for PHI.
- **PHI-safe observability.** The telemetry seam exists, but there is no shipped redaction policy to keep
  sensitive values out of traces, metrics, and logs.

## Responsibility boundary (deployer)

- **Set actor/tenant context.** RLS policies are only as strong as the `app.actor` / `app.tenant` session
  settings; a query that forgets to set them will be denied or under-scoped, not silently elevated, but
  the application owns establishing them correctly.
- **Authorize network-facing subscriptions.** The optional Helidon `LiveQueryWsListener` accepts a
  `<QueryName>:<param>` frame. Install an `authorize(session, query, param)` hook (and keep the default
  param-length bound) — without one, any client can subscribe by guessing query names and params.
  Unknown-query and unauthorized rejections are deliberately indistinguishable so clients cannot probe
  which queries exist.
- **Protect `MONOLITH_FIELD_KEY`** and the database/replication credentials as you would any secret;
  the library does not manage their lifecycle.

## Supported versions

Pre-1.0: only the latest published version receives fixes.
