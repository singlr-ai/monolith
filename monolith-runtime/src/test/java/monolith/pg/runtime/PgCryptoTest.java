/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PgCrypto")
class PgCryptoTest {

  private static final byte[] KEY = "monolith-demo-key-32-bytes-long!".getBytes();

  /** Reset the shared static key so each test starts from a known state. */
  @BeforeEach
  void clearKey() throws Exception {
    var field = PgCrypto.class.getDeclaredField("key");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  void roundTripsThroughCiphertext() {
    PgCrypto.setKey(KEY);
    String plaintext = "123-45-6789";
    byte[] wire = PgCrypto.encrypt(plaintext);

    assertEquals(plaintext, PgCrypto.decrypt(wire));
    assertTrue(wire.length > plaintext.length(), "ciphertext carries a nonce and tag");
  }

  @Test
  void encryptingTwiceYieldsDifferentCiphertext() {
    PgCrypto.setKey(KEY);
    assertFalse(java.util.Arrays.equals(PgCrypto.encrypt("x"), PgCrypto.encrypt("x")),
        "a fresh random nonce each time");
  }

  @Test
  void setKeyRejectsAWrongLength() {
    var ex = assertThrows(IllegalArgumentException.class, () -> PgCrypto.setKey(new byte[16]));
    assertTrue(ex.getMessage().contains("32-byte"));
  }

  @Test
  void encryptAndDecryptFailWhenNoKeyIsConfigured() {
    assertThrows(IllegalStateException.class, () -> PgCrypto.encrypt("x"));
    assertThrows(IllegalStateException.class, () -> PgCrypto.decrypt(new byte[20]));
  }

  @Test
  void loadKeyFromEnvIgnoresNullOrBlank() {
    PgCrypto.loadKeyFromEnv(null);
    PgCrypto.loadKeyFromEnv("   ");
    assertThrows(IllegalStateException.class, () -> PgCrypto.encrypt("x")); // still unset
  }

  @Test
  void loadKeyFromEnvInstallsABase64Key() {
    PgCrypto.loadKeyFromEnv(Base64.getEncoder().encodeToString(KEY));
    assertEquals("hello", PgCrypto.decrypt(PgCrypto.encrypt("hello")));
  }

  @Test
  void aCorruptedTagFailsToDecrypt() {
    PgCrypto.setKey(KEY);
    byte[] wire = PgCrypto.encrypt("secret");
    wire[wire.length - 1] ^= 0xFF; // corrupt the GCM tag

    var ex = assertThrows(RuntimeException.class, () -> PgCrypto.decrypt(wire));
    assertFalse(ex instanceof IllegalStateException, "a crypto failure, not a missing key");
  }

  @Test
  void plaintextNeverAppearsInTheCiphertext() {
    PgCrypto.setKey(KEY);
    byte[] ssn = "123-45-6789".getBytes();
    byte[] wire = PgCrypto.encrypt("123-45-6789");
    // the raw plaintext bytes must not be a contiguous slice of the wire form
    for (int i = 0; i + ssn.length <= wire.length; i++) {
      assertFalse(java.util.Arrays.equals(ssn, java.util.Arrays.copyOfRange(wire, i, i + ssn.length)));
    }
    assertArrayEquals("123-45-6789".getBytes(), PgCrypto.decrypt(wire).getBytes());
  }
}
