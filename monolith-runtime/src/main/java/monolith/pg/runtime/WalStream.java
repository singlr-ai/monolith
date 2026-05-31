/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A streaming logical-replication consumer over FFM.
 * Opens a {@code replication=database} connection, issues {@code START_REPLICATION}, and reads
 * WAL as CopyData in real time, replacing the poll-based {@link Wal#drain}. It auto-replies to
 * keepalives and sends standby status updates carrying its confirmed LSN (the feedback that
 * paces the slot).
 *
 * <p>Backpressure is inherent: {@link #poll} drains to a sink that may block; when it does, the
 * drain pauses, libpq stops being read, and TCP flow control pauses the server, bounded memory,
 * no loss (the slot retains the backlog). Needs {@code wal_level = logical}.
 */
public final class WalStream implements AutoCloseable {

  private static final Pattern TABLE = Pattern.compile("table public\\.(\\w+):");

  private final MemorySegment conn;
  private volatile long receivedLsn;
  private volatile long confirmedLsn;

  public WalStream(String conninfo, String slot) {
    MemorySegment c;
    try (Arena a = Arena.ofConfined()) {
      c = Pg.connect(a, conninfo + " replication=database").getOrThrow();
      Pg.startReplication(a, c, "START_REPLICATION SLOT " + slot + " LOGICAL 0/0");
    }
    this.conn = c;
  }

  /** Block up to {@code timeoutMs} for data, then drain buffered changes to {@code sink} (may block → backpressure). */
  public void poll(int timeoutMs, Consumer<WalChange> sink) {
    try (Arena a = Arena.ofConfined()) {
      if (!Pg.waitReadable(a, conn, timeoutMs)) return;
      if (!Pg.consumeInput(conn)) throw new RuntimeException("PQconsumeInput failed");
      byte[] msg;
      while ((msg = Pg.getCopyData(a, conn)) != null) {
        if (msg.length == 0) continue;
        switch (msg[0]) {
          case 'w' -> { // XLogData: 'w' walStart(8) walEnd(8) sendTime(8) <wal bytes>
            receivedLsn = Math.max(receivedLsn, beLong(msg, 9));
            String text = new String(msg, 25, msg.length - 25, StandardCharsets.UTF_8);
            Matcher m = TABLE.matcher(text);
            if (m.find()) sink.accept(new WalChange(m.group(1), text));
          }
          case 'k' -> { // keepalive: 'k' walEnd(8) sendTime(8) replyRequested(1)
            receivedLsn = Math.max(receivedLsn, beLong(msg, 1));
            if (msg.length > 17 && msg[17] != 0) sendStandby(a);
          }
          default -> { /* ignore */ }
        }
      }
    }
  }

  /** Confirm processing up to the received LSN, advances the slot (releases WAL). */
  public void confirm() {
    confirmedLsn = receivedLsn;
    try (Arena a = Arena.ofConfined()) {
      sendStandby(a);
    }
  }

  private void sendStandby(Arena a) {
    ByteBuffer b = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN);
    b.put((byte) 'r').putLong(receivedLsn).putLong(confirmedLsn).putLong(confirmedLsn).putLong(0).put((byte) 0);
    Pg.sendCopyData(a, conn, b.array());
  }

  private static long beLong(byte[] b, int off) {
    long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xffL);
    return v;
  }

  @Override
  public void close() {
    Pg.finish(conn);
  }
}
