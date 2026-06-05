---
title: "Design: access control as generated row-level security"
description: "Status: implemented. The rationale and contract behind @AccessControlled."
---

**Status: implemented** (`@AccessControlled`, `Grants`, and the generated row-level-security policies;
see `GrantsIT` / `PgTypeProcessorTest`). This page is the design rationale and contract behind the
feature, not a proposal. A unified access model — RBAC, ACLs (relationship-based), and consent — all
**enforced inside Postgres as row-level security**, generated from declarations, with a pure-JDK runtime.

## 1. The thesis: enforce in the database, not in the app

Monolith already takes a position with `@Tenant`: row-level security is **forced** in Postgres, so
isolation holds even against a forgotten `WHERE`, a buggy service, or the table owner. Access control
must keep that position. The decision belongs in the database, on every query, where no application
code path can route around it.

This is why we do **not** adopt a runtime policy evaluator like Google's CEL:

- **Architecture.** CEL parses and evaluates expressions **in the JVM**, per object. That moves
  enforcement back into application code, the exact thing forced RLS exists to avoid. A single handler
  that forgets to call the evaluator leaks data. RLS cannot be forgotten.
- **Dependencies.** cel-java pulls in Guava, Protocol Buffers, and RE2/J, against Monolith's pure-JDK
  rule.

The right shape, when we need an expression language at all, is **compile to SQL** (parameterized,
push-down), exactly what `singlr-ai/scim-sql` does for SCIM filters with an ANTLR grammar. We compile
policy to RLS; we never evaluate it in the app.

## 2. One mechanism: a unified grant model (Zanzibar-shaped, in Postgres)

Rather than separate systems for roles, ACLs, and ownership, there is **one** primitive: a grant that
links a **principal** to a **resource** with a **relation** and an **effect**. RBAC, instance ACLs,
and consent denials are all expressed as grants, and a generated RLS policy enforces them.

```sql
CREATE TABLE monolith_grant (
  principal    text NOT NULL,   -- an actor id, or a role name (see roles below)
  resource     text NOT NULL,   -- resource type, e.g. 'patient'
  resource_id  text NOT NULL,   -- a specific row id (as text), or '*' for every row of the type
  relation     text NOT NULL,   -- 'owner','editor','viewer','care_team','guardian', ...
  effect       text NOT NULL DEFAULT 'allow' CHECK (effect IN ('allow','deny')),
  PRIMARY KEY (principal, resource, resource_id, relation, effect)
);
```

How the four columns cover everything:

- **ReBAC / ACL** (row level): `('alice','patient','<uuid>','care_team','allow')`, Alice is on this
  patient's care team.
- **RBAC** (type level): `('clinician','patient','*','viewer','allow')`, the `clinician` role may view
  every patient. The `'*'` resource id is the bridge from instance grants to role-wide grants.
- **Ownership**: the app writes an `owner` grant for the creator when a row is created (app policy, not
  magic).
- **Consent / denial**: `('mom','patient','<uuid>','guardian','deny')`, an explicit block that
  overrides any allow (section 5).

Roles are one more indirection, a principal can be an actor **or** a role the actor holds:

```sql
CREATE TABLE monolith_role_member (actor text NOT NULL, role text NOT NULL, PRIMARY KEY (actor, role));
```

## 3. The generated RLS policy

`@AccessControlled` on a `@PgType` generates a forced policy that consults the grant table for the
current actor's principals (itself plus its roles), allowing a row when an `allow` grant matches and no
`deny` does:

```sql
ALTER TABLE patient ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_access ON patient USING (
  EXISTS (
    SELECT 1 FROM monolith_grant g
    WHERE g.resource = 'patient'
      AND g.resource_id IN (id::text, '*')
      AND g.effect = 'allow'
      AND g.principal IN (
        SELECT current_setting('app.actor', true)
        UNION ALL
        SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true)))
  AND NOT EXISTS (
    SELECT 1 FROM monolith_grant d
    WHERE d.resource = 'patient'
      AND d.resource_id IN (id::text, '*')
      AND d.effect = 'deny'
      AND d.principal IN (
        SELECT current_setting('app.actor', true)
        UNION ALL
        SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true))));
```

- **Read vs write.** Set `@AccessControlled(read = {...}, write = {...})` to map relations to actions:
  the codegen then emits a separate policy per command (`FOR SELECT` keyed on the read relations, e.g.
  `viewer`/`care_team`; `FOR INSERT/UPDATE/DELETE` keyed on the write relations, e.g. `owner`/`editor`),
  so a read-granting relation can never satisfy a write. An action with no relations is fail-closed, and
  an empty `read`/`write` (the default) keeps the simpler relation-agnostic model — a single `FOR ALL`
  policy where any `allow` grant authorizes every command.
- **Fail-closed.** With `app.actor` unset, `current_setting(...,true)` is null, nothing matches, no
  rows. Same posture as `@Tenant`.
- **Composition.** `@Tenant` and `@AccessControlled` both AND into effect: tenant isolation first, then
  grant-based access within the tenant. Each is independent and optional.
- **Performance.** Indexes on `monolith_grant (resource, resource_id, principal, effect)` and
  `monolith_role_member (actor)` make the `EXISTS` checks index lookups; Postgres caches the actor's
  role set within a statement. For very hot paths the actor's roles can instead be denormalized into a
  session setting (`app.roles`) to drop the role join, an opt-in optimization, not the default.

## 4. The runtime surface (pure JDK)

The app manages grants and sets the actor; Postgres enforces. A `Grants` helper mirrors the queue's
style:

```java
Grants.install(conn);                                              // create the grant + role tables
Grants.grant(conn, "alice", "patient", patientId, "care_team");   // allow
Grants.deny(conn, "mom", "patient", patientId, "guardian");       // consent block (deny-wins)
Grants.revoke(conn, "alice", "patient", patientId, "care_team");
Grants.addRole(conn, "alice", "clinician");                       // RBAC membership

PgSession.actor(arena, conn, currentUserId);                      // already exists; the RLS reads it
```

`PgSession.actor` already sets `app.actor` per transaction (injection-safe `set_config`), so no new
session plumbing is needed for the common path.

## 5. Consent and the pediatric / multi-state crux

Adolescent confidentiality is the reason `effect = 'deny'` exists and **deny always wins**. A guardian
may hold a broad `guardian` allow over their child, yet a specific record must be hidden from them in
some states. That is a `deny` grant; the RLS `AND NOT EXISTS (deny ...)` makes it override the allow,
even an org-wide `'*'` allow.

The scope line, stated plainly:

- **Monolith provides the mechanism**: deny-wins, relationship-keyed, enforced as RLS. Correct and
  unbypassable.
- **The application provides the policy**: *which* grants and denials exist, derived from its rules,
  including state-specific minor-consent law. "A 14-year-old's therapy notes are confidential from a
  guardian in CA but not TX" is data the app writes as grants/denials per its compliance team's
  encoding; the library does not hardcode jurisdiction law. We give a correct enforcement primitive and
  the `Grants` API to drive it.

This keeps the library generic (every enterprise needs RBAC + ACLs + deny) while serving the hardest
healthcare case without baking domain law into the core.

## 6. Attribute conditions (ABAC), and the expression-language decision

Some access rules are conditions over a row's own columns plus session attributes, not grants:
`region = current_setting('app.region')`, `status <> 'archived'`, `classification <= current_setting('app.clearance')::int`.

- **v1: a trusted SQL predicate** on `@AccessControlled(where = "...")`, AND-ed into the policy. This is
  **trusted developer SQL**, not a parsed/validated expression language: the codegen applies only
  *structural* checks (it rejects a statement terminator `;`, a SQL comment `--`/`/*`, and unbalanced
  parentheses) and otherwise embeds the string verbatim. It does **not** verify that the predicate
  references only this type's columns, nor does it block subqueries or cross-table reads — those are
  valid (and the connection role then needs the relevant `SELECT`). Treat the `where` value like any
  hand-written policy SQL: review it. This covers the overwhelming majority of ABAC needs with zero new
  machinery.
- **Later, if needed: a policy DSL compiled to SQL**, built in the annotation processor (build time,
  so the parser dependency never reaches the runtime), modeled exactly on `scim-sql`: an ANTLR grammar
  to a parameterized SQL predicate. This is the place a small grammar earns its keep, and the reason we
  studied scim-sql. It is **not** CEL: compile-to-SQL, push-down, no Guava/protobuf, no JVM evaluation.

So the answer to "CEL, ANTLR, or build our own": for RBAC/ReBAC/consent, **no expression language at
all**, it is table-driven RLS. For ABAC, **a validated SQL predicate now**, and **our own
scim-sql-style SQL compiler later** if richer conditions are demanded, never a runtime evaluator.

## 7. Decisions (the three open questions, proposed)

1. **Extend `@Tenant` or a new annotation?** New `@AccessControlled` at the type level, composing with
   `@Tenant`. Tenant isolation and grant-based access are different concerns; AND-ing two independent
   forced policies is cleaner than overloading one annotation.
2. **Flat tuples or hierarchical resolution?** **Flat** `(principal, resource, resource_id, relation,
   effect)`, plus roles as one indirection. Deep hierarchies (org -> team -> patient) are flattened by
   the app into grants (as Zanzibar "expands" relationships offline), not resolved by recursive RLS,
   which is slow and hard to reason about. Flat is index-friendly and covers the cases.
3. **Negative / consent grants?** **Yes, deny-wins** (section 5). It is the pediatric crux and a common
   enterprise need (explicit revocation that overrides inherited access).

## 8. Scope: what the library does and does not do

- **Does:** generate forced RLS for RBAC + ACLs + ABAC + deny-wins consent; provide the grant/role
  tables and a `Grants` API; compose with `@Tenant`/`@Audited`/`@Encrypted`; keep enforcement in
  Postgres and the runtime pure-JDK.
- **Does not:** decide policy (which roles, grants, denials, jurisdiction rules exist), that is the
  app's; evaluate access in the JVM; ship a general policy engine or workflow.

## 9. Scaling

Because both enforcement and policy live in Postgres, access scales the way the data scales, and
inherits the same trade-offs rather than inventing new ones. There is no separate authorization tier.

- **Many app instances.** The app is stateless with respect to access: it sets `app.actor` per
  transaction and Postgres decides. There is **no in-process policy cache to invalidate across nodes**,
  the exact problem an in-JVM evaluator (CEL, OPA) creates. Run any number of instances; they are
  identical. This is a direct dividend of enforcing in the database.
- **Read replicas (`PgReplicaSet`).** Streaming replication copies the RLS policies and the grant and
  role tables, so a read on a replica enforces the same policy against the replica's copy. The caveat
  is **replication lag**: a new grant may be briefly invisible on a lagging replica, and, the
  security-relevant direction, a **revocation or deny is not instant on replicas**. For access where
  hard, immediate revocation matters, route that check to the primary; otherwise the bounded staleness
  matches ordinary replica reads.
- **Tenant shards (`ShardRouter`).** Each shard enforces its own RLS over its own grant table, which is
  correct as long as grants are co-located with the resources they govern, `Grants` writes route to the
  resource's shard by the same tenant key as the data. Resource grants stay local to their shard; only
  a **global role map** (`monolith_role_member`) needs to be present on every shard (a small,
  slowly-changing table to replicate or maintain per shard).

## 10. Build plan (each step gated at 100%, proven against real Postgres)

1. `Grants` + the grant/role schema + the runtime API (grant/revoke/deny/addRole/removeRole), with an
   IT proving the tuples behave.
2. `@AccessControlled` codegen: the forced read/write RLS policies over the grant table, with an IT
   proving a real connection sees only granted rows, denies override allows, and roles work.
3. The validated `where` ABAC predicate.
4. Docs (`/guides/access/`) and compliance-composition notes; the optional DSL->SQL compiler stays a
   documented future module.

## 11. Why this is "best-in-class, or more"

Most libraries give you either an authz evaluator (CEL, OPA) that runs beside the database, or an
ORM-level check that the database does not know about. Monolith pushes a **Zanzibar-shaped unified grant
model down into Postgres RLS**, generated from a declaration, enforced on every query, composable with
tenant isolation, field encryption, and an audit trail, with no runtime dependencies and no second
service to operate. The access decision lives where the data lives.