/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field encryption for {@code @Encrypted} components, using <b>envelope encryption</b>: each value is
 * encrypted with a fresh random data key (DEK) under AES-256-GCM, and that data key is wrapped by a
 * key-encryption key (KEK) held by a {@link KeyProvider}. The KEK never touches Postgres, and because
 * every value has its own data key, disclosing the database plus one data key exposes one value, not
 * the whole column. The generated reader/builder call {@link #encrypt}/{@link #decrypt}, so this stays
 * transparent to application code.
 *
 * <p>Key custody is the {@link KeyProvider}'s job. The default is a {@link LocalKeyProvider} (KEKs in
 * the JVM); for production, {@link #setKeyProvider} a KMS-backed provider. Configure a simple local
 * key with {@link #setKey} or the base64 env var {@code MONOLITH_FIELD_KEY}.
 *
 * <p>Wire form: {@code [version][keyId][wrapped data key][nonce][ciphertext+tag]}.
 */
public final class PgCrypto {

  private static final byte VERSION = 2;
  private static final int DATA_KEY_BYTES = 32; // AES-256 data key
  private static volatile KeyProvider provider = new LocalKeyProvider();

  static {
    loadKeyFromEnv(System.getenv("MONOLITH_FIELD_KEY"));
  }

  /** Installs a key from a base64 string, ignoring a null/blank value. Package-private for tests. */
  static void loadKeyFromEnv(String base64) {
    if (base64 != null && !base64.isBlank()) {
      setKey(Base64.getDecoder().decode(base64.trim()));
    }
  }

  /**
   * Configure a single local 32-byte key-encryption key (key id {@code "0"}). For a KMS or for key
   * rotation, use {@link #setKeyProvider} with a {@link LocalKeyProvider} or a KMS adapter instead.
   */
  public static void setKey(byte[] kek32) {
    var local = new LocalKeyProvider();
    local.addKey("0", kek32);
    provider = local;
  }

  /** Install the provider that wraps and unwraps data keys (a KMS adapter, or a rotating local one). */
  public static void setKeyProvider(KeyProvider keyProvider) {
    provider = Objects.requireNonNull(keyProvider, "keyProvider");
  }

  public static byte[] encrypt(String plaintext) {
    byte[] dataKey = AesGcm.randomBytes(DATA_KEY_BYTES);
    try {
      byte[] nonce = AesGcm.randomBytes(AesGcm.NONCE_LENGTH);
      byte[] ciphertext = AesGcm.encrypt(
          new SecretKeySpec(dataKey, "AES"), nonce, plaintext.getBytes(StandardCharsets.UTF_8));
      KeyProvider.WrappedKey wrapped = provider.wrap(dataKey);
      byte[] keyId = wrapped.keyId().getBytes(StandardCharsets.UTF_8);
      return ByteBuffer.allocate(
              1 + 1 + keyId.length + 2 + wrapped.wrapped().length + nonce.length + ciphertext.length)
          .put(VERSION)
          .put((byte) keyId.length).put(keyId)
          .putShort((short) wrapped.wrapped().length).put(wrapped.wrapped())
          .put(nonce).put(ciphertext)
          .array();
    } finally {
      Arrays.fill(dataKey, (byte) 0);
    }
  }

  public static String decrypt(byte[] blob) {
    ByteBuffer buffer = ByteBuffer.wrap(blob);
    byte version = buffer.get();
    if (version != VERSION) {
      throw new IllegalStateException("unsupported field-encryption version: " + version);
    }
    byte[] keyId = new byte[buffer.get() & 0xFF];
    buffer.get(keyId);
    byte[] wrapped = new byte[buffer.getShort() & 0xFFFF];
    buffer.get(wrapped);
    byte[] nonce = new byte[AesGcm.NONCE_LENGTH];
    buffer.get(nonce);
    byte[] ciphertext = new byte[buffer.remaining()];
    buffer.get(ciphertext);

    byte[] dataKey = provider.unwrap(new String(keyId, StandardCharsets.UTF_8), wrapped);
    try {
      return new String(
          AesGcm.decrypt(new SecretKeySpec(dataKey, "AES"), nonce, ciphertext), StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(dataKey, (byte) 0);
    }
  }

  private PgCrypto() {}
}
