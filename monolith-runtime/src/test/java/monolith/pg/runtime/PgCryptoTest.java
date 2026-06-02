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
  private static final byte[] KEY2 = "another-demo-key-32-bytes-long!!".getBytes();

  /** Reset the shared provider so each test starts unconfigured. */
  @BeforeEach
  void clearProvider() throws Exception {
    var field = PgCrypto.class.getDeclaredField("provider");
    field.setAccessible(true);
    field.set(null, new LocalKeyProvider());
  }

  @Test
  void roundTripsThroughCiphertext() {
    PgCrypto.setKey(KEY);
    String plaintext = "123-45-6789";
    byte[] wire = PgCrypto.encrypt(plaintext);

    assertEquals(plaintext, PgCrypto.decrypt(wire));
    assertTrue(wire.length > plaintext.length(), "the wire form carries the wrapped key, nonce, and tag");
  }

  @Test
  void encryptingTwiceYieldsDifferentCiphertext() {
    PgCrypto.setKey(KEY);
    assertFalse(java.util.Arrays.equals(PgCrypto.encrypt("x"), PgCrypto.encrypt("x")),
        "a fresh random data key and nonce each time");
  }

  @Test
  void setKeyRejectsAWrongLength() {
    var ex = assertThrows(IllegalArgumentException.class, () -> PgCrypto.setKey(new byte[16]));
    assertTrue(ex.getMessage().contains("32"));
  }

  @Test
  void encryptFailsWhenNoKeyIsConfigured() {
    assertThrows(IllegalStateException.class, () -> PgCrypto.encrypt("x"));
  }

  @Test
  void decryptRejectsAnUnknownWireVersion() {
    var ex = assertThrows(IllegalStateException.class, () -> PgCrypto.decrypt(new byte[20]));
    assertTrue(ex.getMessage().contains("version"));
  }

  @Test
  void setKeyProviderRejectsNull() {
    assertThrows(NullPointerException.class, () -> PgCrypto.setKeyProvider(null));
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
  void rotatingTheKeyKeepsOlderValuesReadable() {
    var provider = new LocalKeyProvider();
    provider.addKey("v1", KEY);
    PgCrypto.setKeyProvider(provider);
    byte[] underV1 = PgCrypto.encrypt("old-secret");

    provider.addKey("v2", KEY2); // rotate: new writes use v2, v1 is still registered
    byte[] underV2 = PgCrypto.encrypt("new-secret");

    assertEquals("old-secret", PgCrypto.decrypt(underV1), "wrapped under v1, still decrypts");
    assertEquals("new-secret", PgCrypto.decrypt(underV2), "wrapped under v2");
  }

  @Test
  void aCorruptedTagFailsToDecrypt() {
    PgCrypto.setKey(KEY);
    byte[] wire = PgCrypto.encrypt("secret");
    wire[wire.length - 1] ^= 0xFF; // corrupt the data GCM tag

    var ex = assertThrows(RuntimeException.class, () -> PgCrypto.decrypt(wire));
    assertFalse(ex instanceof IllegalStateException, "a crypto failure, not a configuration error");
  }

  @Test
  void plaintextNeverAppearsInTheCiphertext() {
    PgCrypto.setKey(KEY);
    byte[] ssn = "123-45-6789".getBytes();
    byte[] wire = PgCrypto.encrypt("123-45-6789");
    for (int i = 0; i + ssn.length <= wire.length; i++) {
      assertFalse(java.util.Arrays.equals(ssn, java.util.Arrays.copyOfRange(wire, i, i + ssn.length)));
    }
    assertArrayEquals("123-45-6789".getBytes(), PgCrypto.decrypt(wire).getBytes());
  }
}
