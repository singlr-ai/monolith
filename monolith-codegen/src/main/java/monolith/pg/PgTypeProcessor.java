/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * The Monolith annotation processor. For each {@code @PgType record}, emits a reader, a builder,
 * a SQL DDL fragment, a TypeScript reader, and an entry in {@code schema.lock}, all
 * from the record's component declaration order (the single source of truth).
 *
 * <p>Layout. Fixed-width fields are placed inline in declaration order; variable
 * fields ({@code String}, {@code byte[]}) occupy an {@code int4 offset} +
 * {@code int4 length} header pair with payload bytes appended to the tail. All
 * multi-byte integers are big-endian, 1-byte aligned, Postgres binary natural
 * order, so the field bytes ARE the Postgres wire representation and bridging is
 * a straight copy. Temporal types are stored as Postgres's own epoch-relative
 * {@code int4}/{@code int8} (date = days since 2000-01-01; time = µs since
 * midnight; timestamp[tz] = µs since 2000-01-01); the reader/builder do the
 * conversion to/from {@code java.time}.
 *
 * <p>Nullability. A record with at least one {@code @PgNull} component gets a
 * leading null bitmap of {@code ceil(N/8)} bytes (bit <i>i</i> set = component
 * <i>i</i> is NULL), mirroring Postgres's {@code HEAP_HASNULL} tuple header.
 * A record with no nullable component carries no bitmap and is byte-identical to
 * the pre-nullability layout (so the q5 {@code Document} stays a byte-for-byte
 * regression).
 *
 * <p>Output locations are processor options (absent option = skip that artifact):
 * {@code -Amonolith.sqlDir}, {@code -Amonolith.tsDir}, {@code -Amonolith.lockDir}.
 */
@SupportedAnnotationTypes({"monolith.pg.PgType", "monolith.pg.PgProjection", "monolith.pg.PgQuery"})
@SupportedOptions({"monolith.sqlDir", "monolith.tsDir", "monolith.lockDir"})
public final class PgTypeProcessor extends AbstractProcessor {

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  private enum Kind { FIXED, VARIABLE }

  /** One record component, resolved to its wire kind, offset, and nullability. */
  private record Field(
      int ordinal, String name, String pgType, Kind kind, int width,
      int headerOffset, String javaType, boolean nullable, boolean encrypted, boolean tenant,
      String table) {}

  /** Accumulated across rounds; the aggregate schema.lock is written at the end. */
  private final List<String> lockEntries = new ArrayList<>();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    Messager messager = processingEnv.getMessager();
    for (Element el : roundEnv.getElementsAnnotatedWith(PgType.class)) {
      if (el.getKind() != ElementKind.RECORD) {
        messager.printMessage(Diagnostic.Kind.ERROR, "@PgType is only valid on records", el);
        continue;
      }
      tryGenerate(messager, (TypeElement) el, false, null);
    }
    for (Element el : roundEnv.getElementsAnnotatedWith(PgProjection.class)) {
      if (el.getKind() != ElementKind.RECORD) {
        messager.printMessage(Diagnostic.Kind.ERROR, "@PgProjection is only valid on records", el);
        continue;
      }
      tryGenerate(messager, (TypeElement) el, true, null);
    }
    for (Element el : roundEnv.getElementsAnnotatedWith(PgQuery.class)) {
      if (el.getKind() != ElementKind.RECORD) {
        messager.printMessage(Diagnostic.Kind.ERROR, "@PgQuery is only valid on records", el);
        continue;
      }
      tryGenerate(messager, (TypeElement) el, true, el.getAnnotation(PgQuery.class).value());
    }
    if (roundEnv.processingOver() && !lockEntries.isEmpty()) {
      writeLock();
    }
    return true;
  }

  private void tryGenerate(Messager messager, TypeElement el, boolean projection, String querySql) {
    try {
      generate(el, projection, querySql);
    } catch (IllegalArgumentException ex) {
      messager.printMessage(Diagnostic.Kind.ERROR, ex.getMessage(), el);
    } catch (IOException ex) {
      messager.printMessage(Diagnostic.Kind.ERROR, "codegen I/O failed: " + ex, el);
    }
  }

  private void generate(TypeElement record, boolean projection, String querySql) throws IOException {
    String name = record.getSimpleName().toString();
    String pkg = packageOf(record);
    String pgName;
    if (projection) {
      PgProjection p = record.getAnnotation(PgProjection.class); // null for @PgQuery
      pgName = (p != null && !p.value().isEmpty()) ? p.value() : snake(name);
    } else {
      PgType ann = record.getAnnotation(PgType.class);
      pgName = ann.value().isEmpty() ? snake(name) : ann.value();
    }

    List<? extends RecordComponentElement> components = record.getRecordComponents();
    int n = components.size();

    // A null bitmap is present iff at least one component is nullable, exactly
    // how Postgres carries t_bits only when HEAP_HASNULL. Non-nullable records
    // pay zero overhead and keep their pre-nullability byte layout.
    boolean anyNullable = false;
    for (RecordComponentElement c : components) {
      if (isNullable(c, name)) { anyNullable = true; break; }
    }
    int bitmapBytes = anyNullable ? (n + 7) / 8 : 0;

    List<Field> fields = new ArrayList<>();
    int offset = bitmapBytes;
    int ordinal = 0;
    for (RecordComponentElement c : components) {
      String comp = c.getSimpleName().toString();
      String type = c.asType().toString();
      Mapping m = map(type);
      if (m == null) {
        throw new IllegalArgumentException(
            "@PgType " + name + ": unsupported component type '" + type + "' for '" + comp
                + "'. Supported: UUID, String, byte[], int, long, short, boolean, double, float, "
                + "LocalDate, LocalTime, LocalDateTime, Instant, OffsetDateTime.");
      }
      boolean encrypted = c.getAnnotation(Encrypted.class) != null;
      if (encrypted && !type.equals("java.lang.String")) {
        throw new IllegalArgumentException(
            "@Encrypted is only supported on String components ('" + comp + "' is " + type + ")");
      }
      boolean tenant = c.getAnnotation(Tenant.class) != null;
      if (tenant && !(type.equals("java.lang.String") || type.equals("java.util.UUID"))) {
        throw new IllegalArgumentException(
            "@Tenant is only supported on String or UUID components ('" + comp + "' is " + type + ")");
      }
      // an encrypted field is a variable-length bytea on the wire; the Java type stays String.
      String pgType = encrypted ? "bytea" : m.pgType;
      int headerWidth = m.kind == Kind.FIXED ? m.width : 8; // var: int4 off + int4 len
      fields.add(new Field(ordinal, comp, pgType, m.kind, m.width, offset, type,
          isNullable(c, name), encrypted, tenant, pgName));
      offset += headerWidth;
      ordinal++;
    }
    int fixedSize = offset;

    emitReader(pkg, name, fields, fixedSize, bitmapBytes);
    if (!projection) {
      // A projection is read-only: no table to create, nothing to write.
      emitBuilder(pkg, name, fields, fixedSize, bitmapBytes);
      AccessControlled access = record.getAnnotation(AccessControlled.class);
      if (access != null) {
        if (fields.stream().noneMatch(f -> snake(f.name()).equals(snake(access.id())))) {
          throw new IllegalArgumentException(
              "@AccessControlled id '" + access.id() + "' is not a component of " + name);
        }
        validateWhere(access.where());
        if (access.read().length == 0 && access.write().length == 0 && !access.relationAgnostic()) {
          throw new IllegalArgumentException("@AccessControlled on " + name
              + " is fail-closed by default: declare read/write relations (e.g. read = {\"viewer\"},"
              + " write = {\"editor\"}) so a read relation cannot authorize a write, or set"
              + " relationAgnostic = true to opt into the coarse 'any grant is full access' model");
        }
      }
      emitSql(pgName, fields, record.getAnnotation(Audited.class) != null, access);
    }
    if (querySql != null) {
      emitQuery(pkg, name, querySql);
      if (!new InvalidationEmitter(processingEnv.getFiler()).emit(pkg, name, querySql)) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
            "@PgQuery " + name + ": could not derive an invalidation rule from the SQL (skipped)");
      }
    }
    emitTs(pgName, name, fields, fixedSize, bitmapBytes);
    recordLock(pkg, name, pgName, fields, fixedSize, bitmapBytes, projection);
  }

  /** Generates {@code <Name>Query}: the SQL + a typed run() that binds params,
   *  executes binary, bridges each row, and returns the readers. */
  private void emitQuery(String pkg, String name, String sql) throws IOException {
    String cls = name + "Query";
    String reader = name + "Reader";
    StringBuilder b = new StringBuilder();
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    b.append("""
        import java.lang.foreign.Arena;
        import java.lang.foreign.MemorySegment;
        import java.util.ArrayList;
        import java.util.List;
        import monolith.pg.runtime.Pg;
        import monolith.pg.runtime.PgBridge;
        import monolith.pg.runtime.PgParam;
        import monolith.pg.runtime.PreparedCache;

        // GENERATED by monolith.pg.PgTypeProcessor from @PgQuery %s. Do not edit.
        public final class %s {

          public static final String SQL = "%s";

          private %s() {}

          /** Binds the positional params, runs the query in binary via a cached prepared statement,
           *  returns the rows. */
          public static List<%s> run(Arena arena, MemorySegment conn, Object... params) {
            PgParam.Bound p = PgParam.bind(arena, params);
            MemorySegment res = PreparedCache.execute(arena, conn, SQL, p).getOrThrow();
            try {
              int n = Pg.ntuples(res);
              List<%s> out = new ArrayList<>(n);
              for (int r = 0; r < n; r++) {
                out.add(new %s(MemorySegment.ofArray(
                    PgBridge.row(res, r, %s.FIXED_SIZE, %s.OFFSET, %s.WIDTH))));
              }
              return out;
            } finally {
              Pg.clear(res);
            }
          }
        }
        """.formatted(name, cls, javaStringLiteral(sql), cls,
            reader, reader, reader, reader, reader, reader));
    write(pkg, cls, b.toString());
  }

  /** Escapes SQL into the body of a Java double-quoted string literal. */
  static String javaStringLiteral(String sql) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  private boolean isNullable(RecordComponentElement c, String recordName) {
    boolean marked = c.getAnnotation(PgNull.class) != null;
    if (!marked) return false;
    if (isPrimitive(c.asType().toString())) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "@PgNull on primitive '" + c.getSimpleName() + "' in @PgType " + recordName
              + ", a primitive can never be null; use the boxed type.", c);
      return false;
    }
    return true;
  }

  // ======================= type mapping ==================================

  private record Mapping(String pgType, Kind kind, int width) {}

  private static Mapping map(String javaType) {
    return switch (javaType) {
      case "java.util.UUID" -> new Mapping("uuid", Kind.FIXED, 16);
      case "java.lang.String" -> new Mapping("text", Kind.VARIABLE, -1);
      case "byte[]" -> new Mapping("bytea", Kind.VARIABLE, -1);
      case "java.math.BigDecimal" -> new Mapping("numeric", Kind.VARIABLE, -1);
      case "monolith.pg.Json" -> new Mapping("jsonb", Kind.VARIABLE, -1);
      case "int[]" -> new Mapping("int4[]", Kind.VARIABLE, -1);
      case "long[]" -> new Mapping("int8[]", Kind.VARIABLE, -1);
      case "java.lang.String[]" -> new Mapping("text[]", Kind.VARIABLE, -1);
      case "int", "java.lang.Integer" -> new Mapping("int4", Kind.FIXED, 4);
      case "long", "java.lang.Long" -> new Mapping("int8", Kind.FIXED, 8);
      case "short", "java.lang.Short" -> new Mapping("int2", Kind.FIXED, 2);
      case "boolean", "java.lang.Boolean" -> new Mapping("bool", Kind.FIXED, 1);
      case "double", "java.lang.Double" -> new Mapping("float8", Kind.FIXED, 8);
      case "float", "java.lang.Float" -> new Mapping("float4", Kind.FIXED, 4);
      case "java.time.LocalDate" -> new Mapping("date", Kind.FIXED, 4);
      case "java.time.LocalTime" -> new Mapping("time", Kind.FIXED, 8);
      case "java.time.LocalDateTime" -> new Mapping("timestamp", Kind.FIXED, 8);
      case "java.time.Instant" -> new Mapping("timestamptz", Kind.FIXED, 8);
      case "java.time.OffsetDateTime" -> new Mapping("timestamptz", Kind.FIXED, 8);
      default -> null;
    };
  }

  private static boolean isPrimitive(String t) {
    return switch (t) {
      case "int", "long", "short", "boolean", "double", "float", "byte", "char" -> true;
      default -> false;
    };
  }

  // ======================= Java reader ===================================

  private void emitReader(String pkg, String name, List<Field> fields, int fixedSize, int bitmap)
      throws IOException {
    String cls = name + "Reader";
    StringBuilder b = new StringBuilder();
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    b.append(imports(fields, false));
    b.append("\n// GENERATED by monolith.pg.PgTypeProcessor from @PgType ").append(name)
        .append(". Do not edit.\n");
    b.append("public record ").append(cls).append("(MemorySegment seg) {\n\n");
    b.append(layoutConstants(fields, fixedSize, bitmap, true));

    if (bitmap > 0) {
      b.append("""
            private boolean isNull(int ordinal) {
              int v = seg.get(ValueLayout.JAVA_BYTE, ordinal >> 3) & 0xff;
              return (v & (1 << (ordinal & 7))) != 0;
            }

        """);
    }
    for (Field f : fields) b.append(readerAccessor(f));
    b.append("""
          public byte[] bytes() { return seg.toArray(ValueLayout.JAVA_BYTE); }
        }
        """);
    write(pkg, cls, b.toString());
  }

  private static String readerAccessor(Field f) {
    int o = f.headerOffset();
    String decode = f.encrypted()
        ? varDecode(o, "monolith.pg.runtime.PgCrypto.decrypt(_raw, " + cryptoContext(f) + ")") // decrypt on read
        : switch (f.javaType()) {
      case "java.util.UUID" ->
          "return new UUID(seg.get(BE_LONG, %d), seg.get(BE_LONG, %d));".formatted(o, o + 8);
      case "java.lang.String" -> ("int off = seg.get(BE_INT, %d);\n"
          + "    int len = seg.get(BE_INT, %d);\n"
          + "    return new String(seg.asSlice(off, len).toArray(ValueLayout.JAVA_BYTE), "
          + "StandardCharsets.UTF_8);").formatted(o, o + 4);
      case "byte[]" -> ("int off = seg.get(BE_INT, %d);\n"
          + "    int len = seg.get(BE_INT, %d);\n"
          + "    return seg.asSlice(off, len).toArray(ValueLayout.JAVA_BYTE);").formatted(o, o + 4);
      case "java.math.BigDecimal" -> varDecode(o, "PgCodec.decodeNumeric(_raw)");
      case "monolith.pg.Json" -> varDecode(o, "new Json(PgCodec.decodeJsonb(_raw))");
      case "int[]" -> varDecode(o, "PgCodec.decodeIntArray(_raw)");
      case "long[]" -> varDecode(o, "PgCodec.decodeLongArray(_raw)");
      case "java.lang.String[]" -> varDecode(o, "PgCodec.decodeTextArray(_raw)");
      case "int", "java.lang.Integer" -> "return seg.get(BE_INT, %d);".formatted(o);
      case "long", "java.lang.Long" -> "return seg.get(BE_LONG, %d);".formatted(o);
      case "short", "java.lang.Short" -> "return seg.get(BE_SHORT, %d);".formatted(o);
      case "boolean", "java.lang.Boolean" ->
          "return seg.get(ValueLayout.JAVA_BYTE, %d) != 0;".formatted(o);
      case "double", "java.lang.Double" -> "return seg.get(BE_DOUBLE, %d);".formatted(o);
      case "float", "java.lang.Float" -> "return seg.get(BE_FLOAT, %d);".formatted(o);
      case "java.time.LocalDate" ->
          "return LocalDate.ofEpochDay(PG_EPOCH_DAYS + seg.get(BE_INT, %d));".formatted(o);
      case "java.time.LocalTime" ->
          "return LocalTime.ofNanoOfDay(seg.get(BE_LONG, %d) * 1000L);".formatted(o);
      case "java.time.LocalDateTime" -> ("long u = seg.get(BE_LONG, %d);\n"
          + "    long s = Math.floorDiv(u, 1_000_000L) + PG_EPOCH_SECONDS;\n"
          + "    int ns = (int) (Math.floorMod(u, 1_000_000L) * 1000L);\n"
          + "    return LocalDateTime.ofEpochSecond(s, ns, ZoneOffset.UTC);").formatted(o);
      case "java.time.Instant" -> ("long u = seg.get(BE_LONG, %d);\n"
          + "    long s = Math.floorDiv(u, 1_000_000L) + PG_EPOCH_SECONDS;\n"
          + "    long ns = Math.floorMod(u, 1_000_000L) * 1000L;\n"
          + "    return Instant.ofEpochSecond(s, ns);").formatted(o);
      case "java.time.OffsetDateTime" -> ("long u = seg.get(BE_LONG, %d);\n"
          + "    long s = Math.floorDiv(u, 1_000_000L) + PG_EPOCH_SECONDS;\n"
          + "    long ns = Math.floorMod(u, 1_000_000L) * 1000L;\n"
          + "    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(s, ns), ZoneOffset.UTC);")
          .formatted(o);
      default -> throw new IllegalStateException(f.javaType());
    };
    String returnType = simpleType(f.javaType());
    if (f.nullable()) {
      return "  public %s %s() {\n    if (isNull(%d)) return null;\n    %s\n  }\n\n"
          .formatted(returnType, f.name(), f.ordinal(), decode);
    }
    return "  public %s %s() {\n    %s\n  }\n\n".formatted(returnType, f.name(), decode);
  }

  /**
   * The Java string literal for an encrypted field's AAD context — {@code "table.column"} — passed to
   * {@code PgCrypto.encrypt/decrypt} so the ciphertext is bound to the field it lives in and cannot be
   * substituted from another column or table. The context does not bind row identity, so a same-column
   * row swap is not detected unless the caller includes a row identifier in the context.
   */
  private static String cryptoContext(Field f) {
    return "\"" + javaStringLiteral(f.table() + "." + snake(f.name())) + "\"";
  }

  /** Reader body for a variable type whose tail bytes are decoded via a runtime codec. */
  private static String varDecode(int o, String decodeExpr) {
    return ("int off = seg.get(BE_INT, %d);\n"
        + "    int len = seg.get(BE_INT, %d);\n"
        + "    byte[] _raw = seg.asSlice(off, len).toArray(ValueLayout.JAVA_BYTE);\n"
        + "    return %s;").formatted(o, o + 4, decodeExpr);
  }

  /** Builder expression yielding the wire bytes for a variable component's value. */
  private static String varEncodeExpr(Field f) {
    String n = f.name();
    if (f.encrypted()) {
      return "monolith.pg.runtime.PgCrypto.encrypt(" + n + ", " + cryptoContext(f) + ")"; // encrypt on write
    }
    return switch (f.javaType()) {
      case "java.lang.String" -> n + ".getBytes(StandardCharsets.UTF_8)";
      case "byte[]" -> n;
      case "java.math.BigDecimal" -> "PgCodec.encodeNumeric(" + n + ")";
      case "monolith.pg.Json" -> "PgCodec.encodeJsonb(" + n + ".value())";
      case "int[]" -> "PgCodec.encodeIntArray(" + n + ")";
      case "long[]" -> "PgCodec.encodeLongArray(" + n + ")";
      case "java.lang.String[]" -> "PgCodec.encodeTextArray(" + n + ")";
      default -> throw new IllegalStateException("not a variable type: " + f.javaType());
    };
  }

  // ======================= Java builder ==================================

  private void emitBuilder(String pkg, String name, List<Field> fields, int fixedSize, int bitmap)
      throws IOException {
    String cls = name + "Builder";
    String reader = name + "Reader";

    StringJoiner params = new StringJoiner(", ");
    for (Field f : fields) params.add(simpleType(f.javaType()) + " " + f.name());

    StringBuilder body = new StringBuilder();
    // require non-null for NOT NULL reference/boxed fields (primitives can't be null)
    for (Field f : fields) {
      if (!f.nullable() && !isPrimitive(f.javaType())) {
        body.append("    java.util.Objects.requireNonNull(%s, \"%s is NOT NULL\");\n"
            .formatted(f.name(), f.name()));
      }
    }
    // pre-encode variable-length fields so we can size the segment (null => 0 bytes)
    boolean anyVar = false;
    for (Field f : fields) {
      if (f.kind() == Kind.VARIABLE) {
        anyVar = true;
        String enc = varEncodeExpr(f);
        if (f.nullable()) {
          body.append("    byte[] _%s = %s == null ? EMPTY : %s;\n"
              .formatted(f.name(), f.name(), enc));
        } else {
          body.append("    byte[] _%s = %s;\n".formatted(f.name(), enc));
        }
      }
    }
    if (anyVar) body.append("    int _total = FIXED_SIZE");
    else body.append("    int _total = FIXED_SIZE");
    for (Field f : fields) {
      if (f.kind() == Kind.VARIABLE) body.append(" + _").append(f.name()).append(".length");
    }
    body.append(";\n");
    body.append("    MemorySegment seg = MemorySegment.ofArray(new byte[_total]);\n");

    // fixed-width writes (nullable => set bit instead of writing)
    for (Field f : fields) {
      if (f.kind() != Kind.FIXED) continue;
      if (f.nullable()) {
        body.append("    if (%s == null) setNull(seg, %d); else %s\n"
            .formatted(f.name(), f.ordinal(), builderFixedWrite(f).trim()));
      } else {
        body.append("    ").append(builderFixedWrite(f).trim()).append('\n');
      }
    }
    // variable writes append to a running tail
    if (anyVar) {
      body.append("    int _tail = FIXED_SIZE;\n");
      for (Field f : fields) {
        if (f.kind() != Kind.VARIABLE) continue;
        int o = f.headerOffset();
        String write = ("seg.set(BE_INT, %d, _tail);\n"
            + "      seg.set(BE_INT, %d, _%s.length);\n"
            + "      MemorySegment.copy(MemorySegment.ofArray(_%s), 0, seg, _tail, _%s.length);\n"
            + "      _tail += _%s.length;")
            .formatted(o, o + 4, f.name(), f.name(), f.name(), f.name());
        if (f.nullable()) {
          body.append("    if (%s == null) { setNull(seg, %d); } else {\n      %s\n    }\n"
              .formatted(f.name(), f.ordinal(), write));
        } else {
          body.append("    ").append(write).append('\n');
        }
      }
    }
    body.append("    return new %s(seg);\n".formatted(reader));

    StringBuilder b = new StringBuilder();
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    b.append(imports(fields, true));
    b.append("\n// GENERATED by monolith.pg.PgTypeProcessor from @PgType ").append(name)
        .append(". Do not edit.\n");
    b.append("public final class ").append(cls).append(" {\n\n");
    b.append("  private static final int FIXED_SIZE = ").append(reader).append(".FIXED_SIZE;\n");
    b.append(epochAndLayouts(fields));
    if (bitmap > 0) {
      b.append("  private static final byte[] EMPTY = new byte[0];\n");
      b.append("""
            private static void setNull(MemorySegment seg, int ordinal) {
              int i = ordinal >> 3;
              seg.set(ValueLayout.JAVA_BYTE, i,
                  (byte) ((seg.get(ValueLayout.JAVA_BYTE, i) & 0xff) | (1 << (ordinal & 7))));
            }
        """);
    } else if (anyVar) {
      b.append("  private static final byte[] EMPTY = new byte[0];\n");
    }
    b.append("\n  private ").append(cls).append("() {}\n\n");
    b.append("  public static %s of(%s) {\n%s  }\n}\n".formatted(reader, params, body));
    write(pkg, cls, b.toString());
  }

  private static String builderFixedWrite(Field f) {
    int o = f.headerOffset();
    String n = f.name();
    return switch (f.javaType()) {
      case "java.util.UUID" ->
          "{ seg.set(BE_LONG, %d, %s.getMostSignificantBits()); seg.set(BE_LONG, %d, %s.getLeastSignificantBits()); }"
              .formatted(o, n, o + 8, n);
      case "int", "java.lang.Integer" -> "seg.set(BE_INT, %d, %s);".formatted(o, n);
      case "long", "java.lang.Long" -> "seg.set(BE_LONG, %d, %s);".formatted(o, n);
      case "short", "java.lang.Short" -> "seg.set(BE_SHORT, %d, %s);".formatted(o, n);
      case "boolean", "java.lang.Boolean" ->
          "seg.set(ValueLayout.JAVA_BYTE, %d, (byte) (%s ? 1 : 0));".formatted(o, n);
      case "double", "java.lang.Double" -> "seg.set(BE_DOUBLE, %d, %s);".formatted(o, n);
      case "float", "java.lang.Float" -> "seg.set(BE_FLOAT, %d, %s);".formatted(o, n);
      case "java.time.LocalDate" ->
          "seg.set(BE_INT, %d, (int) (%s.toEpochDay() - PG_EPOCH_DAYS));".formatted(o, n);
      case "java.time.LocalTime" ->
          "seg.set(BE_LONG, %d, %s.toNanoOfDay() / 1000L);".formatted(o, n);
      case "java.time.LocalDateTime" ->
          ("seg.set(BE_LONG, %d, (%s.toEpochSecond(ZoneOffset.UTC) - PG_EPOCH_SECONDS) * 1_000_000L"
              + " + %s.getNano() / 1000L);").formatted(o, n, n);
      case "java.time.Instant" ->
          ("seg.set(BE_LONG, %d, (%s.getEpochSecond() - PG_EPOCH_SECONDS) * 1_000_000L"
              + " + %s.getNano() / 1000L);").formatted(o, n, n);
      case "java.time.OffsetDateTime" ->
          ("{ Instant _i = %s.toInstant(); seg.set(BE_LONG, %d,"
              + " (_i.getEpochSecond() - PG_EPOCH_SECONDS) * 1_000_000L + _i.getNano() / 1000L); }")
              .formatted(n, o);
      default -> throw new IllegalStateException(f.javaType());
    };
  }

  // ======================= shared generated bits =========================

  /** BE value layouts + (when temporal present) the Postgres epoch constants. */
  private static String epochAndLayouts(List<Field> fields) {
    StringBuilder b = new StringBuilder();
    b.append("""
          private static final ValueLayout.OfLong BE_LONG =
              ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
          private static final ValueLayout.OfInt BE_INT =
              ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
          private static final ValueLayout.OfShort BE_SHORT =
              ValueLayout.JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
          private static final ValueLayout.OfDouble BE_DOUBLE =
              ValueLayout.JAVA_DOUBLE.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
          private static final ValueLayout.OfFloat BE_FLOAT =
              ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
        """);
    if (hasTemporal(fields)) {
      b.append("  private static final long PG_EPOCH_DAYS = 10957L;        // 2000-01-01\n");
      b.append("  private static final long PG_EPOCH_SECONDS = 946684800L; // 2000-01-01T00:00:00Z\n");
    }
    return b.toString();
  }

  /** Reader gets the layouts/epoch PLUS the public introspection metadata arrays. */
  private String layoutConstants(List<Field> fields, int fixedSize, int bitmap, boolean reader) {
    StringBuilder b = new StringBuilder();
    b.append("  public static final int FIXED_SIZE = ").append(fixedSize).append(";\n");
    b.append("  public static final int NULL_BITMAP_BYTES = ").append(bitmap).append(";\n");
    b.append(epochAndLayouts(fields));
    // metadata arrays let a generic bridge / param-builder work off the schema
    b.append("  public static final String[] COLUMNS = ").append(strArr(fields, Field::name)).append(";\n");
    b.append("  public static final String[] PG_COLUMNS = ")
        .append(strArr(fields, f -> snake(f.name()))).append(";\n");
    b.append("  public static final String[] PG_TYPE = ").append(strArr(fields, Field::pgType)).append(";\n");
    b.append("  public static final int[] OFFSET = ").append(intArr(fields, Field::headerOffset)).append(";\n");
    b.append("  public static final int[] WIDTH = ")
        .append(intArr(fields, f -> f.kind() == Kind.FIXED ? f.width() : -1)).append(";\n");
    b.append("  public static final boolean[] NULLABLE = ").append(boolArr(fields)).append(";\n\n");
    return b.toString();
  }

  // ======================= SQL ===========================================

  private void emitSql(String pgName, List<Field> fields, boolean audited, AccessControlled access)
      throws IOException {
    String dir = processingEnv.getOptions().get("monolith.sqlDir");
    if (dir == null) return;
    StringBuilder b = new StringBuilder();
    b.append("-- GENERATED by monolith.pg.PgTypeProcessor. Do not edit.\n");
    b.append("-- Composite type + table for @PgType ").append(pgName).append(".\n");
    b.append("-- Column names are snake_cased: Postgres folds unquoted identifiers to\n");
    b.append("-- lowercase, and the wire layout maps by ordinal, so the column name is\n");
    b.append("-- free to differ from the record component name.\n\n");
    // Composite type carries no NOT NULL (not permitted in a composite type).
    b.append("CREATE TYPE ").append(pgName).append("_t AS (\n");
    appendColumns(b, fields, false);
    b.append(");\n\n");
    // Table carries NOT NULL for non-nullable components.
    b.append("CREATE TABLE ").append(pgName).append(" (\n");
    appendColumns(b, fields, true);
    b.append(");\n");
    Field tenant = fields.stream().filter(Field::tenant).findFirst().orElse(null);
    if (access != null) {
      // Access control owns the policies so it can compose tenant isolation as RESTRICTIVE (AND),
      // since two PERMISSIVE policies would OR.
      String resource = access.resource().isEmpty() ? pgName : access.resource();
      appendAccessRls(b, pgName, resource, snake(access.id()), tenant, access.where(),
          List.of(access.read()), List.of(access.write()));
    } else if (tenant != null) {
      appendTenantRls(b, pgName, tenant);
    }
    if (audited) {
      appendAudit(b, pgName);
    }
    Path out = Path.of(dir, pgName + ".sql");
    Files.createDirectories(out.getParent());
    Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
  }

  /** Forced row-level security confining every row to {@code current_setting('app.tenant')}. */
  private static void appendTenantRls(StringBuilder b, String table, Field tenant) {
    String col = snake(tenant.name());
    String match = col + " = current_setting('app.tenant', true)::" + tenant.pgType();
    b.append("\n-- Tenant isolation: forced row-level security on ").append(col).append(".\n");
    b.append("ALTER TABLE ").append(table).append(" ENABLE ROW LEVEL SECURITY;\n");
    b.append("ALTER TABLE ").append(table).append(" FORCE ROW LEVEL SECURITY;\n");
    b.append("CREATE POLICY ").append(table).append("_tenant_isolation ON ").append(table)
        .append("\n  USING (").append(match).append(")\n  WITH CHECK (").append(match).append(");\n");
  }

  /**
   * Forced row-level security keyed on the grant tables: a row is allowed when an {@code allow} grant
   * matches the actor (or a role it holds) and no {@code deny} does. When a tenant column is present it
   * is emitted as a RESTRICTIVE policy so it ANDs with the grant check (PERMISSIVE policies would OR).
   * The grant and role tables come from {@code Grants.install}.
   */
  private static void appendAccessRls(
      StringBuilder b, String table, String resource, String idCol, Field tenant, String where,
      List<String> read, List<String> write) {
    b.append("\n-- Access control: forced row-level security keyed on grants for resource '")
        .append(sqlLiteral(resource).replaceAll("\\s+", " ")) // single-line, can't break out of the -- comment
        .append("'. Requires the tables from Grants.install.\n");
    b.append("ALTER TABLE ").append(table).append(" ENABLE ROW LEVEL SECURITY;\n");
    b.append("ALTER TABLE ").append(table).append(" FORCE ROW LEVEL SECURITY;\n");
    if (read.isEmpty() && write.isEmpty()) {
      // Relation-agnostic: a single FOR ALL policy where any allow grant authorizes every command.
      String predicate = withWhere(where, accessPredicate(resource, idCol, List.of()));
      b.append("CREATE POLICY ").append(table).append("_access ON ").append(table)
          .append("\n  USING (").append(predicate).append(")\n  WITH CHECK (").append(predicate).append(");\n");
    } else {
      // Relation-aware: one policy per command, so a relation only authorizes its mapped action — a
      // read-granting relation cannot satisfy a write. An action with no relations is fail-closed.
      String readPredicate = commandPredicate(where, resource, idCol, read);
      String writePredicate = commandPredicate(where, resource, idCol, write);
      b.append("CREATE POLICY ").append(table).append("_select ON ").append(table)
          .append(" FOR SELECT\n  USING (").append(readPredicate).append(");\n");
      b.append("CREATE POLICY ").append(table).append("_insert ON ").append(table)
          .append(" FOR INSERT\n  WITH CHECK (").append(writePredicate).append(");\n");
      b.append("CREATE POLICY ").append(table).append("_update ON ").append(table)
          .append(" FOR UPDATE\n  USING (").append(writePredicate).append(")")
          .append("\n  WITH CHECK (").append(writePredicate).append(");\n");
      b.append("CREATE POLICY ").append(table).append("_delete ON ").append(table)
          .append(" FOR DELETE\n  USING (").append(writePredicate).append(");\n");
    }
    if (tenant != null) {
      String match = snake(tenant.name()) + " = current_setting('app.tenant', true)::" + tenant.pgType();
      b.append("-- Tenant isolation as RESTRICTIVE, so a row must both belong to the tenant and be granted.\n");
      b.append("CREATE POLICY ").append(table).append("_tenant_isolation ON ").append(table)
          .append(" AS RESTRICTIVE\n  USING (").append(match).append(")\n  WITH CHECK (").append(match).append(");\n");
    }
  }

  /** The attribute condition (if any) AND-ed onto the grant {@code predicate}. */
  private static String withWhere(String where, String predicate) {
    return where.isEmpty() ? predicate : "(" + where + ")\n    AND " + predicate;
  }

  /**
   * A single command's policy predicate. When the command has no granting relations it is fail-closed
   * ({@code false}); otherwise it is the grant check (with the attribute condition AND-ed in).
   */
  private static String commandPredicate(String where, String resource, String idCol, List<String> relations) {
    return relations.isEmpty() ? "false" : withWhere(where, accessPredicate(resource, idCol, relations));
  }

  /**
   * The allow-and-not-deny predicate over the grant tables for {@code resource}, keyed on {@code idCol}.
   * When {@code relations} is non-empty, both the allow and the deny checks are scoped to those relations,
   * so a grant (or deny) only counts for the action its relation is mapped to.
   */
  private static String accessPredicate(String resource, String idCol, List<String> relations) {
    String res = sqlLiteral(resource);
    String principals = "(SELECT current_setting('app.actor', true)\n"
        + "        UNION ALL SELECT role FROM monolith_role_member"
        + " WHERE actor = current_setting('app.actor', true))";
    String allowRelation = relations.isEmpty() ? "" : " AND g.relation IN (" + relationList(relations) + ")";
    String denyRelation = relations.isEmpty() ? "" : " AND d.relation IN (" + relationList(relations) + ")";
    return "EXISTS (SELECT 1 FROM monolith_grant g\n"
        + "      WHERE g.resource = '" + res + "' AND g.resource_id IN (" + idCol + "::text, '*')"
        + " AND g.effect = 'allow'" + allowRelation + "\n"
        + "        AND g.principal IN " + principals + ")\n"
        + "    AND NOT EXISTS (SELECT 1 FROM monolith_grant d\n"
        + "      WHERE d.resource = '" + res + "' AND d.resource_id IN (" + idCol + "::text, '*')"
        + " AND d.effect = 'deny'" + denyRelation + "\n"
        + "        AND d.principal IN " + principals + ")";
  }

  /** Comma-separated, SQL-escaped relation literals for an {@code IN (...)} list. */
  private static String relationList(List<String> relations) {
    return relations.stream()
        .map(r -> "'" + sqlLiteral(r) + "'")
        .collect(java.util.stream.Collectors.joining(", "));
  }

  /** Escapes a value for embedding inside a single-quoted SQL string literal (doubles each quote). */
  private static String sqlLiteral(String s) {
    return s.replace("'", "''");
  }

  /** Rejects a {@code where} predicate that could break out of the policy expression. */
  private static void validateWhere(String where) {
    if (where.isEmpty()) {
      return;
    }
    for (String banned : java.util.List.of(";", "--", "/*")) {
      if (where.contains(banned)) {
        throw new IllegalArgumentException(
            "@AccessControlled where must not contain ';' or a SQL comment: " + where);
      }
    }
    int depth = 0;
    for (int i = 0; i < where.length(); i++) {
      char c = where.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && --depth < 0) {
        throw new IllegalArgumentException("@AccessControlled where has unbalanced parentheses: " + where);
      }
    }
    if (depth != 0) {
      throw new IllegalArgumentException("@AccessControlled where has unbalanced parentheses: " + where);
    }
  }

  /** Append-only audit table + a write-capturing trigger + an immutability guard. */
  private static void appendAudit(StringBuilder b, String table) {
    b.append("\n-- Immutable audit trail for ").append(table).append(".\n");
    b.append("CREATE TABLE ").append(table).append("_audit (\n")
        .append("  audit_id  bigserial PRIMARY KEY,\n")
        .append("  logged_at timestamptz NOT NULL DEFAULT now(),\n")
        .append("  actor     text NOT NULL,\n")
        .append("  action    text NOT NULL,\n")
        .append("  old_row   jsonb,\n")
        .append("  new_row   jsonb\n);\n");
    // SECURITY DEFINER: the trigger writes the audit row as the (privileged) owner, so the
    // application role needs no direct access to the audit table and cannot forge entries.
    // Hardened search_path handling: the function pins `search_path = pg_catalog, pg_temp` (so the
    // built-ins it calls always resolve from pg_catalog and pg_temp is searched last, not first) and
    // writes through a fully schema-qualified target derived from the trigger's own TG_TABLE_SCHEMA via
    // format(%I.%I). Name resolution therefore cannot be steered by the caller's search_path or by a
    // pg_temp decoy of the audit table — the classic SECURITY DEFINER escalation/audit-evasion class.
    b.append("CREATE FUNCTION ").append(table).append("_audit_record() RETURNS trigger\n")
        .append("  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$\nBEGIN\n")
        .append("  EXECUTE format('INSERT INTO %I.%I (actor, action, old_row, new_row)"
            + " VALUES ($1, $2, $3, $4)', TG_TABLE_SCHEMA, TG_TABLE_NAME || '_audit')\n")
        .append("    USING coalesce(current_setting('app.actor', true), 'unknown'), TG_OP,\n")
        .append("          to_jsonb(OLD), to_jsonb(NEW);\n  RETURN NULL;\nEND $$;\n");
    b.append("CREATE TRIGGER ").append(table).append("_audit_write\n")
        .append("  AFTER INSERT OR UPDATE OR DELETE ON ").append(table)
        .append("\n  FOR EACH ROW EXECUTE FUNCTION ").append(table).append("_audit_record();\n");
    b.append("CREATE FUNCTION ").append(table).append("_audit_immutable() RETURNS trigger\n")
        .append("  LANGUAGE plpgsql AS $$\nBEGIN\n")
        .append("  RAISE EXCEPTION 'audit table ").append(table).append("_audit is append-only';\n")
        .append("END $$;\n");
    b.append("CREATE TRIGGER ").append(table).append("_audit_guard\n")
        .append("  BEFORE UPDATE OR DELETE ON ").append(table).append("_audit")
        .append("\n  FOR EACH ROW EXECUTE FUNCTION ").append(table).append("_audit_immutable();\n");
  }

  private static void appendColumns(StringBuilder b, List<Field> fields, boolean constraints) {
    for (int i = 0; i < fields.size(); i++) {
      Field f = fields.get(i);
      b.append("  ").append(snake(f.name())).append(' ').append(f.pgType());
      if (constraints && !f.nullable()) b.append(" NOT NULL");
      b.append(i < fields.size() - 1 ? ",\n" : "\n");
    }
  }

  // ======================= TypeScript ====================================

  private void emitTs(String pgName, String javaName, List<Field> fields, int fixedSize, int bitmap)
      throws IOException {
    String dir = processingEnv.getOptions().get("monolith.tsDir");
    if (dir == null) return;
    String cls = javaName + "Reader";
    StringBuilder b = new StringBuilder();
    b.append("// GENERATED by monolith.pg.PgTypeProcessor from @PgType ").append(javaName)
        .append(". Do not edit.\n");
    b.append("// Reader over the same Postgres binary layout the Java reader sees ")
        .append("(big-endian).\n");
    b.append("// Temporal accessors return the raw Postgres epoch integer (date: int4 days\n");
    b.append("// since 2000-01-01; time/timestamp[tz]: bigint µs); ISO formatting is deferred.\n\n");
    b.append(tsImports(fields));
    b.append("const FIXED_SIZE = ").append(fixedSize).append(";\n");
    b.append("const NULL_BITMAP_BYTES = ").append(bitmap).append(";\n");
    b.append("const td = new TextDecoder();\n\n");
    b.append("function uuidAt(v: DataView, o: number): string {\n");
    b.append("  let s = '';\n");
    b.append("  for (let i = 0; i < 16; i++) s += v.getUint8(o + i).toString(16).padStart(2, '0');\n");
    b.append("  return `${s.slice(0,8)}-${s.slice(8,12)}-${s.slice(12,16)}-${s.slice(16,20)}-${s.slice(20)}`;\n");
    b.append("}\n\n");
    b.append("export class ").append(cls).append(" {\n");
    b.append("  private readonly v: DataView;\n");
    b.append("  private readonly buf: Uint8Array;\n");
    b.append("  constructor(buf: Uint8Array) {\n");
    b.append("    this.buf = buf;\n");
    b.append("    this.v = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);\n");
    b.append("  }\n");
    b.append("  static from(buf: Uint8Array): ").append(cls).append(" { return new ")
        .append(cls).append("(buf); }\n");
    if (bitmap > 0) {
      b.append("  isNull(ordinal: number): boolean {\n");
      b.append("    return (this.v.getUint8(ordinal >> 3) & (1 << (ordinal & 7))) !== 0;\n");
      b.append("  }\n");
    }
    b.append('\n');
    for (Field f : fields) b.append(tsAccessor(f, bitmap > 0));
    b.append("}\n");
    Path out = Path.of(dir, pgName + ".ts");
    Files.createDirectories(out.getParent());
    Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
  }

  private static String tsAccessor(Field f, boolean hasBitmap) {
    int o = f.headerOffset();
    String expr = f.encrypted()
        ? tsSlice(o, "this.buf.subarray(off, off + len)") // ciphertext stays bytes for the client
        : switch (f.javaType()) {
      case "java.util.UUID" -> "return uuidAt(this.v, %d);".formatted(o);
      case "java.lang.String" -> tsSlice(o, "td.decode(this.buf.subarray(off, off + len))");
      case "byte[]" -> tsSlice(o, "this.buf.subarray(off, off + len)"); // genuine bytes
      case "monolith.pg.Json" -> tsSlice(o, "decodeJsonb(this.buf.subarray(off, off + len))");
      case "java.math.BigDecimal" -> tsSlice(o, "decodeNumeric(this.buf.subarray(off, off + len))");
      case "int[]" -> tsSlice(o, "decodeInt4Array(this.buf.subarray(off, off + len))");
      case "long[]" -> tsSlice(o, "decodeInt8Array(this.buf.subarray(off, off + len))");
      case "java.lang.String[]" -> tsSlice(o, "decodeTextArray(this.buf.subarray(off, off + len))");
      case "int", "java.lang.Integer" -> "return this.v.getInt32(%d, false);".formatted(o);
      case "long", "java.lang.Long" -> "return this.v.getBigInt64(%d, false);".formatted(o);
      case "short", "java.lang.Short" -> "return this.v.getInt16(%d, false);".formatted(o);
      case "boolean", "java.lang.Boolean" -> "return this.v.getUint8(%d) !== 0;".formatted(o);
      case "double", "java.lang.Double" -> "return this.v.getFloat64(%d, false);".formatted(o);
      case "float", "java.lang.Float" -> "return this.v.getFloat32(%d, false);".formatted(o);
      case "java.time.LocalDate" -> "return this.v.getInt32(%d, false);".formatted(o);
      case "java.time.LocalTime", "java.time.LocalDateTime", "java.time.Instant",
          "java.time.OffsetDateTime" -> "return this.v.getBigInt64(%d, false);".formatted(o);
      default -> throw new IllegalStateException(f.javaType());
    };
    String tsType = f.encrypted() ? "Uint8Array" : tsType(f.javaType());
    if (f.nullable()) {
      return "  %s(): %s | null {\n    if (this.isNull(%d)) return null;\n    %s\n  }\n\n"
          .formatted(f.name(), tsType, f.ordinal(), expr);
    }
    return "  %s(): %s {\n    %s\n  }\n\n".formatted(f.name(), tsType, expr);
  }

  /** A TS accessor body that reads the variable-length header, then returns {@code expr}. */
  private static String tsSlice(int o, String expr) {
    return ("const off = this.v.getInt32(%d, false);\n"
        + "    const len = this.v.getInt32(%d, false);\n"
        + "    return %s;").formatted(o, o + 4, expr);
  }

  private static String tsType(String javaType) {
    return switch (javaType) {
      case "java.util.UUID", "java.lang.String" -> "string";
      case "byte[]" -> "Uint8Array";
      case "monolith.pg.Json" -> "unknown";
      case "java.math.BigDecimal" -> "string";
      case "int[]" -> "number[]";
      case "long[]" -> "bigint[]";
      case "java.lang.String[]" -> "(string | null)[]";
      case "long", "java.lang.Long", "java.time.LocalTime", "java.time.LocalDateTime",
          "java.time.Instant", "java.time.OffsetDateTime" -> "bigint";
      case "boolean", "java.lang.Boolean" -> "boolean";
      default -> "number"; // int, short, double, float, date(int4)
    };
  }

  /** Import line for the client-package decoders the generated reader uses (empty if none). */
  private static String tsImports(List<Field> fields) {
    Set<String> fns = new TreeSet<>();
    for (Field f : fields) {
      if (f.encrypted()) continue; // ciphertext stays Uint8Array
      switch (f.javaType()) {
        case "monolith.pg.Json" -> fns.add("decodeJsonb");
        case "java.math.BigDecimal" -> fns.add("decodeNumeric");
        case "int[]" -> fns.add("decodeInt4Array");
        case "long[]" -> fns.add("decodeInt8Array");
        case "java.lang.String[]" -> fns.add("decodeTextArray");
        default -> { }
      }
    }
    if (fns.isEmpty()) return "";
    return "import { " + String.join(", ", fns) + " } from '@standardapplied/monolith-client';\n\n";
  }

  // ======================= schema.lock ===================================

  private void recordLock(String pkg, String javaName, String pgName, List<Field> fields,
      int fixedSize, int bitmap, boolean projection) {
    StringBuilder b = new StringBuilder();
    String fq = pkg.isEmpty() ? javaName : pkg + "." + javaName;
    b.append(projection ? "projection " : "type ").append(pgName)
        .append(" (record ").append(fq)
        .append(") fixed_size=").append(fixedSize)
        .append(" null_bitmap_bytes=").append(bitmap).append('\n');
    for (Field f : fields) {
      String w = f.kind() == Kind.FIXED
          ? "width=" + f.width()
          : "width=var(off@" + f.headerOffset() + ",len@" + (f.headerOffset() + 4) + ")";
      b.append(String.format("  %d %-13s %-12s off=%-4d %-26s %-9s java=%s%n",
          f.ordinal(), f.name(), f.pgType(), f.headerOffset(), w,
          f.nullable() ? "NULL" : "NOT NULL", f.javaType()));
    }
    lockEntries.add(b.toString());
  }

  private void writeLock() {
    String dir = processingEnv.getOptions().get("monolith.lockDir");
    if (dir == null) return;
    try {
      StringBuilder b = new StringBuilder();
      b.append("# schema.lock, GENERATED. Commit this file.\n");
      b.append("# CI fails the build if it changes without an intentional update,\n");
      b.append("# which would mean a @PgType component was reordered, retyped, or its\n");
      b.append("# nullability changed.\n\n");
      lockEntries.stream().sorted().forEach(b::append);
      Path out = Path.of(dir, "schema.lock");
      Files.createDirectories(out.getParent());
      Files.writeString(out, b.toString(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      processingEnv.getMessager()
          .printMessage(Diagnostic.Kind.ERROR, "failed to write schema.lock: " + ex);
    }
  }

  // ======================= helpers =======================================

  private static boolean hasTemporal(List<Field> fields) {
    return fields.stream().anyMatch(f -> f.javaType().startsWith("java.time."));
  }

  /** Minimal import set for the generated reader/builder, based on types used. */
  private static String imports(List<Field> fields, boolean builder) {
    Set<String> imp = new TreeSet<>();
    imp.add("java.lang.foreign.MemorySegment");
    imp.add("java.lang.foreign.ValueLayout");
    imp.add("java.nio.ByteOrder");
    for (Field f : fields) {
      switch (f.javaType()) {
        case "java.util.UUID" -> imp.add("java.util.UUID");
        case "java.lang.String" -> imp.add("java.nio.charset.StandardCharsets");
        case "java.math.BigDecimal" -> {
          imp.add("java.math.BigDecimal");
          imp.add("monolith.pg.runtime.PgCodec");
        }
        case "monolith.pg.Json" -> {
          imp.add("monolith.pg.Json");
          imp.add("monolith.pg.runtime.PgCodec");
        }
        case "int[]", "long[]", "java.lang.String[]" -> imp.add("monolith.pg.runtime.PgCodec");
        case "java.time.LocalDate" -> imp.add("java.time.LocalDate");
        case "java.time.LocalTime" -> imp.add("java.time.LocalTime");
        case "java.time.LocalDateTime" -> {
          imp.add("java.time.LocalDateTime");
          imp.add("java.time.ZoneOffset");
        }
        case "java.time.Instant" -> {
          imp.add("java.time.Instant");
          imp.add("java.time.ZoneOffset");
        }
        case "java.time.OffsetDateTime" -> {
          imp.add("java.time.OffsetDateTime");
          imp.add("java.time.Instant");
          imp.add("java.time.ZoneOffset");
        }
        default -> { }
      }
      if (builder && f.javaType().equals("java.lang.String")) {
        imp.add("java.nio.charset.StandardCharsets");
      }
    }
    StringBuilder b = new StringBuilder();
    for (String s : imp) b.append("import ").append(s).append(";\n");
    return b.toString();
  }

  private interface Fn { String apply(Field f); }

  private static String strArr(List<Field> fields, Fn fn) {
    StringJoiner j = new StringJoiner(", ", "{", "}");
    for (Field f : fields) j.add("\"" + fn.apply(f) + "\"");
    return j.toString();
  }

  private static String intArr(List<Field> fields, java.util.function.ToIntFunction<Field> fn) {
    StringJoiner j = new StringJoiner(", ", "{", "}");
    for (Field f : fields) j.add(Integer.toString(fn.applyAsInt(f)));
    return j.toString();
  }

  private static String boolArr(List<Field> fields) {
    StringJoiner j = new StringJoiner(", ", "{", "}");
    for (Field f : fields) j.add(Boolean.toString(f.nullable()));
    return j.toString();
  }

  private void write(String pkg, String cls, String content) throws IOException {
    Filer filer = processingEnv.getFiler();
    String fq = pkg.isEmpty() ? cls : pkg + "." + cls;
    JavaFileObject jfo = filer.createSourceFile(fq);
    try (Writer w = jfo.openWriter()) {
      w.write(content);
    }
  }

  private static String packageOf(TypeElement record) {
    String fqn = record.getQualifiedName().toString();
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? "" : fqn.substring(0, dot);
  }

  private static String simpleType(String javaType) {
    return switch (javaType) {
      case "java.util.UUID" -> "UUID";
      case "java.lang.String" -> "String";
      case "java.lang.Integer" -> "Integer";
      case "java.lang.Long" -> "Long";
      case "java.lang.Short" -> "Short";
      case "java.lang.Boolean" -> "Boolean";
      case "java.lang.Double" -> "Double";
      case "java.lang.Float" -> "Float";
      case "java.time.LocalDate" -> "LocalDate";
      case "java.time.LocalTime" -> "LocalTime";
      case "java.time.LocalDateTime" -> "LocalDateTime";
      case "java.time.Instant" -> "Instant";
      case "java.time.OffsetDateTime" -> "OffsetDateTime";
      case "java.math.BigDecimal" -> "BigDecimal";
      case "monolith.pg.Json" -> "Json";
      case "java.lang.String[]" -> "String[]";
      default -> javaType; // int, long, short, boolean, double, float, byte[], int[], long[]
    };
  }

  private static String snake(String camel) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) b.append('_');
        b.append(Character.toLowerCase(c));
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }
}
