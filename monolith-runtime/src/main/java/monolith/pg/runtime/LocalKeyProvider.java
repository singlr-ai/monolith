/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keeps key-encryption keys (KEKs) in the JVM and wraps data keys with AES-256-GCM. It holds several
 * KEK versions at once, so you rotate by adding a new KEK (which new writes use) while values wrapped
 * under an older KEK still decrypt:
 *
 * <pre>{@code
 * var provider = new LocalKeyProvider();
 * provider.addKey("2025", oldKek);   // still registered, so last year's rows decrypt
 * provider.addKey("2026", newKek);   // now current: new writes wrap under it
 * PgCrypto.setKeyProvider(provider);
 * }</pre>
 *
 * For production key custody, implement {@link KeyProvider} against a KMS instead and keep the KEK out
 * of the process entirely.
 */
public final class LocalKeyProvider implements KeyProvider {

  private final Map<String, SecretKeySpec> keks = new ConcurrentHashMap<>();
  private volatile String currentKeyId;

  /** Register a 32-byte KEK under {@code keyId} and make it the one new writes wrap under. */
  public void addKey(String keyId, byte[] kek32) {
    Objects.requireNonNull(keyId, "keyId");
    if (kek32.length != 32) {
      throw new IllegalArgumentException("an AES-256 key-encryption key needs 32 bytes");
    }
    keks.put(keyId, new SecretKeySpec(kek32, "AES"));
    currentKeyId = keyId;
  }

  @Override
  public WrappedKey wrap(byte[] dataKey) {
    String keyId = currentKeyId;
    if (keyId == null) {
      throw new IllegalStateException(
          "no key-encryption key configured; call LocalKeyProvider.addKey or PgCrypto.setKey");
    }
    byte[] nonce = AesGcm.randomBytes(AesGcm.NONCE_LENGTH);
    byte[] ciphertext = AesGcm.encrypt(keks.get(keyId), nonce, dataKey);
    byte[] wrapped = ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    return new WrappedKey(keyId, wrapped);
  }

  @Override
  public byte[] unwrap(String keyId, byte[] wrapped) {
    SecretKeySpec kek = keks.get(keyId);
    if (kek == null) {
      throw new IllegalStateException("no key-encryption key registered for id: " + keyId);
    }
    byte[] nonce = Arrays.copyOfRange(wrapped, 0, AesGcm.NONCE_LENGTH);
    byte[] ciphertext = Arrays.copyOfRange(wrapped, AesGcm.NONCE_LENGTH, wrapped.length);
    return AesGcm.decrypt(kek, nonce, ciphertext);
  }
}
