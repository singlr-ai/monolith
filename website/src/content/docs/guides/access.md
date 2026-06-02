---
title: "Access control"
description: "Mark a @PgType @AccessControlled and Postgres enforces, on every query, that the current actor may see a row only if it has been granted access and not den"
---

Mark a `@PgType` `@AccessControlled` and Postgres enforces, on every query, that the current actor may
see a row only if it has been **granted** access and not **denied** it. Authorization runs in the
database as forced row-level security, so it cannot be bypassed by a forgotten `WHERE`, a buggy
service, or the table owner. One grant mechanism covers RBAC, instance ACLs, ownership, and consent.

See [`/design/access/`](/design/access/) for the model and the generated policy in full.

## Setup

Create the grant and role tables once at startup (per shard):

```java
Grants.install(conn).getOrThrow();
```

## Declaring

```java
@AccessControlled @PgType
public record Patient(UUID id, @Tenant UUID org, String name, @Encrypted String ssn) {}
```

The codegen emits forced RLS for the resource (the table name by default; override with
`@AccessControlled(resource = "patient")`). The row is identified by the `id` component
(`@AccessControlled(id = "patientId")` to change it). With `@Tenant` also present, the tenant policy is
emitted as `RESTRICTIVE`, so a row must **both** belong to the tenant **and** be granted.

## Granting (the `Grants` API)

A grant links a **principal** (an actor, or a role an actor holds) to a **resource** with a
**relation** and an effect. The application writes grants; Postgres enforces them.

```java
// Instance ACL: Alice is on this patient's care team.
Grants.grant(conn, "alice", "patient", patientId.toString(), "care_team");

// RBAC: the 'clinician' role may view every patient (ALL is the wildcard resource id).
Grants.grant(conn, "clinician", "patient", Grants.ALL, "viewer");
Grants.addRole(conn, "bob", "clinician");      // Bob now inherits it

// Ownership: grant the creator when you create the row (your policy, in the same transaction).
Grants.grant(conn, creatorId, "patient", patientId.toString(), "owner");

Grants.revoke(conn, "alice", "patient", patientId.toString(), "care_team"); // take it away
Grants.removeRole(conn, "bob", "clinician");
```

## Consent: deny always wins

A `deny` overrides any `allow`, even an org-wide `*` one. This is how a guardian's broad access yields
to a specific confidentiality block:

```java
Grants.grant(conn, "mom", "patient", Grants.ALL, "guardian");   // broad guardian access
Grants.deny(conn, "mom", "patient", teenRecordId.toString(), "guardian"); // ... except this record
```

What grants and denials exist, including jurisdiction-specific minor-consent rules, is your
application's policy. Monolith provides the correct, deny-wins enforcement; you supply the data that
encodes the rule. To lift a block, `revoke` the specific deny (the broader allow remains).

## Setting the actor

The policy reads the actor from the transaction context, set injection-safely per transaction:

```java
Tx.tx(conn, c -> {
  PgSession.actor(arena, c, currentUserId); // who the request is acting as
  return loadPatients(c);                    // sees only granted, non-denied rows
});
```

With the actor unset the policy is **fail-closed**: no rows. For RLS to apply, the application must
connect as a **non-superuser** role (a superuser bypasses RLS); grant that role `SELECT` on the table
and on `monolith_grant` / `monolith_role_member`.

## Attribute conditions (ABAC)

For rules that are conditions over a row's own columns plus session settings, add a `where` predicate,
AND-ed into the policy:

```java
@AccessControlled(where = "status <> 'archived' AND region = current_setting('app.region', true)")
@PgType
public record Document(UUID id, String status, String region) {}
```

It is trusted developer SQL over this table's columns (by their snake_case names) and
`current_setting(...)`. The codegen rejects statement-breaking input (a `;`, a SQL comment, or
unbalanced parentheses) but does not otherwise parse it.

## When a check needs more than the row

Real policies often need information that is not in the row or a grant: a user's membership tier, a
value from another table, something computed in the app. The guiding principle is to **push the check
down as far as it will go**, and there are four rungs.

**1. Attributes of the actor (tier, region, clearance): hydrate into the session.** The app knows who
the actor is and can fetch their attributes once per request (from a users table, a JWT claim, a
cache, even an external service) and inject them. A policy reads them with `current_setting`, so the
per-row check needs no join:

```java
PgSession.actor(arena, conn, userId);
PgSession.set(arena, conn, "app.tier", user.tier()); // hydrated once

@AccessControlled(where = "min_tier <= current_setting('app.tier', true)::int")
```

This is also the bridge from app-layer or external logic into RLS: compute the value however you must,
reduce it to a per-request setting, and the policy consumes it.

**2. Relationships that need traversal: materialize into grants.** Rather than join several tables per
query, compute the relationship when it changes and write a grant; the policy stays an indexed grant
lookup. This is the scalable choice:

```java
// when the user upgrades or joins a team, expand the relationship into grants once:
Grants.grant(conn, userId, "report", Grants.ALL, "viewer");
```

**3. A genuine cross-table join: it is real SQL, so `where` can join.** The `where` predicate is not
restricted to own columns; a subquery is valid:

```java
@AccessControlled(where =
  "EXISTS (SELECT 1 FROM membership m WHERE m.user_id = current_setting('app.actor', true) AND m.tier = 'gold')")
```

Caveats: the connection role needs `SELECT` on the joined table, and a correlated subquery has per-row
cost (`current_setting` is constant within a statement, and indexes help). For hot paths prefer rung 2.
Because the policy is just SQL, you can also add your own hand-written policy alongside the generated
one (Postgres OR-combines permissive policies, AND-combines restrictive ones), or call a
`SECURITY DEFINER` function, `where = "can_access(id, current_setting('app.actor', true))"`, to
encapsulate complex or privileged joins.

**4. A truly external, per-row decision: RLS cannot reach it.** If a check needs a per-row call to app
code or an external service, the database cannot do it. Either reduce it to a per-request value and
hydrate it (rung 1), or do an app-layer post-filter and accept that it is bypassable. The latter is
usually a sign the data belongs in the database.

## Composition

`@AccessControlled` composes with the other declarations on the same type: `@Tenant` (tenant isolation,
emitted `RESTRICTIVE` so it ANDs), `@Encrypted` (encrypted columns are stored as ciphertext and are
not usable in `where`), and `@Audited` (every access-controlled write is still recorded in the audit
trail with the actor that made it).

## Scaling

Because enforcement and policy both live in Postgres, access scales the way your data does, no separate
authorization tier and nothing to invalidate across app instances. The one caveat: on read replicas a
new grant, or a revocation, is only as fresh as replication lag, so for hard, immediate revocation read
the check on the primary. Under sharding, grants co-locate with the resources they govern; only the
global role map is duplicated per shard. Details in [`/design/access/`](/design/access/).