/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Grants;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgSession;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the grant model enforces, against real Postgres: the row-level-security policy from
 * {@code docs/design/ACCESS.md} (which the {@code @AccessControlled} codegen will generate) is applied
 * here by hand so a restricted role sees only the rows it has been granted, deny overrides allow, and
 * role membership grants access. Skips when no Postgres is reachable.
 */
@DisplayName("Grants and access RLS against real Postgres")
class GrantsIT {

  private static final String ADMIN = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  private static final String APP = ADMIN + " user=monolith_acl_role password=monolith";

  private static final String P1 = "11111111-1111-1111-1111-111111111111";
  private static final String P2 = "22222222-2222-2222-2222-222222222222";

  private static final String SETUP = """
      DO $$ BEGIN
        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'monolith_acl_role') THEN
          EXECUTE 'DROP OWNED BY monolith_acl_role';
          EXECUTE 'DROP ROLE monolith_acl_role';
        END IF;
      END $$;
      DROP TABLE IF EXISTS acl_patient;
      CREATE TABLE acl_patient (id uuid PRIMARY KEY, name text NOT NULL);
      ALTER TABLE acl_patient ENABLE ROW LEVEL SECURITY;
      ALTER TABLE acl_patient FORCE ROW LEVEL SECURITY;
      CREATE POLICY acl_patient_access ON acl_patient USING (
        EXISTS (SELECT 1 FROM monolith_grant g
          WHERE g.resource = 'acl_patient' AND g.resource_id IN (id::text, '*') AND g.effect = 'allow'
            AND g.principal IN (SELECT current_setting('app.actor', true)
              UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true)))
        AND NOT EXISTS (SELECT 1 FROM monolith_grant d
          WHERE d.resource = 'acl_patient' AND d.resource_id IN (id::text, '*') AND d.effect = 'deny'
            AND d.principal IN (SELECT current_setting('app.actor', true)
              UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true))));
      DROP TABLE IF EXISTS acl_chart;
      CREATE TABLE acl_chart (id uuid PRIMARY KEY, note text NOT NULL);
      ALTER TABLE acl_chart ENABLE ROW LEVEL SECURITY;
      ALTER TABLE acl_chart FORCE ROW LEVEL SECURITY;
      CREATE POLICY acl_chart_select ON acl_chart FOR SELECT
        USING (
          EXISTS (SELECT 1 FROM monolith_grant g
            WHERE g.resource = 'acl_chart' AND g.resource_id IN (id::text, '*') AND g.effect = 'allow'
              AND g.relation IN ('viewer', 'editor')
              AND g.principal IN (SELECT current_setting('app.actor', true)
                UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true)))
          AND NOT EXISTS (SELECT 1 FROM monolith_grant d
            WHERE d.resource = 'acl_chart' AND d.resource_id IN (id::text, '*') AND d.effect = 'deny'
              AND d.relation IN ('viewer', 'editor')
              AND d.principal IN (SELECT current_setting('app.actor', true)
                UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true))));
      CREATE POLICY acl_chart_insert ON acl_chart FOR INSERT
        WITH CHECK (
          EXISTS (SELECT 1 FROM monolith_grant g
            WHERE g.resource = 'acl_chart' AND g.resource_id IN (id::text, '*') AND g.effect = 'allow'
              AND g.relation IN ('editor')
              AND g.principal IN (SELECT current_setting('app.actor', true)
                UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true)))
          AND NOT EXISTS (SELECT 1 FROM monolith_grant d
            WHERE d.resource = 'acl_chart' AND d.resource_id IN (id::text, '*') AND d.effect = 'deny'
              AND d.relation IN ('editor')
              AND d.principal IN (SELECT current_setting('app.actor', true)
                UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true))));
      CREATE POLICY acl_chart_update ON acl_chart FOR UPDATE
        USING (
          EXISTS (SELECT 1 FROM monolith_grant g
            WHERE g.resource = 'acl_chart' AND g.resource_id IN (id::text, '*') AND g.effect = 'allow'
              AND g.relation IN ('editor')
              AND g.principal IN (SELECT current_setting('app.actor', true)
                UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true)))
          AND NOT EXISTS (SELECT 1 FROM monolith_grant d
            WHERE d.resource = 'acl_chart' AND d.resource_id IN (id::text, '*') AND d.effect = 'deny'
              AND d.relation IN ('editor')
              AND d.principal IN (SELECT current_setting('app.actor', true)
                UNION ALL SELECT role FROM monolith_role_member WHERE actor = current_setting('app.actor', true))));
      CREATE ROLE monolith_acl_role LOGIN PASSWORD 'monolith';
      GRANT SELECT ON acl_patient TO monolith_acl_role;
      GRANT SELECT, INSERT, UPDATE, DELETE ON acl_chart TO monolith_acl_role;
      GRANT SELECT ON monolith_grant TO monolith_acl_role;
      GRANT SELECT ON monolith_role_member TO monolith_acl_role;""";

  private static final Arena ARENA = Arena.ofShared();
  private static MemorySegment admin;
  private static MemorySegment app;
  private static boolean available;

  @BeforeAll
  static void setup() {
    try {
      Result<MemorySegment> a = Pg.connect(ARENA, ADMIN);
      if (a.isFailure()) return;
      admin = a.getOrThrow();
      Grants.install(admin).getOrThrow();
      Pg.exec(ARENA, admin, SETUP).getOrThrow();
      app = Pg.connect(ARENA, APP).getOrThrow();
      available = true;
    } catch (Throwable t) {
      available = false;
    }
  }

  @AfterAll
  static void close() {
    if (app != null) Pg.finish(app);
    if (admin != null) Pg.finish(admin);
    ARENA.close();
  }

  @BeforeEach
  void reset() {
    assumeTrue(available, "no Postgres reachable / could not create the app role");
    adminExec("DELETE FROM monolith_grant");
    adminExec("DELETE FROM monolith_role_member");
    adminExec("DELETE FROM acl_patient");
    adminExec("INSERT INTO acl_patient (id, name) VALUES ('" + P1 + "', 'p1'), ('" + P2 + "', 'p2')");
  }

  @Test
  @DisplayName("an instance grant exposes just that row")
  void instanceGrantExposesOneRow() {
    Grants.grant(admin, "alice", "acl_patient", P1, "care_team").getOrThrow();

    assertEquals(1, asActor("alice", "SELECT count(*) FROM acl_patient"));
    assertEquals("p1", asActorText("alice", "SELECT name FROM acl_patient"));
  }

  @Test
  @DisplayName("a deny overrides a broad allow")
  void denyOverridesAllow() {
    Grants.grant(admin, "alice", "acl_patient", Grants.ALL, "viewer").getOrThrow(); // sees all
    Grants.deny(admin, "alice", "acl_patient", P2, "viewer").getOrThrow();           // except P2

    assertEquals(1, asActor("alice", "SELECT count(*) FROM acl_patient"));
    assertEquals("p1", asActorText("alice", "SELECT name FROM acl_patient"));
  }

  @Test
  @DisplayName("a role grant applies to its members")
  void roleGrantAppliesToMembers() {
    Grants.grant(admin, "clinician", "acl_patient", Grants.ALL, "viewer").getOrThrow();
    Grants.addRole(admin, "bob", "clinician").getOrThrow();

    assertEquals(2, asActor("bob", "SELECT count(*) FROM acl_patient"));

    Grants.removeRole(admin, "bob", "clinician").getOrThrow();
    assertEquals(0, asActor("bob", "SELECT count(*) FROM acl_patient"), "no longer a member");
  }

  @Test
  @DisplayName("revoke removes access")
  void revokeRemovesAccess() {
    Grants.grant(admin, "alice", "acl_patient", P1, "care_team").getOrThrow();
    assertEquals(1, asActor("alice", "SELECT count(*) FROM acl_patient"));

    Grants.revoke(admin, "alice", "acl_patient", P1, "care_team").getOrThrow();
    assertEquals(0, asActor("alice", "SELECT count(*) FROM acl_patient"));
  }

  @Test
  @DisplayName("an actor with no grant sees nothing (fail-closed)")
  void unknownActorSeesNothing() {
    assertEquals(0, asActor("nobody", "SELECT count(*) FROM acl_patient"));
  }

  @Test
  @DisplayName("a hydrated session attribute is readable by current_setting (the basis for attribute checks)")
  void aHydratedAttributeIsReadable() {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, app, "BEGIN").getOrThrow();
      PgSession.set(a, app, "app.tier", "gold"); // the app hydrates an actor attribute once
      String tier = Pg.textColumn(a, app, "SELECT current_setting('app.tier', true)").getOrThrow().get(0);
      Pg.exec(a, app, "COMMIT").getOrThrow();
      assertEquals("gold", tier, "a policy or where-clause can read this with current_setting");
    }
  }

  @Test
  @DisplayName("a read relation grants SELECT only; writing requires a write relation")
  void relationSeparatesReadFromWrite() {
    adminExec("DELETE FROM acl_chart");
    adminExec("INSERT INTO acl_chart (id, note) VALUES ('" + P1 + "', 'chart1')");

    // alice is a viewer: she may read the row but neither insert nor update it.
    Grants.grant(admin, "alice", "acl_chart", P1, "viewer").getOrThrow();
    assertEquals(1, asActor("alice", "SELECT count(*) FROM acl_chart"), "a viewer can read");
    assertTrue(asActorExec("alice", "INSERT INTO acl_chart (id, note) VALUES ('" + P2 + "', 'x')").isFailure(),
        "a viewer cannot insert: the write policy's WITH CHECK rejects it");
    asActorExec("alice", "UPDATE acl_chart SET note = 'hacked' WHERE id = '" + P1 + "'");
    assertEquals("chart1", adminText("SELECT note FROM acl_chart WHERE id = '" + P1 + "'"),
        "a viewer's UPDATE matches no row under the write policy, so nothing changes");

    // carol is an editor: she may read and write.
    Grants.grant(admin, "carol", "acl_chart", P1, "editor").getOrThrow();
    assertEquals(1, asActor("carol", "SELECT count(*) FROM acl_chart"), "an editor can also read");
    assertTrue(asActorExec("carol", "UPDATE acl_chart SET note = 'edited' WHERE id = '" + P1 + "'").isSuccess(),
        "an editor can write");
    assertEquals("edited", adminText("SELECT note FROM acl_chart WHERE id = '" + P1 + "'"));
  }

  @Test
  @DisplayName("granting twice is idempotent")
  void grantingTwiceIsIdempotent() {
    Grants.grant(admin, "alice", "acl_patient", P1, "care_team").getOrThrow();
    Grants.grant(admin, "alice", "acl_patient", P1, "care_team").getOrThrow();

    assertEquals(1, adminCount("SELECT count(*) FROM monolith_grant"));
  }

  // ---- helpers ----

  private static int asActor(String actor, String sql) {
    return Integer.parseInt(asActorText(actor, sql));
  }

  private static String asActorText(String actor, String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, app, "BEGIN").getOrThrow();
      PgSession.actor(a, app, actor);
      String value = Pg.textColumn(a, app, sql).getOrThrow().get(0);
      Pg.exec(a, app, "COMMIT").getOrThrow();
      return value;
    }
  }

  /** Runs a write as {@code actor} in its own transaction, returning the result (success or failure). */
  private static Result<Void> asActorExec(String actor, String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, app, "BEGIN").getOrThrow();
      PgSession.actor(a, app, actor);
      Result<Void> result = Pg.exec(a, app, sql);
      Pg.exec(a, app, result.isSuccess() ? "COMMIT" : "ROLLBACK");
      return result;
    }
  }

  private static String adminText(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, admin, sql).getOrThrow().get(0);
    }
  }

  private static int adminCount(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Integer.parseInt(Pg.textColumn(a, admin, sql).getOrThrow().get(0));
    }
  }

  private static void adminExec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, sql).getOrThrow();
    }
  }
}
