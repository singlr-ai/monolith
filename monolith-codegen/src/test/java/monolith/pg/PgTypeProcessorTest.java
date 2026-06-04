/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives the annotation processor through the JDK compiler with {@code -proc:only}: the processor
 * runs and writes its artifacts, but the runtime-referencing generated code is not compiled, so
 * codegen needs no dependency on runtime. Assertions check the generated text and the diagnostics.
 */
@DisplayName("PgTypeProcessor")
class PgTypeProcessorTest {

  private static final String IMPORTS =
      "import java.util.UUID; import java.math.BigDecimal; import java.time.*; import monolith.pg.*;";

  /** Result of a processing run: success, the error messages, and the output directories. */
  private record Outcome(boolean ok, List<String> errors, Path gen, Path sql, Path ts, Path lock) {
    boolean generated(String relativePath) {
      return Files.exists(gen.resolve(relativePath));
    }

    String read(String relativePath) {
      try {
        return Files.readString(gen.resolve(relativePath));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    boolean anyError(String fragment) {
      return errors.stream().anyMatch(e -> e.contains(fragment));
    }

    /**
     * Errors the processor itself raised, excluding the expected noise from {@code -proc:only}
     * entering the generated code (which references {@code monolith.pg.runtime}, not on this
     * module's classpath). The processor still ran fully and wrote its files.
     */
    List<String> processorErrors() {
      return errors.stream()
          .filter(e -> !e.contains("does not exist") && !e.contains("cannot find symbol"))
          .toList();
    }

    boolean cleanRun() {
      return processorErrors().isEmpty();
    }
  }

  private static Outcome process(Map<String, String> sources, boolean withDirs) {
    try {
      var compiler = ToolProvider.getSystemJavaCompiler();
      var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
      var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
      Path work = Files.createTempDirectory("monolith-proc");
      Path gen = Files.createDirectories(work.resolve("gen"));
      Path classes = Files.createDirectories(work.resolve("classes"));
      Path sql = work.resolve("sql");
      Path ts = work.resolve("ts");
      Path lock = work.resolve("lock");

      var units = new ArrayList<JavaFileObject>();
      sources.forEach((fqn, code) -> units.add(source(fqn, code)));

      var options = new ArrayList<>(List.of(
          "-proc:only",
          "-processor", "monolith.pg.PgTypeProcessor",
          "-s", gen.toString(),
          "-d", classes.toString()));
      if (withDirs) {
        options.add("-Amonolith.sqlDir=" + sql);
        options.add("-Amonolith.tsDir=" + ts);
        options.add("-Amonolith.lockDir=" + lock);
      }

      boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
      List<String> errors = diagnostics.getDiagnostics().stream()
          .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
          .map(d -> d.getMessage(null))
          .toList();
      return new Outcome(ok, errors, gen, sql, ts, lock);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Outcome process(String fqn, String code) {
    return process(Map.of(fqn, code), false);
  }

  private static JavaFileObject source(String fqn, String code) {
    return new SimpleJavaFileObject(URI.create("string:///" + fqn.replace('.', '/') + ".java"),
        JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return code;
      }
    };
  }

  @Nested
  @DisplayName("@PgType code generation")
  class Types {

    @Test
    void everyColumnTypeGeneratesAReaderBuilderSqlTsAndLock() {
      String code = "package t; " + IMPORTS + " @PgType public record AllTypes("
          + "UUID a, String b, byte[] c, BigDecimal d, Json e, int[] f, long[] g, String[] h,"
          + "int i, long j, short k, boolean l, double m, float n,"
          + "LocalDate o, LocalTime p, LocalDateTime q, Instant r, OffsetDateTime s) {}";
      Outcome out = process(Map.of("t.AllTypes", code), true);

      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("t/AllTypesReader.java"));
      assertTrue(out.generated("t/AllTypesBuilder.java"));
      assertTrue(Files.exists(out.sql().resolve("all_types.sql")));
      assertTrue(Files.exists(out.ts().resolve("all_types.ts")));
      assertTrue(Files.exists(out.lock().resolve("schema.lock")));

      String reader = out.read("t/AllTypesReader.java");
      assertTrue(reader.contains("PgCodec.decodeNumeric"));
      assertTrue(reader.contains("PgCodec.decodeJsonb"));
      assertTrue(reader.contains("LocalDate.ofEpochDay"));
    }

    @Test
    void nullableBoxedColumnsGetANullBitmap() {
      String code = "package t; " + IMPORTS + " @PgType public record Nullable("
          + "@PgNull UUID a, @PgNull String b, @PgNull byte[] c, @PgNull BigDecimal d, @PgNull Json e,"
          + "@PgNull int[] f, @PgNull long[] g, @PgNull String[] h,"
          + "@PgNull Integer i, @PgNull Long j, @PgNull Short k, @PgNull Boolean l,"
          + "@PgNull Double m, @PgNull Float n, @PgNull LocalDate o, @PgNull LocalTime p,"
          + "@PgNull LocalDateTime q, @PgNull Instant r, @PgNull OffsetDateTime s) {}";
      Outcome out = process(Map.of("t.Nullable", code), true);

      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      String reader = out.read("t/NullableReader.java");
      assertTrue(reader.contains("private boolean isNull(int ordinal)"));
      assertFalse(reader.contains("NULL_BITMAP_BYTES = 0;"));
    }

    @Test
    void encryptedStringEncryptsOnWriteAndDecryptsOnRead() throws IOException {
      String code = "package t; " + IMPORTS
          + " @PgType public record Secret(UUID id, @Encrypted String ssn) {}";
      Outcome out = process(Map.of("t.Secret", code), true);

      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.read("t/SecretReader.java").contains("PgCrypto.decrypt"));
      assertTrue(out.read("t/SecretBuilder.java").contains("PgCrypto.encrypt"));
      assertTrue(Files.readString(out.ts().resolve("secret.ts")).contains("Uint8Array")); // ciphertext to client
    }

    @Test
    void aFixedOnlyRecordHasNoBitmapOrTail() {
      Outcome out = process("t.Fixed",
          "package t; " + IMPORTS + " @PgType public record Fixed(int a, long b) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.read("t/FixedReader.java").contains("NULL_BITMAP_BYTES = 0;"));
    }

    @Test
    void anExplicitNameIsHonored() {
      Outcome out = process(Map.of("t.Named",
          "package t; " + IMPORTS + " @PgType(\"custom\") public record Named(int x) {}"), true);
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(Files.exists(out.sql().resolve("custom.sql")));
    }

    @Test
    void theTypeScriptReaderDecodesJsonbNumericAndArrays() throws IOException {
      String code = "package t; " + IMPORTS + " @PgType public record Wide("
          + "Json meta, BigDecimal amount, int[] tags, long[] ids, String[] labels) {}";
      Outcome out = process(Map.of("t.Wide", code), true);
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());

      String ts = Files.readString(out.ts().resolve("wide.ts"));
      assertTrue(ts.contains("import { decodeInt4Array, decodeInt8Array, decodeJsonb, "
          + "decodeNumeric, decodeTextArray } from '@standardapplied/monolith-client';"));
      assertTrue(ts.contains("decodeJsonb(this.buf"));
      assertTrue(ts.contains("): unknown {"));            // jsonb
      assertTrue(ts.contains("): string {"));             // numeric, as a decimal string
      assertTrue(ts.contains("): number[] {"));           // int4[]
      assertTrue(ts.contains("): bigint[] {"));           // int8[]
      assertTrue(ts.contains("): (string | null)[] {"));  // text[]
    }
  }

  @Nested
  @DisplayName("@PgProjection")
  class Projections {

    @Test
    void aProjectionGeneratesAReaderButNoBuilderOrTable() {
      Outcome out = process("t.Proj",
          "package t; " + IMPORTS + " @PgProjection public record Proj(int x, String y) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("t/ProjReader.java"));
      assertFalse(out.generated("t/ProjBuilder.java"));
    }

    @Test
    void aProjectionCanCarryAnExplicitName() {
      Outcome out = process(Map.of("t.Proj2",
          "package t; " + IMPORTS + " @PgProjection(\"pj\") public record Proj2(int x) {}"), true);
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(Files.exists(out.ts().resolve("pj.ts")));
    }
  }

  @Nested
  @DisplayName("@PgQuery invalidation derivation")
  class Queries {

    @Test
    void aParamOnTheBaseTableYieldsADirectRule() {
      Outcome out = process("t.Q1", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT id, n FROM widgets WHERE box_id = $1\")"
          + " public record Q1(UUID id, int n) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("t/Q1Query.java"));
      assertTrue(out.read("t/Q1Invalidation.java").contains("valuesOf.apply(\"box_id\")"));
    }

    @Test
    void anAliasedParamIsResolved() {
      Outcome out = process("t.Q2", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT w.id FROM widgets AS w WHERE w.box_id = $1\")"
          + " public record Q2(UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("t/Q2Invalidation.java"));
    }

    @Test
    void aJoinedParamWalksTheBackReference() {
      Outcome out = process("t.Q3", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT w.id FROM widgets w JOIN boxes b ON b.id = w.box_id"
          + " WHERE b.region = $1\") public record Q3(UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      String rule = out.read("t/Q3Invalidation.java");
      assertTrue(rule.contains("case \"boxes\""));
      assertTrue(rule.contains("PgInvalidate.resolve"));
    }

    @Test
    void aMultiHopJoinChainsTheBackReference() {
      Outcome out = process("t.Q4", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT w.id FROM widgets w JOIN boxes b ON b.id = w.box_id"
          + " JOIN regions r ON r.id = b.region_id WHERE r.name = $1\")"
          + " public record Q4(UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.read("t/Q4Invalidation.java").contains("JOIN"));
    }

    @Test
    void aQueryWithoutADerivableParamSkipsTheRule() {
      Outcome noFrom = process("t.Q5",
          "package t; " + IMPORTS + " @PgQuery(\"SELECT 1\") public record Q5(int x) {}");
      assertTrue(noFrom.cleanRun(), () -> "processor errors: " + noFrom.processorErrors());
      assertTrue(noFrom.generated("t/Q5Query.java"));
      assertFalse(noFrom.generated("t/Q5Invalidation.java"));

      Outcome noParam = process("t.Q6", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT x FROM dual\") public record Q6(int x) {}");
      assertTrue(noParam.cleanRun());
      assertFalse(noParam.generated("t/Q6Invalidation.java"));
    }
  }

  @Nested
  @DisplayName("diagnostics for bad input")
  class Errors {

    @Test
    void pgTypeOnANonRecordIsRejected() {
      Outcome out = process("t.NotARecord",
          "package t; " + IMPORTS + " @PgType public class NotARecord {}");
      assertFalse(out.ok());
      assertTrue(out.anyError("only valid on records"));
    }

    @Test
    void anUnsupportedComponentTypeIsRejected() {
      Outcome out = process("t.Bad",
          "package t; " + IMPORTS + " @PgType public record Bad(Object x) {}");
      assertFalse(out.ok());
      assertTrue(out.anyError("unsupported component type"));
    }

    @Test
    void encryptedOnANonStringIsRejected() {
      Outcome out = process("t.Bad",
          "package t; " + IMPORTS + " @PgType public record Bad(@Encrypted int x) {}");
      assertFalse(out.ok());
      assertTrue(out.anyError("@Encrypted is only supported on String"));
    }

    @Test
    void pgNullOnAPrimitiveIsRejected() {
      Outcome out = process("t.Bad",
          "package t; " + IMPORTS + " @PgType public record Bad(@PgNull int x) {}");
      assertFalse(out.ok());
      assertTrue(out.anyError("never be null"));
    }

    @Test
    void projectionAndQueryOnNonRecordsAreRejected() {
      assertTrue(process("t.P", "package t; " + IMPORTS + " @PgProjection public class P {}")
          .anyError("only valid on records"));
      assertTrue(process("t.Q", "package t; " + IMPORTS + " @PgQuery(\"SELECT 1\") public class Q {}")
          .anyError("only valid on records"));
    }
  }

  @Nested
  @DisplayName("compliance DDL (@Tenant / @Audited)")
  class Compliance {

    private String sqlFor(String record) throws IOException {
      Outcome out = process(Map.of("t.Acct", "package t; " + IMPORTS + " " + record), true);
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      return Files.readString(out.sql().resolve("acct.sql"));
    }

    @Test
    void aTenantColumnGeneratesForcedRowLevelSecurity() throws IOException {
      String sql = sqlFor("@PgType public record Acct(@Tenant String org, int n) {}");
      assertTrue(sql.contains("ENABLE ROW LEVEL SECURITY"));
      assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"));
      assertTrue(sql.contains("acct_tenant_isolation"));
      assertTrue(sql.contains("current_setting('app.tenant', true)::text"));
    }

    @Test
    void aUuidTenantColumnCastsToUuid() throws IOException {
      String sql = sqlFor("@PgType public record Acct(@Tenant java.util.UUID org) {}");
      assertTrue(sql.contains("current_setting('app.tenant', true)::uuid"));
    }

    @Test
    void anAuditedTypeGeneratesAnAppendOnlyAuditTrail() throws IOException {
      String sql = sqlFor("@Audited @PgType public record Acct(int n) {}");
      assertTrue(sql.contains("CREATE TABLE acct_audit"));
      assertTrue(sql.contains("acct_audit_write"));
      assertTrue(sql.contains("acct_audit_guard"));
      assertTrue(sql.contains("SECURITY DEFINER"));
      assertTrue(sql.contains("SET search_path FROM CURRENT"),
          () -> "the audit function must pin search_path to resist caller hijacking; got:\n" + sql);
      assertTrue(sql.contains("append-only"));
    }

    @Test
    void anOrdinaryTypeHasNeitherRlsNorAudit() throws IOException {
      String sql = sqlFor("@PgType public record Acct(int n) {}");
      assertFalse(sql.contains("ROW LEVEL SECURITY"));
      assertFalse(sql.contains("_audit"));
    }

    @Test
    void tenantOnANonTextOrUuidColumnIsRejected() {
      Outcome out = process("t.Bad",
          "package t; " + IMPORTS + " @PgType public record Bad(@Tenant int n) {}");
      assertFalse(out.cleanRun());
      assertTrue(out.anyError("@Tenant is only supported"));
    }

    @Test
    void anAccessControlledTypeGeneratesGrantBasedRls() throws IOException {
      String sql = sqlFor("@AccessControlled @PgType public record Acct(java.util.UUID id, int n) {}");
      assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"));
      assertTrue(sql.contains("CREATE POLICY acct_access ON acct"));
      assertTrue(sql.contains("FROM monolith_grant g"));
      assertTrue(sql.contains("g.resource = 'acct'"));
      assertTrue(sql.contains("g.resource_id IN (id::text, '*')"));
      assertTrue(sql.contains("g.effect = 'allow'"));
      assertTrue(sql.contains("AND NOT EXISTS")); // deny-wins
      assertTrue(sql.contains("d.effect = 'deny'"));
      assertTrue(sql.contains("FROM monolith_role_member")); // role indirection
      assertTrue(sql.contains("current_setting('app.actor', true)"));
    }

    @Test
    void readAndWriteRelationsEmitCommandSpecificPolicies() throws IOException {
      String sql = sqlFor("@AccessControlled(read = {\"viewer\", \"care_team\"}, write = {\"owner\", \"editor\"})"
          + " @PgType public record Acct(java.util.UUID id, int n) {}");
      // One policy per command, so a grant only authorizes the action its relation is mapped to.
      assertTrue(sql.contains("CREATE POLICY acct_select ON acct FOR SELECT"), () -> sql);
      assertTrue(sql.contains("CREATE POLICY acct_insert ON acct FOR INSERT"), () -> sql);
      assertTrue(sql.contains("CREATE POLICY acct_update ON acct FOR UPDATE"), () -> sql);
      assertTrue(sql.contains("CREATE POLICY acct_delete ON acct FOR DELETE"), () -> sql);
      // The SELECT policy is keyed on the read relations, the write commands on the write relations.
      assertTrue(sql.contains("g.relation IN ('viewer', 'care_team')"), () -> sql);
      assertTrue(sql.contains("g.relation IN ('owner', 'editor')"), () -> sql);
      // Deny is relation-scoped too, so a deny on a write relation blocks writes, not reads.
      assertTrue(sql.contains("d.relation IN ('owner', 'editor')"), () -> sql);
      // The relation-agnostic single policy is not emitted in relation-aware mode.
      assertFalse(sql.contains("CREATE POLICY acct_access ON acct"), () -> sql);
    }

    @Test
    void anActionWithNoRelationsIsFailClosed() throws IOException {
      // read-only access control: writes have no granting relation, so the write policies are fail-closed.
      String sql = sqlFor("@AccessControlled(read = {\"viewer\"})"
          + " @PgType public record Acct(java.util.UUID id) {}");
      assertTrue(sql.contains("CREATE POLICY acct_insert ON acct FOR INSERT\n  WITH CHECK (false)"), () -> sql);
      assertTrue(sql.contains("CREATE POLICY acct_delete ON acct FOR DELETE\n  USING (false)"), () -> sql);
      assertTrue(sql.contains("g.relation IN ('viewer')"), () -> sql);
    }

    @Test
    void writeOnlyAccessControlFailClosesReads() throws IOException {
      // write relations only: reads have no granting relation, so the SELECT policy is fail-closed.
      String sql = sqlFor("@AccessControlled(write = {\"editor\"})"
          + " @PgType public record Acct(java.util.UUID id) {}");
      assertTrue(sql.contains("CREATE POLICY acct_select ON acct FOR SELECT\n  USING (false)"), () -> sql);
      assertTrue(sql.contains("CREATE POLICY acct_update ON acct FOR UPDATE"), () -> sql);
      assertTrue(sql.contains("g.relation IN ('editor')"), () -> sql);
    }

    @Test
    void relationLiteralsAreSqlEscaped() throws IOException {
      String sql = sqlFor("@AccessControlled(read = {\"o'brien\"})"
          + " @PgType public record Acct(java.util.UUID id) {}");
      assertTrue(sql.contains("g.relation IN ('o''brien')"), () -> sql);
    }

    @Test
    void accessControlComposesWithTenantAsRestrictive() throws IOException {
      String sql = sqlFor(
          "@AccessControlled @PgType public record Acct(java.util.UUID id, @Tenant String org) {}");
      assertTrue(sql.contains("CREATE POLICY acct_access ON acct"));
      assertTrue(sql.contains("acct_tenant_isolation ON acct AS RESTRICTIVE"),
          "tenant ANDs with the grant check, not ORs");
    }

    @Test
    void accessControlAcceptsACustomResourceAndIdColumn() throws IOException {
      String sql = sqlFor(
          "@AccessControlled(resource = \"account\", id = \"acctId\")"
              + " @PgType public record Acct(java.util.UUID acctId) {}");
      assertTrue(sql.contains("g.resource = 'account'"));
      assertTrue(sql.contains("g.resource_id IN (acct_id::text, '*')"));
    }

    @Test
    void accessControlEscapesSingleQuotesInTheResourceLiteral() throws IOException {
      // A resource with an apostrophe must be escaped as a doubled quote, not break out of the SQL
      // string literal and generate broken or unintended policy text.
      String sql = sqlFor("@AccessControlled(resource = \"o'brien\")"
          + " @PgType public record Acct(java.util.UUID id) {}");
      assertTrue(sql.contains("g.resource = 'o''brien'"),
          () -> "resource literal must be SQL-escaped (doubled quote); got:\n" + sql);
      assertFalse(sql.contains("'o'brien'"),
          "must not emit an unescaped apostrophe that terminates the string literal");
    }

    @Test
    void accessControlWithAnUnknownIdColumnIsRejected() {
      Outcome out = process("t.Acct",
          "package t; " + IMPORTS + " @AccessControlled @PgType public record Acct(int n) {}");
      assertFalse(out.cleanRun());
      assertTrue(out.anyError("@AccessControlled id"));
    }

    @Test
    void anAttributeConditionIsAndedIntoThePolicy() throws IOException {
      // Balanced parentheses in the predicate are allowed (they just must not be unbalanced).
      String sql = sqlFor("@AccessControlled(where = \"(status <> 'archived' OR status IS NULL)\")"
          + " @PgType public record Acct(java.util.UUID id, String status) {}");
      assertTrue(sql.contains("(status <> 'archived' OR status IS NULL)"));
      assertTrue(sql.contains("FROM monolith_grant g"), "the condition ANDs with the grant check");
    }

    @Test
    void aStatementBreakingWhereIsRejected() {
      assertWhereRejected("status = 'x'; DROP TABLE acct"); // statement terminator
      assertWhereRejected("status = 'x')");                  // unbalanced close
      assertWhereRejected("(status = 'x'");                  // unbalanced open
    }

    private void assertWhereRejected(String where) {
      Outcome out = process("t.Acct", "package t; " + IMPORTS + " @AccessControlled(where = \"" + where
          + "\") @PgType public record Acct(java.util.UUID id) {}");
      assertFalse(out.cleanRun(), () -> "expected rejection of where: " + where);
      assertTrue(out.anyError("@AccessControlled where"));
    }
  }

  @Test
  @DisplayName("without output directories, no sql/ts/lock files are written")
  void artifactDirsAreOptional() {
    Outcome out = process(Map.of("t.Fixed",
        "package t; " + IMPORTS + " @PgType public record Fixed(int a) {}"), false);
    assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
    assertTrue(out.generated("t/FixedReader.java")); // java is always generated via the Filer
    assertFalse(Files.exists(out.sql()));            // but sql/ts/lock dirs are option-gated
    assertFalse(Files.exists(out.ts()));
    assertFalse(Files.exists(out.lock()));
  }

  @Nested
  @DisplayName("edge cases")
  class Edges {

    @Test
    void aRecordInTheDefaultPackageGeneratesWithoutAPackageLine() {
      Outcome out = process(Map.of("Root",
          "import monolith.pg.*; @PgType public record Root(int x) {}"), true);
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("RootReader.java"));
      assertFalse(out.read("RootReader.java").startsWith("package"));
    }

    @Test
    void aParamOnAnUnknownAliasCannotDeriveARule() {
      Outcome out = process("t.Q", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT id FROM widgets w WHERE x.col = $1\") public record Q(java.util.UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("t/QQuery.java"));
      assertFalse(out.generated("t/QInvalidation.java")); // paramTable unknown -> rule skipped
    }

    @Test
    void aBaseTableFollowedByBareAsKeyword() {
      Outcome out = process("t.Q", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT id FROM widgets AS WHERE box_id = $1\") public record Q(java.util.UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("t/QInvalidation.java"));
    }

    @Test
    void aQueryInTheDefaultPackageStillEmitsAQueryAndRule() {
      Outcome out = process(Map.of("DQ", "import monolith.pg.*;"
          + " @PgQuery(\"SELECT id FROM widgets WHERE box_id = $1\") public record DQ(java.util.UUID id) {}"),
          true);
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.generated("DQQuery.java"));
      assertTrue(out.generated("DQInvalidation.java"));
    }

    @Test
    void aTableThatCannotReachTheParamIsSkippedFromTheRule() {
      // the self-joined 'tags g' alias is disconnected from the param table, so bfs returns null
      Outcome out = process("t.Q", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT w.id FROM widgets w JOIN boxes b ON b.id = w.box_id"
          + " JOIN tags g ON g.a = g.b WHERE b.region = $1\") public record Q(java.util.UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      String rule = out.read("t/QInvalidation.java");
      assertTrue(rule.contains("case \"boxes\""));
      assertFalse(rule.contains("case \"tags\"")); // disconnected -> no case generated
    }

    @Test
    void aSelfJoinIsRejectedRatherThanSilentlyCollapsed() {
      // employees joined twice (self/manager): collapsing the aliases to one would drop the
      // manager-role invalidation path and silently miss live changes. Reject at compile time.
      Outcome out = process("t.Q", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT e.id FROM employees e JOIN employees m ON m.id = e.manager_id"
          + " WHERE e.id = $1\") public record Q(java.util.UUID id) {}");
      assertFalse(out.ok(), "a self-join query must fail compilation, not generate a partial rule");
      assertTrue(out.anyError("self-join"), () -> "expected a self-join error, got: " + out.errors());
      assertFalse(out.generated("t/QInvalidation.java"), "no invalidation rule for a rejected self-join");
    }

    @Test
    void aJoinWrittenChildKeyFirstResolvesTheEdge() {
      Outcome out = process("t.Q", "package t; " + IMPORTS
          + " @PgQuery(\"SELECT w.id FROM widgets w JOIN boxes b ON w.box_id = b.id"
          + " WHERE b.region = $1\") public record Q(java.util.UUID id) {}");
      assertTrue(out.cleanRun(), () -> "processor errors: " + out.processorErrors());
      assertTrue(out.read("t/QInvalidation.java").contains("PgInvalidate.resolve"));
    }

    @Test
    void anUnwritableSqlDirSurfacesAsAProcessorError() throws IOException {
      // point sqlDir at a path *under a regular file*, so directory creation fails with IOException
      Path blockingFile = Files.createTempFile("monolith-block", ".txt");
      Outcome out = compileWithBadDir("monolith.sqlDir", blockingFile.resolve("nope").toString(),
          "@PgType public record Bad(int a) {}");
      assertTrue(out.anyError("codegen I/O failed"));
    }

    @Test
    void anUnwritableLockDirSurfacesAsAProcessorError() throws IOException {
      Path blockingFile = Files.createTempFile("monolith-block", ".txt");
      Outcome out = compileWithBadDir("monolith.lockDir", blockingFile.resolve("nope").toString(),
          "@PgType public record Bad(int a) {}");
      assertTrue(out.anyError("failed to write schema.lock"));
    }
  }

  /** Compile one record with a single {@code -A} option pointed at a deliberately unusable path. */
  private static Outcome compileWithBadDir(String optKey, String optVal, String recordDecl) {
    try {
      var compiler = ToolProvider.getSystemJavaCompiler();
      var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
      var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
      Path work = Files.createTempDirectory("monolith-proc");
      Path gen = Files.createDirectories(work.resolve("gen"));
      var units = List.of(source("Bad", "import monolith.pg.*; " + recordDecl));
      var options = List.of("-proc:only", "-processor", "monolith.pg.PgTypeProcessor",
          "-s", gen.toString(), "-d", gen.toString(), "-A" + optKey + "=" + optVal);
      compiler.getTask(null, fm, diagnostics, options, null, units).call();
      List<String> errors = diagnostics.getDiagnostics().stream()
          .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(d -> d.getMessage(null)).toList();
      return new Outcome(false, errors, gen, gen, gen, gen);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Nested
  @DisplayName("private helpers (reflective, for defensive paths)")
  class Internals {

    @Test
    void javaStringLiteralEscapesEveryControlCharacter() throws Exception {
      String escaped = (String) invoke("javaStringLiteral", new Class<?>[] {String.class},
          "a\\b\"c\nd\re\tf");
      assertTrue(escaped.contains("\\\\")); // backslash
      assertTrue(escaped.contains("\\\"")); // quote
      assertTrue(escaped.contains("\\n"));
      assertTrue(escaped.contains("\\r"));
      assertTrue(escaped.contains("\\t"));
    }

    @Test
    void typeSwitchesRejectAnUnmappedTypeDefensively() throws Exception {
      Object field = field("x.Unmapped");
      Class<?> fieldClass = Class.forName("monolith.pg.PgTypeProcessor$Field");
      for (String method : List.of("readerAccessor", "varEncodeExpr", "builderFixedWrite")) {
        var ex = assertThrowsCause(method, new Class<?>[] {fieldClass}, field);
        assertTrue(ex instanceof IllegalStateException, method + " should reject the unmapped type");
      }
      var tsEx = assertThrowsCause("tsAccessor", new Class<?>[] {fieldClass, boolean.class}, field, false);
      assertTrue(tsEx instanceof IllegalStateException);
    }

    @Test
    void bfsAndEdgeBetweenReturnNullWhenDisconnected() throws Exception {
      assertNull(invokeEmitter("bfs", new Class<?>[] {List.class, String.class, String.class},
          List.of(), "a", "b"));
      assertNull(invokeEmitter("edgeBetween", new Class<?>[] {List.class, String.class, String.class},
          List.of(), "a", "b"));
    }

    @Test
    void edgeBetweenRejectsAPartialMatchInEitherDirection() throws Exception {
      Object edges = List.of(qedge("p", "1", "q", "2"));
      var types = new Class<?>[] {List.class, String.class, String.class};
      // a == x but b != y, and a == y but b != x: neither is a full edge, so no match
      assertNull(invokeEmitter("edgeBetween", types, edges, "p", "z"));
      assertNull(invokeEmitter("edgeBetween", types, edges, "z", "p"));
    }

    private static Object qedge(String a, String ca, String b, String cb) throws Exception {
      Class<?> qedgeClass = Class.forName("monolith.pg.InvalidationEmitter$QEdge");
      var ctor = qedgeClass.getDeclaredConstructors()[0];
      ctor.setAccessible(true);
      return ctor.newInstance(a, ca, b, cb);
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
      var m = PgTypeProcessor.class.getDeclaredMethod(name, types);
      m.setAccessible(true);
      return m.invoke(null, args);
    }

    /** Like {@link #invoke} but targets the extracted {@link InvalidationEmitter} (SQL-to-rule logic). */
    private static Object invokeEmitter(String name, Class<?>[] types, Object... args) throws Exception {
      var m = InvalidationEmitter.class.getDeclaredMethod(name, types);
      m.setAccessible(true);
      return m.invoke(null, args);
    }

    private static Throwable assertThrowsCause(String name, Class<?>[] types, Object... args)
        throws Exception {
      var m = PgTypeProcessor.class.getDeclaredMethod(name, types);
      m.setAccessible(true);
      try {
        m.invoke(null, args);
        throw new AssertionError(name + " did not throw");
      } catch (java.lang.reflect.InvocationTargetException e) {
        return e.getCause();
      }
    }

    /** Build a private {@code Field} with a bogus java type to hit the switch defaults. */
    private static Object field(String javaType) throws Exception {
      Class<?> kindClass = Class.forName("monolith.pg.PgTypeProcessor$Kind");
      @SuppressWarnings({"unchecked", "rawtypes"})
      Object fixed = Enum.valueOf((Class<Enum>) kindClass, "FIXED");
      Class<?> fieldClass = Class.forName("monolith.pg.PgTypeProcessor$Field");
      var ctor = fieldClass.getDeclaredConstructors()[0];
      ctor.setAccessible(true);
      return ctor.newInstance(0, "x", "text", fixed, 4, 0, javaType, false, false, false);
    }
  }
}
