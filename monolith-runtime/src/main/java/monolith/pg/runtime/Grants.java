/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Manages the grant and role tables that the generated {@code @AccessControlled} row-level-security
 * policies consult. A grant links a <b>principal</b> (an actor id, or a role an actor holds) to a
 * <b>resource</b> with a <b>relation</b> and an <b>effect</b> (allow or deny); one mechanism expresses
 * RBAC, instance ACLs, ownership, and consent denials. The application writes grants here; Postgres
 * enforces them on every query (see {@code docs/design/ACCESS.md}). The actor is set per transaction
 * with {@link PgSession#actor}.
 *
 * <p>Use {@link #ALL} as the resource id for a grant that covers every row of a resource type (the
 * bridge from instance grants to role-wide grants). A {@code deny} always wins over an {@code allow}.
 */
public final class Grants {

  /** Resource id that matches every row of a type (a type-wide, typically role-based, grant). */
  public static final String ALL = "*";

  private static final String SCHEMA = """
      CREATE TABLE IF NOT EXISTS monolith_grant (
        principal text NOT NULL,
        resource text NOT NULL,
        resource_id text NOT NULL,
        relation text NOT NULL,
        effect text NOT NULL DEFAULT 'allow' CHECK (effect IN ('allow', 'deny')),
        PRIMARY KEY (principal, resource, resource_id, relation, effect));
      CREATE INDEX IF NOT EXISTS monolith_grant_lookup
        ON monolith_grant (resource, resource_id, principal, effect);
      CREATE TABLE IF NOT EXISTS monolith_role_member (
        actor text NOT NULL, role text NOT NULL, PRIMARY KEY (actor, role));
      CREATE INDEX IF NOT EXISTS monolith_role_member_actor ON monolith_role_member (actor)""";

  private static final String UPSERT_GRANT_SQL = """
      INSERT INTO monolith_grant (principal, resource, resource_id, relation, effect)
      VALUES ($1, $2, $3, $4, $5) ON CONFLICT DO NOTHING""";

  private static final String REVOKE_SQL = """
      DELETE FROM monolith_grant
      WHERE principal = $1 AND resource = $2 AND resource_id = $3 AND relation = $4""";

  private static final String ADD_ROLE_SQL =
      "INSERT INTO monolith_role_member (actor, role) VALUES ($1, $2) ON CONFLICT DO NOTHING";

  private static final String REMOVE_ROLE_SQL =
      "DELETE FROM monolith_role_member WHERE actor = $1 AND role = $2";

  /** Create the grant and role tables if they do not exist. Idempotent; run once at startup per shard. */
  public static Result<Void> install(MemorySegment conn) {
    try (Arena arena = Arena.ofConfined()) {
      return Pg.exec(arena, conn, SCHEMA);
    }
  }

  /** Allow {@code principal} the {@code relation} on a resource ({@link #ALL} for the whole type). */
  public static Result<Void> grant(
      MemorySegment conn, String principal, String resource, String resourceId, String relation) {
    return write(conn, UPSERT_GRANT_SQL, principal, resource, resourceId, relation, "allow");
  }

  /** Deny {@code principal} the {@code relation} on a resource; a deny overrides any matching allow. */
  public static Result<Void> deny(
      MemorySegment conn, String principal, String resource, String resourceId, String relation) {
    return write(conn, UPSERT_GRANT_SQL, principal, resource, resourceId, relation, "deny");
  }

  /** Remove both the allow and the deny for this {@code (principal, resource, resourceId, relation)}. */
  public static Result<Void> revoke(
      MemorySegment conn, String principal, String resource, String resourceId, String relation) {
    return write(conn, REVOKE_SQL, principal, resource, resourceId, relation);
  }

  /** Make {@code actor} a member of {@code role}, so grants to the role apply to the actor. */
  public static Result<Void> addRole(MemorySegment conn, String actor, String role) {
    return write(conn, ADD_ROLE_SQL, actor, role);
  }

  /** Remove {@code actor} from {@code role}. */
  public static Result<Void> removeRole(MemorySegment conn, String actor, String role) {
    return write(conn, REMOVE_ROLE_SQL, actor, role);
  }

  private static Result<Void> write(MemorySegment conn, String sql, Object... params) {
    try (Arena arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, params);
      return Pg.execParamsBinary(arena, conn, sql, p.values(), p.lengths(), p.formats()).map(res -> {
        Pg.clear(res);
        return null;
      });
    }
  }

  private Grants() {}
}
