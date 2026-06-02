/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The one place the AES-256-GCM ceremony lives, shared by data encryption and key wrapping. */
final class AesGcm {

  static final int NONCE_LENGTH = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RNG = new SecureRandom();

  static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RNG.nextBytes(bytes);
    return bytes;
  }

  static byte[] encrypt(SecretKeySpec key, byte[] nonce, byte[] plaintext) {
    return doFinal(Cipher.ENCRYPT_MODE, key, nonce, plaintext);
  }

  static byte[] decrypt(SecretKeySpec key, byte[] nonce, byte[] ciphertext) {
    return doFinal(Cipher.DECRYPT_MODE, key, nonce, ciphertext);
  }

  private static byte[] doFinal(int mode, SecretKeySpec key, byte[] nonce, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  private AesGcm() {}
}
