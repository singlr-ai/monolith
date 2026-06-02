/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

/**
 * The health of a logical replication slot, the operational signal that matters most for the reactive
 * change feed. A slot whose consumer has stalled or died keeps retaining write-ahead log, and unbounded
 * retention fills the disk and takes Postgres down. Poll {@link Wal#health} and watch {@code
 * retainedBytes} (alert past a threshold) and {@code walStatus}.
 *
 * @param exists        whether the slot is present at all
 * @param active        whether a consumer is currently connected to it
 * @param walStatus     Postgres' {@code wal_status}: {@code reserved} (normal), {@code extended} or
 *                      {@code unreserved} (retention growing past limits), {@code lost} (the slot was
 *                      invalidated and changes were missed), or {@code none} when the slot is absent
 * @param retainedBytes how much WAL the slot is holding back ({@code restart_lsn} to the current LSN)
 */
public record SlotHealth(boolean exists, boolean active, String walStatus, long retainedBytes) {

  /**
   * Whether the slot was invalidated (it outran the server's retention limit and missed changes). A
   * lost slot must be recreated and every live subscription re-queried, because the feed has a gap.
   */
  public boolean isLost() {
    return "lost".equals(walStatus);
  }
}
