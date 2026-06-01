/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgSession;
import monolith.pg.runtime.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the compliance features the codegen emits actually enforce, against real Postgres: forced
 * row-level security isolates tenants and blocks cross-tenant writes, a SECURITY DEFINER trigger
 * records an attributed audit row for every write, and that audit trail is append-only. The session
 * context (tenant, actor) is set through {@link PgSession}. Skips when no Postgres is reachable.
 *
 * <p>The schema below mirrors what {@code PgTypeProcessor} generates for a {@code @Tenant} +
 * {@code @Audited @PgType}; {@code PgTypeProcessorTest} asserts that generated DDL string-for-string.
 */
@DisplayName("compliance against real Postgres")
class ComplianceIT {

  private static final String ADMIN = System.getenv().getOrDefault(
      "MONOLITH_TEST_CONNINFO",
      "host=localhost dbname=monolith_test user=" + System.getProperty("user.name"));

  // libpq takes the last value when a key repeats, so this overrides the user/password.
  private static final String APP = ADMIN + " user=monolith_app_role password=monolith";

  private static final String SCHEMA = """
      DO $$ BEGIN
        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'monolith_app_role') THEN
          EXECUTE 'DROP OWNED BY monolith_app_role';
          EXECUTE 'DROP ROLE monolith_app_role';
        END IF;
      END $$;
      DROP TABLE IF EXISTS account_audit;
      DROP TABLE IF EXISTS account;
      CREATE TABLE account (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(), org text NOT NULL, balance int NOT NULL);
      ALTER TABLE account ENABLE ROW LEVEL SECURITY;
      ALTER TABLE account FORCE ROW LEVEL SECURITY;
      CREATE POLICY account_tenant_isolation ON account
        USING (org = current_setting('app.tenant', true)::text)
        WITH CHECK (org = current_setting('app.tenant', true)::text);
      CREATE TABLE account_audit (
        audit_id bigserial PRIMARY KEY, logged_at timestamptz NOT NULL DEFAULT now(),
        actor text NOT NULL, action text NOT NULL, old_row jsonb, new_row jsonb);
      CREATE OR REPLACE FUNCTION account_audit_record() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER AS $$
      BEGIN
        INSERT INTO account_audit (actor, action, old_row, new_row)
        VALUES (coalesce(current_setting('app.actor', true), 'unknown'), TG_OP,
                to_jsonb(OLD), to_jsonb(NEW));
        RETURN NULL;
      END $$;
      CREATE TRIGGER account_audit_write AFTER INSERT OR UPDATE OR DELETE ON account
        FOR EACH ROW EXECUTE FUNCTION account_audit_record();
      CREATE OR REPLACE FUNCTION account_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN RAISE EXCEPTION 'audit table account_audit is append-only'; END $$;
      CREATE TRIGGER account_audit_guard BEFORE UPDATE OR DELETE ON account_audit
        FOR EACH ROW EXECUTE FUNCTION account_audit_immutable();
      CREATE ROLE monolith_app_role LOGIN PASSWORD 'monolith';
      GRANT SELECT, INSERT, UPDATE, DELETE ON account TO monolith_app_role;
      GRANT SELECT ON account_audit TO monolith_app_role;""";

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
      Pg.exec(ARENA, admin, SCHEMA).getOrThrow();
      app = Pg.connect(ARENA, APP).getOrThrow(); // login as the restricted role
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
  void requirePostgres() {
    assumeTrue(available, "no Postgres reachable / could not create the app role");
    adminExec("DELETE FROM account"); // reset rows (the audit trail is append-only)
  }

  @Test
  @DisplayName("forced RLS confines each tenant to its own rows")
  void rlsIsolatesTenants() {
    adminExec("INSERT INTO account (org, balance) VALUES ('acme', 1), ('globex', 2)");

    inAppTransaction(() -> {
      PgSession.tenant(ARENA, app, "acme");
      assertEquals(1, appCount("SELECT count(*) FROM account"));
      assertEquals("acme", appText("SELECT org FROM account"));
    });
    inAppTransaction(() -> {
      PgSession.tenant(ARENA, app, "globex");
      assertEquals(1, appCount("SELECT count(*) FROM account"));
    });
  }

  @Test
  @DisplayName("a write to another tenant is blocked, the same tenant is allowed")
  void rlsBlocksCrossTenantWrites() {
    appExec("BEGIN");
    PgSession.tenant(ARENA, app, "acme");
    assertTrue(appExec("INSERT INTO account (org, balance) VALUES ('globex', 9)").isFailure(),
        "WITH CHECK must reject a row for another tenant");
    appExec("ROLLBACK");

    inAppTransaction(() -> {
      PgSession.tenant(ARENA, app, "acme");
      assertTrue(appExec("INSERT INTO account (org, balance) VALUES ('acme', 5)").isSuccess());
    });
  }

  @Test
  @DisplayName("every write is audited with its actor")
  void auditRecordsTheActor() {
    inAppTransaction(() -> {
      PgSession.actor(ARENA, app, "dr.smith");
      PgSession.tenant(ARENA, app, "acme");
      appExec("INSERT INTO account (org, balance) VALUES ('acme', 7)").getOrThrow();
    });

    assertEquals("dr.smith",
        adminText("SELECT actor FROM account_audit WHERE action = 'INSERT' ORDER BY audit_id DESC LIMIT 1"));
    assertEquals("7",
        adminText("SELECT new_row->>'balance' FROM account_audit WHERE action = 'INSERT' ORDER BY audit_id DESC LIMIT 1"));
  }

  @Test
  @DisplayName("the audit trail rejects updates and deletes")
  void auditIsAppendOnly() {
    adminExec("INSERT INTO account (org, balance) VALUES ('audited', 1)"); // leaves an audit row
    assertTrue(fails("UPDATE account_audit SET actor = 'hacker'"), "audit UPDATE must be rejected");
    assertTrue(fails("DELETE FROM account_audit"), "audit DELETE must be rejected");
  }

  // ---- helpers ----

  private interface Work {
    void run();
  }

  private void inAppTransaction(Work work) {
    appExec("BEGIN");
    try {
      work.run();
      appExec("COMMIT");
    } catch (RuntimeException e) {
      appExec("ROLLBACK");
      throw e;
    }
  }

  private static Result<Void> appExec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, app, sql);
    }
  }

  private static void adminExec(String sql) {
    try (Arena a = Arena.ofConfined()) {
      Pg.exec(a, admin, sql).getOrThrow();
    }
  }

  private static boolean fails(String sql) {
    return adminTry(sql).isFailure();
  }

  private static Result<Void> adminTry(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.exec(a, admin, sql);
    }
  }

  private static int appCount(String sql) {
    return Integer.parseInt(appText(sql));
  }

  private static String appText(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, app, sql).getOrThrow().get(0);
    }
  }

  private static String adminText(String sql) {
    try (Arena a = Arena.ofConfined()) {
      return Pg.textColumn(a, admin, sql).getOrThrow().get(0);
    }
  }
}
