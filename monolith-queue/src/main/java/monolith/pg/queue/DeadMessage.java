/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.queue;

/**
 * A dead-lettered message, for inspection: its {@code id} (pass to {@link Queue#replay} to try it
 * again), ordering {@code key}, opaque {@code payload}, how many {@code attempts} it took before
 * giving up, and the {@code lastError} that ended it.
 */
public record DeadMessage(long id, String key, byte[] payload, int attempts, String lastError) {}
