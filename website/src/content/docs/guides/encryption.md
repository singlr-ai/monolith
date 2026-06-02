---
title: "Field encryption"
description: "Mark a record component @Encrypted and the generated builder encrypts it on write, the generated reader decrypts it on read, and Postgres only ever stores"
---

Mark a record component `@Encrypted` and the generated builder encrypts it on write, the generated
reader decrypts it on read, and Postgres only ever stores ciphertext:

```java
@PgType
public record Patient(UUID id, String name, @Encrypted String ssn) {}
```

## Envelope encryption

`PgCrypto` uses **envelope encryption**, the same scheme a KMS expects:

- Each value is encrypted with its own freshly generated **data key** (DEK) under AES-256-GCM.
- That data key is then **wrapped** (encrypted) by a long-lived **key-encryption key** (KEK).
- The wire form stored in Postgres is `[version][keyId][wrapped data key][nonce][ciphertext+tag]`.

Two properties fall out of this. The KEK never touches the database, so a dump of Postgres is useless
without it. And because every value has its own data key, disclosing the database plus one leaked data
key exposes one value, not the whole column.

## Key custody is a provider

Where the KEK lives, and who can use it, is the one thing you should not hardcode. It is a
`KeyProvider` (pure JDK, no third-party types):

```java
public interface KeyProvider {
  record WrappedKey(String keyId, byte[] wrapped) {}
  WrappedKey wrap(byte[] dataKey);
  byte[] unwrap(String keyId, byte[] wrapped);
}
```

### Local keys (development, or a simple deployment)

The default `LocalKeyProvider` holds KEKs in the JVM. For a single key, set it at startup:

```java
PgCrypto.setKey(kms.fetchKek());                 // a 32-byte AES-256 KEK, or
// MONOLITH_FIELD_KEY=<base64-32-bytes>          // the env var, read at class load
```

### A KMS (production)

For real key custody, implement `KeyProvider` against a KMS in its own module (so the AWS/GCP/Vault
SDK stays out of the core), and install it:

```java
PgCrypto.setKeyProvider(new AwsKmsKeyProvider(cmkArn)); // wrap -> KMS Encrypt, unwrap -> KMS Decrypt
```

The KEK now never enters your process: wrapping and unwrapping data keys are KMS calls, and access is
governed by the KMS's own policy and audit log. This adapter is the recommended path for HIPAA and
similar; it is a small module to add, not core surface.

## Key rotation

The `keyId` in every value records which KEK wrapped it, so KEK versions coexist. Rotate by introducing
a new KEK that new writes use, while existing values still decrypt under the KEK that wrapped them:

```java
var provider = new LocalKeyProvider();
provider.addKey("2025", lastYearsKek);  // still registered: last year's rows decrypt
provider.addKey("2026", thisYearsKek);  // now current: new writes wrap under it
PgCrypto.setKeyProvider(provider);
```

A KMS-backed provider gets this for free: KMS keeps prior key versions, so old values keep decrypting
while new writes use the current version, with no data re-encryption. To fully retire an old KEK, read
and re-write the affected rows (which re-wraps their data keys under the current KEK).

## Notes

- `@Encrypted` applies to `String` components. The plaintext lives only in the JVM heap during
  encrypt/decrypt; data keys are zeroed after use.
- Encryption removes the column from value-precise reactive matching and from SQL predicates (the
  database sees only ciphertext); filter and match on non-encrypted columns.