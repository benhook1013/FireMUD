package net.firedevops.firemud.accountservice.security;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Account-local AES-256-GCM for bounded credential-bearing response envelopes. The mounted manifest
 * is read for every operation so atomic rotation and key removal take effect immediately. Callers
 * must provide an Account-only mounted path and must read back the exact durable operation/envelope
 * before using {@link #decrypt} for recovery.
 *
 * <p>Manifest v1 is strict ASCII (an ASCII subset of UTF-8), terminated by one LF:
 *
 * <pre>
 * version=1
 * activeKeyId=k2
 * key:k1:bare-login=&lt;43-character-unpadded-base64url&gt;
 * key:k1:connect-token=&lt;43-character-unpadded-base64url&gt;
 * key:k2:bare-login=&lt;43-character-unpadded-base64url&gt;
 * key:k2:connect-token=&lt;43-character-unpadded-base64url&gt;
 * </pre>
 *
 * Every key ID must have one independent 32-byte key for each purpose. The active ID is used for
 * new writes; retained older IDs are decrypt-only. No default key or classpath fallback exists.
 */
public final class AccountEnvelopeCrypto {
  public static final String KEY_RING_PATH_ENVIRONMENT_VARIABLE =
      "FIREMUD_AUTH_RESPONSE_ENVELOPE_KEY_RING_PATH";
  public static final String EXPECTED_MOUNT_PATH =
      "/var/run/secrets/firemud/response-envelope-key-ring/manifest.v1";

  private static final int MAX_MANIFEST_LENGTH_BYTES = 64 * 1024;
  private static final int AES_KEY_LENGTH_BYTES = 32;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int MAX_AAD_FIELD_LENGTH_BYTES = 4096;
  private static final byte[] AAD_DOMAIN =
      "firemud-account-response-envelope/v1".getBytes(StandardCharsets.US_ASCII);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final Path manifestPath;

  public AccountEnvelopeCrypto(Path mountedManifestPath) {
    Objects.requireNonNull(mountedManifestPath, "mountedManifestPath");
    if (!mountedManifestPath.isAbsolute()) {
      throw new IllegalArgumentException("The Account key-ring path must be absolute");
    }
    this.manifestPath = mountedManifestPath;
  }

  /** Creates the runtime implementation from the required Account-only mount environment value. */
  public static AccountEnvelopeCrypto fromEnvironment() {
    String configuredPath = System.getenv(KEY_RING_PATH_ENVIRONMENT_VARIABLE);
    if (configuredPath == null || configuredPath.isBlank()) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_UNAVAILABLE);
    }
    try {
      return new AccountEnvelopeCrypto(Path.of(configuredPath));
    } catch (RuntimeException exception) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_UNAVAILABLE);
    }
  }

  public AccountEncryptedEnvelope encrypt(
      AccountEnvelopePurpose purpose, AccountEnvelopeBinding binding, byte[] plaintext) {
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(plaintext, "plaintext");
    requirePurposeBinding(purpose, binding);
    if (plaintext.length == 0
        || plaintext.length > AccountEncryptedEnvelope.MAX_PLAINTEXT_LENGTH_BYTES) {
      throw failure(AccountEnvelopeCryptoException.Failure.INVALID_ENVELOPE);
    }

    try (LoadedKeyRing keyRing = readKeyRing()) {
      String keyId = keyRing.activeKeyId;
      byte[] keyBytes = keyRing.keyBytes(keyId, purpose);
      byte[] nonce = new byte[AccountEncryptedEnvelope.NONCE_LENGTH_BYTES];
      SECURE_RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(keyBytes, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(
          associatedData(AccountEncryptedEnvelope.CURRENT_FORMAT_VERSION, keyId, purpose, binding));
      byte[] ciphertext = cipher.doFinal(plaintext);
      return new AccountEncryptedEnvelope(
          AccountEncryptedEnvelope.CURRENT_FORMAT_VERSION, keyId, purpose, nonce, ciphertext);
    } catch (AccountEnvelopeCryptoException exception) {
      throw exception;
    } catch (GeneralSecurityException exception) {
      throw failure(AccountEnvelopeCryptoException.Failure.CRYPTO_OPERATION_FAILED);
    }
  }

  public byte[] decrypt(
      AccountEncryptedEnvelope envelope,
      AccountEnvelopePurpose expectedPurpose,
      AccountEnvelopeBinding binding) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(expectedPurpose, "expectedPurpose");
    Objects.requireNonNull(binding, "binding");
    requirePurposeBinding(expectedPurpose, binding);
    if (envelope.purpose() != expectedPurpose) {
      throw failure(AccountEnvelopeCryptoException.Failure.PURPOSE_MISMATCH);
    }

    try (LoadedKeyRing keyRing = readKeyRing()) {
      byte[] keyBytes = keyRing.keyBytes(envelope.keyId(), envelope.purpose());
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(keyBytes, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.nonce()));
      cipher.updateAAD(
          associatedData(envelope.formatVersion(), envelope.keyId(), envelope.purpose(), binding));
      return cipher.doFinal(envelope.ciphertext());
    } catch (AEADBadTagException exception) {
      throw failure(AccountEnvelopeCryptoException.Failure.AUTHENTICATION_FAILED);
    } catch (AccountEnvelopeCryptoException exception) {
      throw exception;
    } catch (GeneralSecurityException exception) {
      throw failure(AccountEnvelopeCryptoException.Failure.CRYPTO_OPERATION_FAILED);
    }
  }

  private LoadedKeyRing readKeyRing() {
    byte[] manifest = null;
    try {
      if (!Files.isRegularFile(manifestPath)) {
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_UNAVAILABLE);
      }
      try (InputStream input = Files.newInputStream(manifestPath)) {
        manifest = input.readNBytes(MAX_MANIFEST_LENGTH_BYTES + 1);
      }
      if (manifest.length == 0 || manifest.length > MAX_MANIFEST_LENGTH_BYTES) {
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
      }
      return parseManifest(manifest);
    } catch (AccountEnvelopeCryptoException exception) {
      throw exception;
    } catch (IOException | SecurityException exception) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_UNAVAILABLE);
    } finally {
      if (manifest != null) {
        Arrays.fill(manifest, (byte) 0);
      }
    }
  }

  private static LoadedKeyRing parseManifest(byte[] manifest) {
    Map<String, EnumMap<AccountEnvelopePurpose, byte[]>> keys = new HashMap<>();
    try {
      int lineNumber = 0;
      int lineStart = 0;
      String activeKeyId = null;
      ArrayList<byte[]> seenKeyMaterial = new ArrayList<>();
      if (manifest.length == 0 || manifest[manifest.length - 1] != '\n') {
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
      }
      for (int index = 0; index < manifest.length; index++) {
        byte value = manifest[index];
        if (value == '\r' || (value < 0x21 && value != '\n') || value > 0x7e) {
          throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
        }
        if (value != '\n') {
          continue;
        }
        if (index == lineStart) {
          throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
        }
        lineNumber++;
        if (lineNumber == 1) {
          if (!lineEquals(manifest, lineStart, index, "version=1")) {
            throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
          }
        } else if (lineNumber == 2) {
          String line = asciiString(manifest, lineStart, index);
          String prefix = "activeKeyId=";
          if (!line.startsWith(prefix)) {
            throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
          }
          activeKeyId = line.substring(prefix.length());
          if (!isValidKeyId(activeKeyId)) {
            throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
          }
        } else {
          byte[] keyBytes = parseKeyLine(manifest, lineStart, index);
          String idAndPurpose = keyLineIdAndPurpose(manifest, lineStart, index);
          int separator = idAndPurpose.indexOf('\u0000');
          String keyId = idAndPurpose.substring(0, separator);
          AccountEnvelopePurpose purpose =
              AccountEnvelopePurpose.fromManifestName(idAndPurpose.substring(separator + 1));
          for (byte[] existing : seenKeyMaterial) {
            if (MessageDigest.isEqual(existing, keyBytes)) {
              Arrays.fill(keyBytes, (byte) 0);
              throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
            }
          }
          EnumMap<AccountEnvelopePurpose, byte[]> byPurpose =
              keys.computeIfAbsent(keyId, ignored -> new EnumMap<>(AccountEnvelopePurpose.class));
          if (byPurpose.putIfAbsent(purpose, keyBytes) != null) {
            Arrays.fill(keyBytes, (byte) 0);
            throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
          }
          seenKeyMaterial.add(keyBytes);
        }
        lineStart = index + 1;
      }
      if (lineNumber < 3 || activeKeyId == null) {
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
      }
      for (Map.Entry<String, EnumMap<AccountEnvelopePurpose, byte[]>> entry : keys.entrySet()) {
        if (entry.getValue().size() != AccountEnvelopePurpose.values().length) {
          throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
        }
      }
      if (!keys.containsKey(activeKeyId)) {
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
      }
      return new LoadedKeyRing(activeKeyId, keys);
    } catch (AccountEnvelopeCryptoException exception) {
      wipeKeys(keys);
      throw exception;
    } catch (RuntimeException exception) {
      wipeKeys(keys);
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
    }
  }

  private static byte[] parseKeyLine(byte[] manifest, int start, int end) {
    String identity = keyLineIdAndPurpose(manifest, start, end);
    int separator = identity.indexOf('\u0000');
    if (separator < 1
        || !isValidKeyId(identity.substring(0, separator))
        || AccountEnvelopePurpose.fromManifestName(identity.substring(separator + 1)) == null) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
    }
    int equalsIndex = -1;
    for (int index = start; index < end; index++) {
      if (manifest[index] == '=') {
        if (equalsIndex != -1) {
          throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
        }
        equalsIndex = index;
      }
    }
    int encodedLength = end - equalsIndex - 1;
    if (equalsIndex < 0 || encodedLength != 43) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
    }
    byte[] encoded = Arrays.copyOfRange(manifest, equalsIndex + 1, end);
    byte[] keyBytes = null;
    try {
      keyBytes = Base64.getUrlDecoder().decode(encoded);
      if (keyBytes.length != AES_KEY_LENGTH_BYTES
          || !Arrays.equals(Base64.getUrlEncoder().withoutPadding().encode(keyBytes), encoded)) {
        Arrays.fill(keyBytes, (byte) 0);
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
      }
      return keyBytes;
    } catch (IllegalArgumentException exception) {
      if (keyBytes != null) {
        Arrays.fill(keyBytes, (byte) 0);
      }
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
    } finally {
      Arrays.fill(encoded, (byte) 0);
    }
  }

  private static String keyLineIdAndPurpose(byte[] manifest, int start, int end) {
    int equalsIndex = -1;
    for (int index = start; index < end; index++) {
      if (manifest[index] == '=') {
        if (equalsIndex != -1) {
          throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
        }
        equalsIndex = index;
      }
    }
    if (equalsIndex <= start || !lineStartsWith(manifest, start, equalsIndex, "key:")) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
    }
    int purposeSeparator = -1;
    for (int index = start + 4; index < equalsIndex; index++) {
      if (manifest[index] == ':') {
        purposeSeparator = index;
        break;
      }
    }
    if (purposeSeparator <= start + 4 || purposeSeparator == equalsIndex - 1) {
      throw failure(AccountEnvelopeCryptoException.Failure.KEY_RING_MALFORMED);
    }
    String keyId = asciiString(manifest, start + 4, purposeSeparator);
    String purpose = asciiString(manifest, purposeSeparator + 1, equalsIndex);
    return keyId + '\u0000' + purpose;
  }

  private static byte[] associatedData(
      int formatVersion,
      String keyId,
      AccountEnvelopePurpose purpose,
      AccountEnvelopeBinding binding) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      writeFrame(output, AAD_DOMAIN);
      output.writeInt(formatVersion);
      writeFrame(output, utf8(keyId));
      writeFrame(output, utf8(purpose.manifestName()));
      writeFrame(output, utf8(binding.operationKind().name()));
      writeFrame(output, utf8(binding.operationId()));
      writeFrame(output, utf8(binding.requestId()));
      writeFrame(output, utf8(binding.accountId()));
      writeFrame(output, utf8(binding.tenantId()));
      writeFrame(output, utf8(binding.connectScopeId()));
      writeNullableFrame(output, nullableUtf8(binding.sourceConnectOperationId()));
      writeFrame(output, binding.requestDigest());
      writeFrame(output, binding.contextEvidenceDigest());
      writeFrame(output, binding.authorityTupleDigest());
      writeFrame(output, binding.issuanceFenceDigest());
      writeFrame(output, binding.postconditionDigest());
      output.flush();
      if (bytes.size() > MAX_AAD_FIELD_LENGTH_BYTES * 8) {
        throw failure(AccountEnvelopeCryptoException.Failure.INVALID_ENVELOPE);
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw failure(AccountEnvelopeCryptoException.Failure.CRYPTO_OPERATION_FAILED);
    }
  }

  private static void writeFrame(DataOutputStream output, byte[] value) throws IOException {
    if (value.length > MAX_AAD_FIELD_LENGTH_BYTES) {
      throw failure(AccountEnvelopeCryptoException.Failure.INVALID_ENVELOPE);
    }
    output.writeInt(value.length);
    output.write(value);
  }

  private static void writeNullableFrame(DataOutputStream output, byte[] value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
    } else {
      writeFrame(output, value);
    }
  }

  private static byte[] nullableUtf8(String value) {
    return value == null ? null : utf8(value);
  }

  private static byte[] utf8(String value) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_AAD_FIELD_LENGTH_BYTES) {
      throw failure(AccountEnvelopeCryptoException.Failure.INVALID_ENVELOPE);
    }
    return encoded;
  }

  private static void requirePurposeBinding(
      AccountEnvelopePurpose purpose, AccountEnvelopeBinding binding) {
    boolean matches =
        (purpose == AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE
                && binding.operationKind()
                    == AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE)
            || (purpose == AccountEnvelopePurpose.BARE_LOGIN_RESPONSE
                && binding.operationKind()
                    == AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE);
    if (!matches) {
      throw failure(AccountEnvelopeCryptoException.Failure.PURPOSE_MISMATCH);
    }
  }

  private static void wipeKeys(Map<String, EnumMap<AccountEnvelopePurpose, byte[]>> keys) {
    for (EnumMap<AccountEnvelopePurpose, byte[]> byPurpose : keys.values()) {
      for (byte[] keyBytes : byPurpose.values()) {
        Arrays.fill(keyBytes, (byte) 0);
      }
    }
    keys.clear();
  }

  private static boolean isValidKeyId(String keyId) {
    if (keyId == null || keyId.isEmpty() || keyId.length() > 64) {
      return false;
    }
    for (int index = 0; index < keyId.length(); index++) {
      char value = keyId.charAt(index);
      if (!((value >= 'a' && value <= 'z')
          || (value >= 'A' && value <= 'Z')
          || (value >= '0' && value <= '9')
          || value == '_'
          || value == '-')) {
        return false;
      }
    }
    return true;
  }

  private static boolean lineEquals(byte[] source, int start, int end, String expected) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
    return end - start == expectedBytes.length
        && regionMatches(source, start, expectedBytes, 0, expectedBytes.length);
  }

  private static boolean lineStartsWith(byte[] source, int start, int end, String expected) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
    return end - start >= expectedBytes.length
        && regionMatches(source, start, expectedBytes, 0, expectedBytes.length);
  }

  private static boolean regionMatches(
      byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
    for (int index = 0; index < length; index++) {
      if (source[sourceOffset + index] != target[targetOffset + index]) {
        return false;
      }
    }
    return true;
  }

  private static String asciiString(byte[] source, int start, int end) {
    return new String(source, start, end - start, StandardCharsets.US_ASCII);
  }

  private static AccountEnvelopeCryptoException failure(
      AccountEnvelopeCryptoException.Failure failure) {
    return new AccountEnvelopeCryptoException(failure);
  }

  private static final class LoadedKeyRing implements AutoCloseable {
    private final String activeKeyId;
    private final Map<String, EnumMap<AccountEnvelopePurpose, byte[]>> keys;

    private LoadedKeyRing(
        String activeKeyId, Map<String, EnumMap<AccountEnvelopePurpose, byte[]>> keys) {
      this.activeKeyId = activeKeyId;
      this.keys = keys;
    }

    private byte[] keyBytes(String keyId, AccountEnvelopePurpose purpose) {
      EnumMap<AccountEnvelopePurpose, byte[]> byPurpose = keys.get(keyId);
      byte[] keyBytes = byPurpose == null ? null : byPurpose.get(purpose);
      if (keyBytes == null) {
        throw failure(AccountEnvelopeCryptoException.Failure.KEY_UNAVAILABLE);
      }
      return keyBytes;
    }

    @Override
    public void close() {
      wipeKeys(keys);
    }
  }
}
