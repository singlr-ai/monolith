/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package monolith.pg.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LocalKeyProvider")
class LocalKeyProviderTest {

  private static final byte[] KEK1 = "monolith-kek-one-32-bytes-long!!".getBytes();
  private static final byte[] KEK2 = "monolith-kek-two-32-bytes-long!!".getBytes();
  private static final byte[] DATA_KEY = "a-32-byte-data-key-for-the-test!".getBytes();

  @Test
  @DisplayName("wraps and unwraps a data key under the current KEK")
  void wrapsAndUnwraps() {
    var provider = new LocalKeyProvider();
    provider.addKey("k1", KEK1);

    var wrapped = provider.wrap(DATA_KEY);
    assertEquals("k1", wrapped.keyId());
    assertArrayEquals(DATA_KEY, provider.unwrap(wrapped.keyId(), wrapped.wrapped()));
  }

  @Test
  @DisplayName("a value wrapped under an older KEK still unwraps after rotation")
  void olderKeyStillUnwrapsAfterRotation() {
    var provider = new LocalKeyProvider();
    provider.addKey("k1", KEK1);
    var underK1 = provider.wrap(DATA_KEY);

    provider.addKey("k2", KEK2); // rotate
    var underK2 = provider.wrap(DATA_KEY);

    assertEquals("k2", underK2.keyId(), "new writes use the current KEK");
    assertArrayEquals(DATA_KEY, provider.unwrap("k1", underK1.wrapped()), "k1 still registered");
    assertArrayEquals(DATA_KEY, provider.unwrap("k2", underK2.wrapped()));
  }

  @Test
  @DisplayName("wrapping fails when no KEK is configured")
  void wrapFailsWithoutAKey() {
    assertThrows(IllegalStateException.class, () -> new LocalKeyProvider().wrap(DATA_KEY));
  }

  @Test
  @DisplayName("unwrapping fails for an unregistered key id")
  void unwrapFailsForUnknownKeyId() {
    var provider = new LocalKeyProvider();
    provider.addKey("k1", KEK1);
    var wrapped = provider.wrap(DATA_KEY);

    var ex = assertThrows(IllegalStateException.class,
        () -> provider.unwrap("missing", wrapped.wrapped()));
    assertEquals(true, ex.getMessage().contains("missing"));
  }

  @Test
  @DisplayName("addKey validates its inputs")
  void addKeyValidates() {
    var provider = new LocalKeyProvider();
    assertThrows(NullPointerException.class, () -> provider.addKey(null, KEK1));
    assertThrows(IllegalArgumentException.class, () -> provider.addKey("k", new byte[16]));
  }
}
