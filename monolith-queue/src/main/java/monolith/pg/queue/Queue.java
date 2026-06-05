/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import monolith.pg.runtime.ConnectionSource;
import monolith.pg.runtime.Observability;
import monolith.pg.runtime.Pg;
import monolith.pg.runtime.PgParam;
import monolith.pg.runtime.Result;

/**
 * A durable, ordered, at-least-once message queue in the same Postgres as your data. Enqueue inside a
 * transaction (see {@code Tx}) so the message commits atomically with your writes, then a worker
 * delivers it. This class holds the producer side and the claim primitive; see
 * {@code docs/design/QUEUE.md} for the full contract.
 */
public final class Queue {

  private static final String SCHEMA = """
      CREATE TABLE IF NOT EXISTS monolith_queue (
        id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        -- topic shape mirrors Message.requireValidTopic, so even a direct writer (a migration, an admin
        -- script, a bulk loader) cannot insert a structurally invalid topic that would later reach LISTEN.
        topic text NOT NULL CHECK (topic ~ '^[A-Za-z0-9._-]{1,48}$'),
        msg_key text,
        payload bytea NOT NULL,
        idem_key text,
        metadata jsonb NOT NULL DEFAULT '{}',
        status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'dead')),
        attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
        max_attempts int NOT NULL CHECK (max_attempts >= 1),
        run_at timestamptz NOT NULL DEFAULT now(),
        lease_until timestamptz,
        last_error text,
        created_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now());
      CREATE INDEX IF NOT EXISTS monolith_queue_claim
        ON monolith_queue (topic, msg_key, id) WHERE status = 'pending';
      CREATE UNIQUE INDEX IF NOT EXISTS monolith_queue_idem
        ON monolith_queue (topic, idem_key) WHERE idem_key IS NOT NULL;
      CREATE INDEX IF NOT EXISTS monolith_queue_status_updated
        ON monolith_queue (status, updated_at);
      CREATE OR REPLACE FUNCTION monolith_queue_notify() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          PERFORM pg_notify('monolith_queue_' || NEW.topic, '');
          RETURN NULL;
        END $$;
      CREATE OR REPLACE TRIGGER monolith_queue_notify_trigger
        AFTER INSERT ON monolith_queue FOR EACH ROW EXECUTE FUNCTION monolith_queue_notify()""";

  private static final String ENQUEUE_SQL = """
      INSERT INTO monolith_queue (topic, msg_key, payload, idem_key, max_attempts, run_at)
      VALUES ($1, $2, $3, $4, $5, COALESCE($6, now()))
      ON CONFLICT (topic, idem_key) WHERE idem_key IS NOT NULL DO NOTHING
      RETURNING id""";

  private static final String EXISTING_ID_SQL =
      "SELECT id FROM monolith_queue WHERE topic = $1 AND idem_key = $2";

  private static final String CLAIM_SQL = """
      WITH due AS (
        SELECT q.id FROM monolith_queue q
        WHERE q.topic = $1 AND q.status = 'pending' AND q.run_at <= now()
          AND (q.lease_until IS NULL OR q.lease_until < now())
          AND (q.msg_key IS NULL OR NOT EXISTS (
                SELECT 1 FROM monolith_queue earlier
                WHERE earlier.topic = q.topic AND earlier.msg_key = q.msg_key
                  AND earlier.status = 'pending' AND earlier.id < q.id))
        ORDER BY q.id
        FOR UPDATE SKIP LOCKED
        LIMIT $2)
      UPDATE monolith_queue q
      SET lease_until = now() + ($3 * interval '1 second'), attempts = q.attempts + 1, updated_at = now()
      FROM due WHERE q.id = due.id
      RETURNING q.id, q.payload, q.msg_key, q.idem_key, q.attempts, q.max_attempts""";

  private static final String MARK_SUCCEEDED_SQL =
      "UPDATE monolith_queue SET status = 'succeeded', lease_until = NULL, updated_at = now() WHERE id = $1";

  private static final String MARK_PENDING_SQL = """
      UPDATE monolith_queue
      SET status = 'pending', lease_until = NULL, run_at = now() + ($2 * interval '1 millisecond'),
          last_error = $3, updated_at = now()
      WHERE id = $1""";

  private static final String MARK_DEAD_SQL =
      "UPDATE monolith_queue SET status = 'dead', lease_until = NULL, last_error = $2, updated_at = now() WHERE id = $1";

  private static final String EXTEND_LEASE_SQL = """
      UPDATE monolith_queue SET lease_until = now() + ($2 * interval '1 second'), updated_at = now()
      WHERE id = ANY($1) AND status = 'pending'""";

  private static final String DEAD_LETTERS_SQL = """
      SELECT id, msg_key, payload, attempts, last_error FROM monolith_queue
      WHERE topic = $1 AND status = 'dead' ORDER BY id LIMIT $2""";

  private static final String REPLAY_SQL = """
      UPDATE monolith_queue
      SET status = 'pending', attempts = 0, lease_until = NULL, run_at = now(),
          last_error = NULL, updated_at = now()
      WHERE id = $1 AND status = 'dead'""";

  private static final String PURGE_SQL = """
      DELETE FROM monolith_queue
      WHERE topic = $1 AND status = 'succeeded' AND updated_at < now() - ($2 * interval '1 second')
      RETURNING id""";

  /** Creates the queue table and its indexes if they do not exist. Idempotent; run once at startup. */
  public static Result<Void> install(MemorySegment conn) {
    try (Arena arena = Arena.ofConfined()) {
      return Pg.exec(arena, conn, SCHEMA);
    }
  }

  /**
   * Begins configuring a {@link Worker} that drains {@code topic} from {@code source}.
   *
   * <p>A worker holds one connection for its whole life to {@code LISTEN} for enqueue notifications. When
   * {@code source} is a single-database {@link monolith.pg.runtime.PgPool} (it exposes a
   * {@link ConnectionSource#dedicatedConninfo() dedicated conninfo}), that listener is a dedicated
   * <em>unpooled</em> connection, so it never ties up a pool slot — even a size-1 pool stays usable for
   * claiming and delivery. A composed source (a shard router or replica set) exposes no dedicated
   * conninfo, so the listener falls back to a <em>pooled</em> lease held for the worker's life: size such
   * a source to leave at least one connection beyond the listener, or the worker cannot also claim.
   */
  public static Worker.Builder worker(ConnectionSource source, String topic) {
    Message.requireValidTopic(topic); // the topic becomes a LISTEN channel identifier in the worker
    return new Worker.Builder(source, topic);
  }

  /**
   * Enqueues a message, returning its id. Call inside a transaction so it commits with your writes. If
   * the message carries an {@code idempotencyKey} that is already present, this is a no-op and returns
   * the id of the existing message.
   */
  public static Result<Long> enqueue(MemorySegment conn, Message message) {
    try (Arena arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, message.topic(), message.key(), message.payload(),
          message.idempotencyKey(), message.maxAttempts(), message.runAt());
      return Pg.execParamsBinary(arena, conn, ENQUEUE_SQL, p.values(), p.lengths(), p.formats())
          .map(res -> {
            boolean inserted = Pg.ntuples(res) == 1;
            long id = inserted ? readLong(res, 0, 0) : existingId(arena, conn, message);
            Pg.clear(res);
            if (inserted && Observability.enabled()) {
              Observability.emit(new QueueEvent.Enqueued(message.topic(), id));
            }
            return id;
          });
    }
  }

  /**
   * Claims up to {@code batchSize} due messages for {@code topic}, leasing each for {@code lease} so a
   * crashed worker's messages become reclaimable. Respects per-key ordering (only the head of each key
   * is claimable) and skips messages another worker holds. The attempt counter is advanced on claim.
   */
  public static Result<List<DeliveredMessage>> claim(
      MemorySegment conn, String topic, int batchSize, Duration lease) {
    Message.requireValidTopic(topic);
    try (Arena arena = Arena.ofConfined()) {
      // LIMIT infers int8 (bind a long); `$3 * interval` infers float8 (bind a double).
      var p = PgParam.bind(arena, topic, (long) batchSize, (double) lease.toSeconds());
      return Pg.execParamsBinary(arena, conn, CLAIM_SQL, p.values(), p.lengths(), p.formats())
          .map(res -> {
            int n = Pg.ntuples(res);
            List<DeliveredMessage> claimed = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
              claimed.add(new DeliveredMessage(
                  readLong(res, i, 0), topic, text(res, i, 2), Pg.getbytes(res, i, 1),
                  text(res, i, 3), readInt(res, i, 4), readInt(res, i, 5)));
            }
            Pg.clear(res);
            return claimed;
          });
    }
  }

  /** Marks a message delivered. Runs on {@code conn}, so it joins an ambient transaction if there is one. */
  static Result<Void> markSucceeded(MemorySegment conn, long id) {
    return update(conn, MARK_SUCCEEDED_SQL, id);
  }

  /** Returns a message to pending, due after {@code delay}, recording the error (a retry). */
  static Result<Void> markPending(MemorySegment conn, long id, Duration delay, String error) {
    return update(conn, MARK_PENDING_SQL, id, (double) delay.toMillis(), error);
  }

  /** Moves a message to the dead-letter state, recording the error. */
  static Result<Void> markDead(MemorySegment conn, long id, String error) {
    return update(conn, MARK_DEAD_SQL, id, error);
  }

  /** Pushes the lease out for the still-pending messages among {@code ids} (the worker heartbeat). */
  static Result<Void> extendLease(MemorySegment conn, long[] ids, Duration lease) {
    return update(conn, EXTEND_LEASE_SQL, ids, (double) lease.toSeconds());
  }

  /** Lists up to {@code limit} dead-lettered messages for {@code topic}, oldest first, to inspect. */
  public static Result<List<DeadMessage>> deadLetters(MemorySegment conn, String topic, int limit) {
    Message.requireValidTopic(topic);
    try (Arena arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, topic, (long) limit);
      return Pg.execParamsBinary(arena, conn, DEAD_LETTERS_SQL, p.values(), p.lengths(), p.formats())
          .map(res -> {
            int n = Pg.ntuples(res);
            List<DeadMessage> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
              out.add(new DeadMessage(
                  readLong(res, i, 0), text(res, i, 1), Pg.getbytes(res, i, 2),
                  readInt(res, i, 3), text(res, i, 4)));
            }
            Pg.clear(res);
            return out;
          });
    }
  }

  /** Returns a dead-lettered message to pending with a fresh attempt budget, once you have fixed it. */
  public static Result<Void> replay(MemorySegment conn, long id) {
    return update(conn, REPLAY_SQL, id);
  }

  /** Deletes succeeded messages for {@code topic} older than {@code olderThan}; returns how many. */
  public static Result<Integer> purgeSucceeded(MemorySegment conn, String topic, Duration olderThan) {
    Message.requireValidTopic(topic);
    try (Arena arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, topic, (double) olderThan.toSeconds());
      return Pg.execParamsBinary(arena, conn, PURGE_SQL, p.values(), p.lengths(), p.formats())
          .map(res -> {
            int purged = Pg.ntuples(res);
            Pg.clear(res);
            return purged;
          });
    }
  }

  private static Result<Void> update(MemorySegment conn, String sql, Object... params) {
    try (Arena arena = Arena.ofConfined()) {
      var p = PgParam.bind(arena, params);
      return Pg.execParamsBinary(arena, conn, sql, p.values(), p.lengths(), p.formats()).map(res -> {
        Pg.clear(res);
        return null;
      });
    }
  }

  private static long existingId(Arena arena, MemorySegment conn, Message message) {
    var p = PgParam.bind(arena, message.topic(), message.idempotencyKey());
    var res = Pg.execParamsBinary(arena, conn, EXISTING_ID_SQL, p.values(), p.lengths(), p.formats())
        .getOrThrow();
    long id = readLong(res, 0, 0);
    Pg.clear(res);
    return id;
  }

  private static long readLong(MemorySegment res, int row, int col) {
    return ByteBuffer.wrap(Pg.getbytes(res, row, col)).getLong();
  }

  private static int readInt(MemorySegment res, int row, int col) {
    return ByteBuffer.wrap(Pg.getbytes(res, row, col)).getInt();
  }

  private static String text(MemorySegment res, int row, int col) {
    return Pg.getisnull(res, row, col)
        ? null
        : new String(Pg.getbytes(res, row, col), StandardCharsets.UTF_8);
  }

  private Queue() {}
}
