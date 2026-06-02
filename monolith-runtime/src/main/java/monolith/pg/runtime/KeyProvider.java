/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

/**
 * Wraps and unwraps the per-value data keys that {@link PgCrypto} uses for envelope encryption. The
 * key-encryption key (KEK) never leaves the provider, so it never reaches the data store. This is the
 * seam for key custody: the default {@link LocalKeyProvider} keeps KEKs in the JVM, and an adapter in
 * its own module can implement this against a real KMS (AWS KMS {@code Encrypt}/{@code Decrypt}, GCP
 * KMS, Vault), keeping the third-party dependency out of the core.
 *
 * <p>A {@code keyId} identifies which KEK wrapped a given data key, so several KEK versions can
 * coexist: rotate by introducing a new KEK for new writes while old values still decrypt under the
 * KEK that wrapped them.
 */
public interface KeyProvider {

  /** A data key wrapped by the KEK identified by {@code keyId}. */
  record WrappedKey(String keyId, byte[] wrapped) {}

  /** Wrap a freshly generated data key under the current KEK. */
  WrappedKey wrap(byte[] dataKey);

  /** Unwrap a data key that was wrapped under the KEK named {@code keyId}. */
  byte[] unwrap(String keyId, byte[] wrapped);
}
