/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * The libpq binding, Postgres' C client library called directly via the Panama FFM API
 * ({@code invokeExact} downcalls), with no JDBC in the path. Covers connect/exec, the binary
 * {@code PQexecParams} path (parameters and results in binary, straight to/from our wire layout),
 * and the streaming-replication entry points used by the change feed.
 *
 * <p><b>Locating libpq.</b> Resolved in order: the {@code monolith.libpq} system property, the
 * {@code MONOLITH_LIBPQ} environment variable, a set of common platform install paths, and finally
 * the OS loader path by name ({@code libpq.so}/{@code .dylib}). Set the property/env var when
 * Postgres is installed somewhere non-standard (e.g. a keg-only Homebrew formula).
 */
public final class Pg {

  private static final Linker LINKER = Linker.nativeLinker();

  /** Common locations to probe before falling back to the OS loader path. */
  private static final String[] LIBPQ_CANDIDATES = {
      "/opt/homebrew/opt/libpq/lib/libpq.dylib",                 // Homebrew (Apple Silicon), keg-only libpq
      "/usr/local/opt/libpq/lib/libpq.dylib",                    // Homebrew (Intel), keg-only libpq
      "/opt/homebrew/opt/postgresql@18/lib/postgresql/libpq.dylib",
      "/usr/lib/x86_64-linux-gnu/libpq.so.5",                    // Debian/Ubuntu
      "/usr/lib64/libpq.so.5",                                   // RHEL/Fedora
      "/usr/lib/libpq.so.5",
  };

  // Initialised after LIBPQ_CANDIDATES so resolveLibpq() can read the array during class init.
  private static final SymbolLookup LIB = resolveLibpq();

  private static SymbolLookup resolveLibpq() {
    String override = System.getProperty("monolith.libpq");
    if (override == null || override.isBlank()) override = System.getenv("MONOLITH_LIBPQ");
    if (override != null && !override.isBlank()) {
      return SymbolLookup.libraryLookup(java.nio.file.Path.of(override.trim()), Arena.global());
    }
    for (String path : LIBPQ_CANDIDATES) {
      if (java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
        return SymbolLookup.libraryLookup(java.nio.file.Path.of(path), Arena.global());
      }
    }
    // Last resort: let the OS loader find libpq.so/.dylib on its search path by name.
    try {
      return SymbolLookup.libraryLookup("pq", Arena.global());
    } catch (IllegalArgumentException e) {
      throw new RuntimeException(
          "could not locate libpq. Install PostgreSQL's client library, or set -Dmonolith.libpq=/path/to/libpq"
              + " (or the MONOLITH_LIBPQ env var)", e);
    }
  }

  private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
  private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

  private static MethodHandle h(String name, FunctionDescriptor desc) {
    return LINKER.downcallHandle(LIB.find(name).orElseThrow(
        () -> new RuntimeException("libpq symbol not found: " + name)), desc);
  }

  private static final MethodHandle PQconnectdb    = h("PQconnectdb",    FunctionDescriptor.of(PTR, PTR));
  private static final MethodHandle PQstatus       = h("PQstatus",       FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQerrorMessage = h("PQerrorMessage", FunctionDescriptor.of(PTR, PTR));
  private static final MethodHandle PQfinish       = h("PQfinish",       FunctionDescriptor.ofVoid(PTR));
  private static final MethodHandle PQexec         = h("PQexec",         FunctionDescriptor.of(PTR, PTR, PTR));
  private static final MethodHandle PQexecParams   = h("PQexecParams",
      FunctionDescriptor.of(PTR, PTR, PTR, INT, PTR, PTR, PTR, PTR, INT));
  private static final MethodHandle PQprepare      = h("PQprepare",
      FunctionDescriptor.of(PTR, PTR, PTR, PTR, INT, PTR));
  private static final MethodHandle PQexecPrepared = h("PQexecPrepared",
      FunctionDescriptor.of(PTR, PTR, PTR, INT, PTR, PTR, PTR, INT));
  private static final MethodHandle PQresultStatus       = h("PQresultStatus",       FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQresultErrorMessage = h("PQresultErrorMessage", FunctionDescriptor.of(PTR, PTR));
  private static final MethodHandle PQresultErrorField   = h("PQresultErrorField",   FunctionDescriptor.of(PTR, PTR, INT));

  /** {@code PG_DIAG_SQLSTATE} field code ('C'): the five-character SQLSTATE of a failed result. */
  private static final int PG_DIAG_SQLSTATE = 'C';
  private static final MethodHandle PQclear        = h("PQclear",        FunctionDescriptor.ofVoid(PTR));
  private static final MethodHandle PQntuples      = h("PQntuples",      FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQbinaryTuples = h("PQbinaryTuples", FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQgetvalue     = h("PQgetvalue",     FunctionDescriptor.of(PTR, PTR, INT, INT));
  private static final MethodHandle PQgetlength    = h("PQgetlength",    FunctionDescriptor.of(INT, PTR, INT, INT));
  private static final MethodHandle PQgetisnull    = h("PQgetisnull",    FunctionDescriptor.of(INT, PTR, INT, INT));

  // Async notifications (LISTEN/NOTIFY) + libc poll() for event-driven push.
  private static final MethodHandle PQconsumeInput = h("PQconsumeInput", FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQnotifies     = h("PQnotifies",     FunctionDescriptor.of(PTR, PTR));
  private static final MethodHandle PQsocket       = h("PQsocket",       FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQfreemem      = h("PQfreemem",      FunctionDescriptor.ofVoid(PTR));

  // Async query protocol: dispatch without blocking on the result, then
  // consume the result when the socket becomes readable.
  private static final MethodHandle PQsendQueryParams = h("PQsendQueryParams",
      FunctionDescriptor.of(INT, PTR, PTR, INT, PTR, PTR, PTR, PTR, INT));
  private static final MethodHandle PQisBusy   = h("PQisBusy",   FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQgetResult = h("PQgetResult", FunctionDescriptor.of(PTR, PTR));

  // Streaming logical replication: START_REPLICATION puts the connection in
  // COPY_BOTH mode; WAL data arrives as CopyData messages, and the consumer sends standby
  // status replies (the LSN feedback that paces the slot, flow control / backpressure).
  private static final MethodHandle PQflush       = h("PQflush",       FunctionDescriptor.of(INT, PTR));
  private static final MethodHandle PQgetCopyData = h("PQgetCopyData", FunctionDescriptor.of(INT, PTR, PTR, INT));
  private static final MethodHandle PQputCopyData = h("PQputCopyData", FunctionDescriptor.of(INT, PTR, PTR, INT));
  private static final MethodHandle POLL = LINKER.downcallHandle(
      LINKER.defaultLookup().find("poll").orElseThrow(() -> new RuntimeException("poll not found")),
      FunctionDescriptor.of(INT, PTR, INT, INT)); // int poll(struct pollfd*, nfds_t(uint), int timeout)

  private static final short POLLIN = 0x0001;
  private static final short POLLOUT = 0x0004;

  public static final int CONNECTION_OK = 0;
  public static final int PGRES_COMMAND_OK = 1;
  public static final int PGRES_TUPLES_OK = 2;
  public static final int PGRES_COPY_BOTH = 8;

  /** Opens a connection. A failed handshake (bad conninfo, server down) is a {@link Result.Failure}. */
  public static Result<MemorySegment> connect(Arena arena, String conninfo) {
    try {
      MemorySegment conn = (MemorySegment)
          PQconnectdb.invokeExact((MemorySegment) arena.allocateFrom(conninfo, StandardCharsets.UTF_8));
      if ((int) PQstatus.invokeExact(conn) != CONNECTION_OK) {
        String err = cstr((MemorySegment) PQerrorMessage.invokeExact(conn));
        return Result.failure("connect failed: " + err);
      }
      return Result.success(conn);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static void finish(MemorySegment conn) {
    try { PQfinish.invokeExact(conn); } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Runs a (possibly multi-statement) command. A SQL error is a {@link Result.Failure}. */
  public static Result<Void> exec(Arena arena, MemorySegment conn, String sql) {
    try {
      MemorySegment res = (MemorySegment)
          PQexec.invokeExact(conn, (MemorySegment) arena.allocateFrom(sql, StandardCharsets.UTF_8));
      int st = (int) PQresultStatus.invokeExact(res);
      if (st != PGRES_COMMAND_OK && st != PGRES_TUPLES_OK) {
        String err = cstr((MemorySegment) PQresultErrorMessage.invokeExact(res));
        String state = cstr((MemorySegment) PQresultErrorField.invokeExact(res, PG_DIAG_SQLSTATE));
        PQclear.invokeExact(res);
        String msg = "exec failed (" + st + "): " + err + "\n  sql: " + sql;
        return Result.failure(msg, new PgSqlException(state, msg));
      }
      PQclear.invokeExact(res);
      return Result.success(null);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Execute with binary parameters and request a binary result.
   * {@code values[i] == null} sends SQL NULL for that parameter. Parameter
   * value segments must be native (off-heap) so libpq can read them by pointer.
   * A {@link Result.Success} carries the result handle (caller must {@link #clear} it); a SQL error
   * (constraint violation, syntax) is a {@link Result.Failure}.
   */
  public static Result<MemorySegment> execParamsBinary(
      Arena arena, MemorySegment conn, String sql,
      MemorySegment[] values, int[] lengths, int[] formats) {
    try {
      int n = values.length;
      MemorySegment cmd = arena.allocateFrom(sql, StandardCharsets.UTF_8);
      MemorySegment[] p = paramArrays(arena, values, lengths, formats);
      MemorySegment res = (MemorySegment) PQexecParams.invokeExact(
          conn, cmd, n, MemorySegment.NULL, p[0], p[1], p[2], 1 /* binary result */);
      return result(res, "execParams", sql);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Parses and plans {@code sql} once as a named prepared statement on {@code conn}, so it can be run
   * many times with {@link #execPrepared} without re-parsing. The statement lives on the connection
   * until it is deallocated or the connection's session state is reset. A SQL error is a
   * {@link Result.Failure}.
   */
  public static Result<Void> prepare(Arena arena, MemorySegment conn, String name, String sql) {
    try {
      MemorySegment res = (MemorySegment) PQprepare.invokeExact(conn,
          (MemorySegment) arena.allocateFrom(name, StandardCharsets.UTF_8),
          (MemorySegment) arena.allocateFrom(sql, StandardCharsets.UTF_8),
          0, MemorySegment.NULL); // 0 param types: let Postgres infer from the query
      int st = (int) PQresultStatus.invokeExact(res);
      if (st != PGRES_COMMAND_OK) {
        String err = cstr((MemorySegment) PQresultErrorMessage.invokeExact(res));
        String state = cstr((MemorySegment) PQresultErrorField.invokeExact(res, PG_DIAG_SQLSTATE));
        PQclear.invokeExact(res);
        String msg = "prepare failed (" + st + "): " + err + "\n  sql: " + sql;
        return Result.failure(msg, new PgSqlException(state, msg));
      }
      PQclear.invokeExact(res);
      return Result.success(null);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Executes a statement previously {@link #prepare}d under {@code name}, with binary params and result. */
  public static Result<MemorySegment> execPrepared(
      Arena arena, MemorySegment conn, String name,
      MemorySegment[] values, int[] lengths, int[] formats) {
    try {
      int n = values.length;
      MemorySegment stmt = arena.allocateFrom(name, StandardCharsets.UTF_8);
      MemorySegment[] p = paramArrays(arena, values, lengths, formats);
      MemorySegment res = (MemorySegment) PQexecPrepared.invokeExact(
          conn, stmt, n, p[0], p[1], p[2], 1 /* binary result */);
      return result(res, "execPrepared " + name, name);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Builds the {values, lengths, formats} native arrays for a binary parameter call (NULLs when empty). */
  private static MemorySegment[] paramArrays(
      Arena arena, MemorySegment[] values, int[] lengths, int[] formats) {
    int n = values.length;
    if (n == 0) {
      return new MemorySegment[] {MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL};
    }
    MemorySegment valuesArr = arena.allocate(PTR, n);
    MemorySegment lengthsArr = arena.allocate(INT, n);
    MemorySegment formatsArr = arena.allocate(INT, n);
    for (int i = 0; i < n; i++) {
      valuesArr.setAtIndex(PTR, i, values[i] == null ? MemorySegment.NULL : values[i]);
      lengthsArr.setAtIndex(INT, i, lengths[i]);
      formatsArr.setAtIndex(INT, i, formats[i]);
    }
    return new MemorySegment[] {valuesArr, lengthsArr, formatsArr};
  }

  /** Wraps a binary result handle: success carries it (caller clears), a SQL error carries its SQLSTATE. */
  private static Result<MemorySegment> result(MemorySegment res, String what, String detail)
      throws Throwable {
    int st = (int) PQresultStatus.invokeExact(res);
    if (st != PGRES_COMMAND_OK && st != PGRES_TUPLES_OK) {
      String err = cstr((MemorySegment) PQresultErrorMessage.invokeExact(res));
      String state = cstr((MemorySegment) PQresultErrorField.invokeExact(res, PG_DIAG_SQLSTATE));
      PQclear.invokeExact(res);
      String msg = what + " failed (" + st + "): " + err + "\n  sql: " + detail;
      return Result.failure(msg, new PgSqlException(state, msg));
    }
    return Result.success(res);
  }

  public static int ntuples(MemorySegment res) {
    try { return (int) PQntuples.invokeExact(res); } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Connection health (CONNECTION_OK == healthy). Used by the pool to detect a dead backend. */
  public static int status(MemorySegment conn) {
    try { return (int) PQstatus.invokeExact(conn); } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Run a text-result query and return column 0 of every row as a String. For admin /
   * replication queries (e.g. draining a logical slot via {@code pg_logical_slot_get_changes})
   * where the result is text, not our binary layout.
   */
  public static Result<java.util.List<String>> textColumn(Arena arena, MemorySegment conn, String sql) {
    try {
      MemorySegment res = (MemorySegment)
          PQexec.invokeExact(conn, (MemorySegment) arena.allocateFrom(sql, StandardCharsets.UTF_8));
      int st = (int) PQresultStatus.invokeExact(res);
      if (st != PGRES_TUPLES_OK && st != PGRES_COMMAND_OK) {
        String err = cstr((MemorySegment) PQresultErrorMessage.invokeExact(res));
        PQclear.invokeExact(res);
        return Result.failure("textColumn failed: " + err + "\n  sql: " + sql);
      }
      int n = (int) PQntuples.invokeExact(res);
      java.util.List<String> out = new java.util.ArrayList<>(n);
      for (int r = 0; r < n; r++) {
        out.add(cstr((MemorySegment) PQgetvalue.invokeExact(res, r, 0)));
      }
      PQclear.invokeExact(res);
      return Result.success(out);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static boolean binaryTuples(MemorySegment res) {
    try { return ((int) PQbinaryTuples.invokeExact(res)) != 0; }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static boolean getisnull(MemorySegment res, int row, int col) {
    try { return ((int) PQgetisnull.invokeExact(res, row, col)) != 0; }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static int getlength(MemorySegment res, int row, int col) {
    try { return (int) PQgetlength.invokeExact(res, row, col); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Binary-format cell as a sized byte[]. Empty array if length 0. */
  public static byte[] getbytes(MemorySegment res, int row, int col) {
    try {
      int len = (int) PQgetlength.invokeExact(res, row, col);
      MemorySegment ptr = (MemorySegment) PQgetvalue.invokeExact(res, row, col);
      if (len == 0 || ptr.equals(MemorySegment.NULL)) return new byte[0];
      return ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static void clear(MemorySegment res) {
    try { PQclear.invokeExact(res); } catch (Throwable t) { throw new RuntimeException(t); }
  }

  // ===================== LISTEN / NOTIFY (event-driven push) =============

  /** Start listening on a channel for the rest of the connection. */
  public static void listen(Arena arena, MemorySegment conn, String channel) {
    exec(arena, conn, "LISTEN " + channel).getOrThrow();
  }

  /**
   * Block (up to {@code timeoutMs}) until the connection's socket is readable, i.e.
   * a NOTIFY (or other input) has arrived. Returns true if readable, false on
   * timeout. This is the real event-driven wait, not a poll-the-table loop.
   */
  public static boolean waitReadable(Arena arena, MemorySegment conn, int timeoutMs) {
    try {
      int fd = (int) PQsocket.invokeExact(conn);
      if (fd < 0) return false;
      MemorySegment pollfd = arena.allocate(8); // struct pollfd { int fd; short events; short revents; }
      pollfd.set(ValueLayout.JAVA_INT, 0, fd);
      pollfd.set(ValueLayout.JAVA_SHORT, 4, POLLIN);
      pollfd.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
      int rc = (int) POLL.invokeExact(pollfd, 1, timeoutMs);
      return rc > 0;
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  // ===================== Async query protocol =================

  /**
   * Dispatch a binary-param query requesting a binary result, <b>without</b> waiting
   * for the result. Returns true if the command was queued+flushed. The caller then
   * waits for the connection's socket to become readable (typically on a reactor
   * thread, so the requesting virtual thread can unmount), then drains via
   * {@link #consumeInput}/{@link #isBusy}/{@link #getResult}. The connection must not
   * be touched by another thread until {@code getResult} returns NULL.
   */
  public static boolean sendQueryParamsBinary(
      Arena arena, MemorySegment conn, String sql,
      MemorySegment[] values, int[] lengths, int[] formats) {
    try {
      int n = values.length;
      MemorySegment cmd = arena.allocateFrom(sql, StandardCharsets.UTF_8);
      MemorySegment valuesArr = MemorySegment.NULL;
      MemorySegment lengthsArr = MemorySegment.NULL;
      MemorySegment formatsArr = MemorySegment.NULL;
      if (n > 0) {
        valuesArr = arena.allocate(PTR, n);
        lengthsArr = arena.allocate(INT, n);
        formatsArr = arena.allocate(INT, n);
        for (int i = 0; i < n; i++) {
          valuesArr.setAtIndex(PTR, i, values[i] == null ? MemorySegment.NULL : values[i]);
          lengthsArr.setAtIndex(INT, i, lengths[i]);
          formatsArr.setAtIndex(INT, i, formats[i]);
        }
      }
      int rc = (int) PQsendQueryParams.invokeExact(
          conn, cmd, n, MemorySegment.NULL, valuesArr, lengthsArr, formatsArr, 1 /* binary result */);
      return rc == 1;
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Read whatever input is available on the socket without blocking; false on failure. */
  public static boolean consumeInput(MemorySegment conn) {
    try { return ((int) PQconsumeInput.invokeExact(conn)) == 1; }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** True if a command is still in progress (a {@link #getResult} call would block). */
  public static boolean isBusy(MemorySegment conn) {
    try { return ((int) PQisBusy.invokeExact(conn)) == 1; }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Next result for the in-flight command, or NULL when the command is complete. */
  public static MemorySegment getResult(MemorySegment conn) {
    try { return (MemorySegment) PQgetResult.invokeExact(conn); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static int resultStatus(MemorySegment res) {
    try { return (int) PQresultStatus.invokeExact(res); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static String resultError(MemorySegment res) {
    try { return cstr((MemorySegment) PQresultErrorMessage.invokeExact(res)); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  public static int socketFd(MemorySegment conn) {
    try { return (int) PQsocket.invokeExact(conn); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  // ===================== Streaming logical replication =========

  /** Issue {@code START_REPLICATION}; the connection enters COPY_BOTH (streaming) mode. */
  public static void startReplication(Arena arena, MemorySegment conn, String command) {
    try {
      MemorySegment res = (MemorySegment)
          PQexec.invokeExact(conn, (MemorySegment) arena.allocateFrom(command, StandardCharsets.UTF_8));
      int st = (int) PQresultStatus.invokeExact(res);
      if (st != PGRES_COPY_BOTH) {
        String err = cstr((MemorySegment) PQresultErrorMessage.invokeExact(res));
        PQclear.invokeExact(res);
        throw new RuntimeException("START_REPLICATION failed (" + st + "): " + err + "\n  " + command);
      }
      PQclear.invokeExact(res);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Next CopyData message (async): its bytes, or {@code null} if none is buffered yet
   * (call {@link #consumeInput} after the socket is readable). Throws at stream end/error.
   */
  public static byte[] getCopyData(Arena arena, MemorySegment conn) {
    try {
      MemorySegment bufPtr = arena.allocate(PTR);
      int rc = (int) PQgetCopyData.invokeExact(conn, bufPtr, 1 /* async */);
      if (rc == 0) return null;
      if (rc < 0) throw new RuntimeException("copy stream ended/error: " + rc);
      MemorySegment buf = bufPtr.get(PTR, 0);
      byte[] out = buf.reinterpret(rc).toArray(ValueLayout.JAVA_BYTE);
      PQfreemem.invokeExact(buf);
      return out;
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Send a CopyData message upstream (e.g. a standby status reply) and flush it fully. The flush is not
   * fire-and-forget: a standby reply carries the confirmed LSN that advances the replication slot, so a
   * dropped flush would stall slot advancement and let WAL accumulate. When the socket's send buffer is
   * full (PQflush returns 1), wait for write-readiness and flush again until it is sent (0) or the
   * connection errors.
   */
  public static void sendCopyData(Arena arena, MemorySegment conn, byte[] msg) {
    try {
      MemorySegment data = arena.allocate(msg.length);
      MemorySegment.copy(msg, 0, data, ValueLayout.JAVA_BYTE, 0, msg.length);
      if ((int) PQputCopyData.invokeExact(conn, data, msg.length) < 0) {
        throw new RuntimeException("PQputCopyData failed");
      }
      int rc;
      while ((rc = (int) PQflush.invokeExact(conn)) == 1) { // 1 = send buffer full, data still queued
        int fd = (int) PQsocket.invokeExact(conn);
        if (fd < 0) throw new RuntimeException("flush failed: connection has no socket");
        MemorySegment pollfd = arena.allocate(8); // struct pollfd { int fd; short events; short revents; }
        pollfd.set(ValueLayout.JAVA_INT, 0, fd);
        pollfd.set(ValueLayout.JAVA_SHORT, 4, POLLOUT);
        pollfd.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
        int ignore = (int) POLL.invokeExact(pollfd, 1, 1000); // wait for writability (or an error in revents)
      }
      if (rc < 0) throw new RuntimeException("PQflush failed");
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Poll many connection sockets at once for readability (the reactor's wait). Returns
   * a parallel {@code boolean[]}: true where that socket is readable or errored. One
   * blocking native call covers all fds, so a single thread multiplexes every in-flight
   * query, and the application virtual threads that submitted them stay unmounted.
   */
  public static boolean[] pollReadable(Arena arena, int[] fds, int timeoutMs) {
    try {
      int n = fds.length;
      boolean[] ready = new boolean[n];
      if (n == 0) return ready;
      MemorySegment arr = arena.allocate(8L * n); // struct pollfd { int fd; short events; short revents; }
      for (int i = 0; i < n; i++) {
        arr.set(ValueLayout.JAVA_INT, 8L * i, fds[i]);
        arr.set(ValueLayout.JAVA_SHORT, 8L * i + 4, POLLIN);
        arr.set(ValueLayout.JAVA_SHORT, 8L * i + 6, (short) 0);
      }
      int rc = (int) POLL.invokeExact(arr, n, timeoutMs);
      if (rc <= 0) return ready;
      for (int i = 0; i < n; i++) {
        if (arr.get(ValueLayout.JAVA_SHORT, 8L * i + 6) != 0) ready[i] = true;
      }
      return ready;
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Consume pending socket input and drain queued notifications; returns how many. */
  public static int drainNotifications(MemorySegment conn) {
    try {
      if ((int) PQconsumeInput.invokeExact(conn) == 0) {
        throw new RuntimeException("PQconsumeInput failed");
      }
      int count = 0;
      MemorySegment n;
      while (!(n = (MemorySegment) PQnotifies.invokeExact(conn)).equals(MemorySegment.NULL)) {
        PQfreemem.invokeExact(n);
        count++;
      }
      return count;
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private static String cstr(MemorySegment ptr) {
    if (ptr == null || ptr.equals(MemorySegment.NULL)) return null;
    return ptr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
  }

  private Pg() {}
}
