/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} component of a {@code @PgType}/{@code @PgQuery} record as encrypted at
 * rest. The column is stored as {@code bytea}; the generated write path encrypts the value before
 * it leaves the JVM and the read path decrypts it, so the developer writes/reads plaintext and
 * Postgres only ever holds ciphertext. The key lives in {@link monolith.pg.runtime.PgCrypto} (a
 * KMS in production), never in the database, stronger than TDE, which decrypts in the server.
 *
 * <p>Note: an encrypted column cannot be queried/indexed by value (it's opaque to SQL), and the
 * generated client (TS) reader returns the raw ciphertext bytes, decryption is server-side.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.RECORD_COMPONENT)
public @interface Encrypted {}
