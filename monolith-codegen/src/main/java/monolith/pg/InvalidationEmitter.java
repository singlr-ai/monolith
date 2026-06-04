/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;

/**
 * Derives a {@code PgInvalidationRule} from a {@code @PgQuery}'s declared SQL and emits it. This is the
 * heart of the live-query guarantee: parse the FROM/JOIN graph and the {@code = $1} predicate, then for
 * the table carrying the param emit a direct column read, and for each joined table emit the
 * back-reference SQL that walks the join path back to the param column. Split out of
 * {@link PgTypeProcessor} so the SQL-to-rule logic can be read and reviewed on its own; the processor
 * still owns annotations, type mapping, DDL, RLS, audit, and the query runner.
 */
final class InvalidationEmitter {

  private final Filer filer;

  InvalidationEmitter(Filer filer) {
    this.filer = filer;
  }

  /**
   * Parses {@code querySql}, and if an invalidation rule is derivable, writes {@code <Name>Invalidation}
   * and returns {@code true}; returns {@code false} when no rule can be derived (the caller notes a skip).
   * Throws {@link IllegalArgumentException} for a self-join, which the processor surfaces as a compile error.
   */
  boolean emit(String pkg, String name, String querySql) throws IOException {
    ParsedQuery pq = parseQuery(querySql);
    if (pq == null || pq.paramTable() == null) {
      return false;
    }
    rejectSelfJoin(name, pq);
    emitInvalidation(pkg, name, pq);
    return true;
  }

  // Parse the declared SQL into a join graph + param predicate, then emit a
  // PgInvalidationRule: for the table carrying the param, a direct column read; for a
  // joined table, the back-reference SQL that walks the join path to the param column.
  // This is exactly what the *declared* @PgQuery buys, the join back-reference becomes
  // generated SQL instead of a runtime guess.

  private record QEdge(String a, String ca, String b, String cb) {}

  private record ParsedQuery(Map<String, String> aliasToTable, List<QEdge> edges,
      String paramAlias, String paramCol) {
    String paramTable() { return aliasToTable.get(paramAlias); }
  }

  private static final Pattern FROM_CLAUSE = Pattern.compile(
      "\\bFROM\\b(.*?)(\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bLIMIT\\b|$)", Pattern.CASE_INSENSITIVE);
  private static final Pattern JOIN_CLAUSE = Pattern.compile(
      "(?:LEFT|RIGHT|INNER|FULL|CROSS|OUTER)?\\s*JOIN\\s+(\\w+)\\s+(\\w+)\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern WHERE_CLAUSE = Pattern.compile(
      "\\bWHERE\\b(.*?)(\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bLIMIT\\b|$)", Pattern.CASE_INSENSITIVE);

  /** Parse the subset of SQL @PgQuery uses: FROM/JOIN..ON/WHERE col = $1. Null if not derivable. */
  private static ParsedQuery parseQuery(String sqlRaw) {
    String sql = sqlRaw.replaceAll("\\s+", " ").trim();
    Matcher fm = FROM_CLAUSE.matcher(sql);
    if (!fm.find()) return null;
    String fromClause = fm.group(1).trim();

    Map<String, String> aliasToTable = new LinkedHashMap<>();
    List<QEdge> edges = new ArrayList<>();
    Matcher jm = JOIN_CLAUSE.matcher(fromClause);
    int firstJoin = fromClause.length();
    boolean anyJoin = false;
    while (jm.find()) {
      if (!anyJoin) { firstJoin = jm.start(); anyJoin = true; }
      aliasToTable.put(jm.group(2), jm.group(1));
      edges.add(new QEdge(jm.group(3), jm.group(4), jm.group(5), jm.group(6)));
    }
    String[] base = fromClause.substring(0, firstJoin).trim().split("\\s+");
    String baseTable = base[0];
    String baseAlias = base.length > 1
        ? (base[1].equalsIgnoreCase("AS") && base.length > 2 ? base[2] : base[1])
        : baseTable;
    aliasToTable.put(baseAlias, baseTable);

    String where = "";
    Matcher wm = WHERE_CLAUSE.matcher(sql);
    if (wm.find()) where = wm.group(1);
    Matcher pm = Pattern.compile("(\\w+)\\.(\\w+)\\s*=\\s*\\$1").matcher(where);
    String paramAlias;
    String paramCol;
    if (pm.find()) {
      paramAlias = pm.group(1);
      paramCol = pm.group(2);
    } else {
      Matcher pm2 = Pattern.compile("(\\w+)\\s*=\\s*\\$1").matcher(where);
      if (!pm2.find()) return null;
      paramAlias = baseAlias;
      paramCol = pm2.group(1);
    }
    return new ParsedQuery(aliasToTable, edges, paramAlias, paramCol);
  }

  /** Per changed table: the column to read, and the back-reference SQL prefix (null = direct). */
  private record InvalEntry(String table, String keyColumn, String backRefSqlPrefix) {}

  /**
   * Rejects a self-join (the same table under two aliases) before an invalidation rule is derived.
   * {@link #invalidationEntries} keys entries by table, so a duplicate table would silently collapse
   * to one alias and drop the other role's invalidation path — a WAL change on the omitted alias would
   * fail to wake subscribers, producing stale results. Failing loud at compile time beats that.
   */
  private static void rejectSelfJoin(String name, ParsedQuery q) {
    Map<String, List<String>> aliasesByTable = new LinkedHashMap<>();
    q.aliasToTable().forEach(
        (al, tb) -> aliasesByTable.computeIfAbsent(tb, k -> new ArrayList<>()).add(al));
    for (Map.Entry<String, List<String>> e : aliasesByTable.entrySet()) {
      if (e.getValue().size() > 1) {
        throw new IllegalArgumentException("@PgQuery " + name + ": self-join is not supported for"
            + " live-query invalidation — table '" + e.getKey() + "' appears under aliases "
            + e.getValue() + ". The invalidation rule would cover only one role of the table and"
            + " silently miss changes on the others. Split the query or remove the duplicate join.");
      }
    }
  }

  private static List<InvalEntry> invalidationEntries(ParsedQuery q) {
    Map<String, String> tableToAlias = new LinkedHashMap<>();
    q.aliasToTable().forEach((al, tb) -> tableToAlias.putIfAbsent(tb, al)); // self-joins rejected upstream
    String paramTable = q.paramTable();
    List<InvalEntry> out = new ArrayList<>();
    for (Map.Entry<String, String> e : tableToAlias.entrySet()) {
      String table = e.getKey();
      String alias = e.getValue();
      if (table.equals(paramTable)) {
        out.add(new InvalEntry(table, q.paramCol(), null)); // direct
        continue;
      }
      List<String> path = bfs(q.edges(), alias, q.paramAlias());
      // A connected non-param table always yields a path of length >= 2 (its alias differs from the
      // param alias); a disconnected one yields null. So null is the only "can't resolve" case.
      if (path == null) continue;
      QEdge first = edgeBetween(q.edges(), path.get(0), path.get(1));
      String keyCol = colOn(first, path.get(0));
      String entryCol = colOn(first, path.get(1));
      String ukAlias = path.get(path.size() - 1);
      StringBuilder sql = new StringBuilder();
      sql.append("SELECT ").append(ukAlias).append('.').append(q.paramCol())
          .append(" FROM ").append(q.aliasToTable().get(path.get(1))).append(' ').append(path.get(1));
      for (int i = 2; i < path.size(); i++) {
        String ui = path.get(i);
        QEdge je = edgeBetween(q.edges(), path.get(i - 1), ui);
        sql.append(" JOIN ").append(q.aliasToTable().get(ui)).append(' ').append(ui)
            .append(" ON ").append(je.a()).append('.').append(je.ca())
            .append(" = ").append(je.b()).append('.').append(je.cb());
      }
      sql.append(" WHERE ").append(path.get(1)).append('.').append(entryCol).append(" = ");
      out.add(new InvalEntry(table, keyCol, sql.toString()));
    }
    return out;
  }

  private static List<String> bfs(List<QEdge> edges, String start, String goal) {
    Map<String, String> prev = new LinkedHashMap<>();
    prev.put(start, null);
    List<String> frontier = new ArrayList<>(List.of(start));
    while (!frontier.isEmpty()) {
      List<String> next = new ArrayList<>();
      for (String u : frontier) {
        if (u.equals(goal)) {
          List<String> path = new ArrayList<>();
          for (String at = goal; at != null; at = prev.get(at)) path.add(at);
          java.util.Collections.reverse(path);
          return path;
        }
        for (QEdge e : edges) {
          for (String v : neighbors(e, u)) {
            if (!prev.containsKey(v)) { prev.put(v, u); next.add(v); }
          }
        }
      }
      frontier = next;
    }
    return null;
  }

  private static List<String> neighbors(QEdge e, String u) {
    if (e.a().equals(u)) return List.of(e.b());
    if (e.b().equals(u)) return List.of(e.a());
    return List.of();
  }

  private static QEdge edgeBetween(List<QEdge> edges, String x, String y) {
    for (QEdge e : edges) {
      if ((e.a().equals(x) && e.b().equals(y)) || (e.a().equals(y) && e.b().equals(x))) return e;
    }
    return null;
  }

  private static String colOn(QEdge e, String alias) {
    return e.a().equals(alias) ? e.ca() : e.cb();
  }

  private void emitInvalidation(String pkg, String name, ParsedQuery q) throws IOException {
    String cls = name + "Invalidation";
    StringJoiner tables = new StringJoiner(", ");
    for (String t : new LinkedHashSet<>(q.aliasToTable().values())) tables.add('"' + t + '"');

    StringBuilder b = new StringBuilder();
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    b.append("""
        import java.util.Set;
        import java.util.function.Function;
        import monolith.pg.runtime.PgInvalidate;
        import monolith.pg.runtime.PgInvalidationRule;
        import monolith.pg.runtime.PgPool;

        // GENERATED by monolith.pg.PgTypeProcessor from @PgQuery %s. Do not edit.
        public final class %s implements PgInvalidationRule {

          public static final String QUERY = "%s";
          private static final String[] TABLES = {%s};

          @Override public String query() { return QUERY; }

          @Override public String[] tables() { return TABLES.clone(); }

          @Override
          public Set<String> affectedParams(String changedTable, Function<String, Set<String>> valuesOf, PgPool pool) {
            switch (changedTable) {
        %s      default: return Set.of();
            }
          }
        }
        """.formatted(name, cls, name, tables.toString(), invalidationCases(q)));
    write(pkg, cls, b.toString());
  }

  private String invalidationCases(ParsedQuery q) {
    StringBuilder cases = new StringBuilder();
    for (InvalEntry e : invalidationEntries(q)) {
      if (e.backRefSqlPrefix() == null) {
        cases.append("      case \"").append(e.table()).append("\": return valuesOf.apply(\"")
            .append(e.keyColumn()).append("\");\n");
      } else {
        cases.append("      case \"").append(e.table())
            .append("\": return PgInvalidate.resolve(pool, valuesOf.apply(\"").append(e.keyColumn())
            .append("\"),\n          id -> \"").append(PgTypeProcessor.javaStringLiteral(e.backRefSqlPrefix()))
            .append("'\" + id + \"'\");\n");
      }
    }
    return cases.toString();
  }

  private void write(String pkg, String cls, String content) throws IOException {
    String fq = pkg.isEmpty() ? cls : pkg + "." + cls;
    JavaFileObject jfo = filer.createSourceFile(fq);
    try (Writer w = jfo.openWriter()) {
      w.write(content);
    }
  }
}
