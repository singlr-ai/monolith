/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field-encryption provider for {@code @Encrypted} components. AES-256-GCM; the key lives here in
 * the JVM (set it from a KMS/HSM), never in Postgres. The generated reader/builder call
 * {@link #decrypt}/{@link #encrypt}, so encryption is transparent to application code.
 *
 * <p>Key sources, in order: {@link #setKey(byte[])}, or the base64 env var {@code MONOLITH_FIELD_KEY}.
 * Wire form: {@code [12-byte nonce][ciphertext+tag]}.
 */
public final class PgCrypto {

  private static final int NONCE_LEN = 12;
  private static final int TAG_BITS = 128;
  private static volatile SecretKeySpec key;

  static {
    loadKeyFromEnv(System.getenv("MONOLITH_FIELD_KEY"));
  }

  /** Installs the key from a base64 string, ignoring a null/blank value. Package-private for tests. */
  static void loadKeyFromEnv(String base64) {
    if (base64 != null && !base64.isBlank()) {
      setKey(Base64.getDecoder().decode(base64.trim()));
    }
  }

  /** Install the 32-byte AES-256 key (typically fetched from a KMS at startup). */
  public static void setKey(byte[] key32) {
    if (key32.length != 32) throw new IllegalArgumentException("AES-256 needs a 32-byte key");
    key = new SecretKeySpec(key32, "AES");
  }

  public static byte[] encrypt(String plaintext) {
    byte[] nonce = new byte[NONCE_LEN];
    new SecureRandom().nextBytes(nonce);
    byte[] ct = cipher(Cipher.ENCRYPT_MODE, nonce, plaintext.getBytes(StandardCharsets.UTF_8));
    return ByteBuffer.allocate(NONCE_LEN + ct.length).put(nonce).put(ct).array();
  }

  public static String decrypt(byte[] blob) {
    byte[] nonce = Arrays.copyOfRange(blob, 0, NONCE_LEN);
    byte[] ct = Arrays.copyOfRange(blob, NONCE_LEN, blob.length);
    return new String(cipher(Cipher.DECRYPT_MODE, nonce, ct), StandardCharsets.UTF_8);
  }

  /** The single place the JCE ceremony lives; its catch is exercised by a bad-tag decrypt. */
  private static byte[] cipher(int mode, byte[] nonce, byte[] input) {
    SecretKeySpec k = require();
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(mode, k, new GCMParameterSpec(TAG_BITS, nonce));
      return c.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  private static SecretKeySpec require() {
    SecretKeySpec k = key;
    if (k == null) {
      throw new IllegalStateException(
          "field-encryption key not configured, set MONOLITH_FIELD_KEY or call PgCrypto.setKey(...)");
    }
    return k;
  }

  private PgCrypto() {}
}
