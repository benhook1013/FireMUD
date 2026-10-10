package net.firedevops.firemud.accountservice.service.session;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Account-owned AEAD primitive for exact, bounded response-byte recovery. */
@Component
public final class AccountResponseEnvelopeCryptography {
  static final int NONCE_BYTES = 12;
  static final int TAG_BITS = 128;
  static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
  static final int MAX_RESPONSE_BYTES = 64 * 1024;
  static final int MAX_BINDING_BYTES = 16 * 1024;
  private static final byte[] MAGIC = {'F', 'R', 'E', 'A'};
  private static final byte FORMAT_VERSION = 1;
  private static final int FIXED_HEADER_BYTES = MAGIC.length + 1 + 1 + Long.BYTES + NONCE_BYTES;
  private static final int MAX_HEADER_BYTES = FIXED_HEADER_BYTES + 32;
  static final int MAX_ENVELOPE_BYTES = MAX_HEADER_BYTES + MAX_RESPONSE_BYTES + TAG_BYTES;
  private static final byte[] AAD_DOMAIN =
      "firemud/account-response-envelope/aad/v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] BINDING_DOMAIN =
      "firemud/account-response-envelope/binding/v1".getBytes(StandardCharsets.US_ASCII);
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:/@+-]{1,256}");

  private final AccountResponseEnvelopeKeyring keyring;
  private final Clock clock;
  private final SecureRandom secureRandom;

  @Autowired
  public AccountResponseEnvelopeCryptography(AccountResponseEnvelopeKeyring keyring) {
    this(keyring, Clock.systemUTC(), new SecureRandom());
  }

  AccountResponseEnvelopeCryptography(
      AccountResponseEnvelopeKeyring keyring, Clock clock, SecureRandom secureRandom) {
    this.keyring = Objects.requireNonNull(keyring, "keyring");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
  }

  /** Encrypts exact response bytes under the current active key and immutable operation expiry. */
  public EncryptedResponseEnvelope encrypt(
      Binding binding, byte[] exactResponseBytes, Instant immutableRecoveryExpiry) {
    Objects.requireNonNull(binding, "binding");
    validateResponse(exactResponseBytes);
    long expiryMillis = exactEpochMillis(immutableRecoveryExpiry);
    if (!clock.instant().isBefore(immutableRecoveryExpiry)) {
      throw new IllegalArgumentException("Recovery expiry must be in the future when sealing");
    }

    AccountResponseEnvelopeKeyring.KeyEntry active = keyring.readCurrent().activeKey();
    byte[] nonce = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    byte[] header = encodeHeader(active.keyId(), expiryMillis, nonce);
    byte[] bindingBytes = canonicalBinding(binding);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, active.secretKey(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(associatedData(header, bindingBytes));
      byte[] ciphertext = cipher.doFinal(exactResponseBytes);
      if (ciphertext.length != exactResponseBytes.length + TAG_BYTES) {
        throw new CryptographyUnavailableException();
      }
      return new EncryptedResponseEnvelope(concatenate(header, ciphertext));
    } catch (GeneralSecurityException ex) {
      throw new CryptographyUnavailableException();
    }
  }

  /**
   * Decrypts only for the exact immutable owner binding and expiry read from the durable operation.
   * The caller must establish current authorization and postconditions before invoking this method.
   */
  public byte[] decrypt(
      EncryptedResponseEnvelope envelope, Binding expectedBinding, Instant expectedRecoveryExpiry) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(expectedBinding, "expectedBinding");
    long expectedExpiryMillis = exactEpochMillis(expectedRecoveryExpiry);
    byte[] encoded = envelope.bytes();
    Header header = parseHeader(encoded);
    if (header.expiryMillis() != expectedExpiryMillis) {
      throw new IntegrityException();
    }
    Instant now = clock.instant();
    if (!now.isBefore(expectedRecoveryExpiry)) {
      throw new ResponseRecoveryExpiredException();
    }

    AccountResponseEnvelopeKeyring.KeyEntry key =
        keyring.readCurrent().decryptionKey(header.keyId(), now);
    byte[] ciphertext = Arrays.copyOfRange(encoded, header.headerLength(), encoded.length);
    byte[] bindingBytes = canonicalBinding(expectedBinding);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BITS, header.nonce()));
      cipher.updateAAD(associatedData(Arrays.copyOf(encoded, header.headerLength()), bindingBytes));
      return cipher.doFinal(ciphertext);
    } catch (AEADBadTagException ex) {
      throw new IntegrityException();
    } catch (GeneralSecurityException ex) {
      throw new CryptographyUnavailableException();
    }
  }

  private static byte[] encodeHeader(String keyId, long expiryMillis, byte[] nonce) {
    byte[] keyIdBytes = keyId.getBytes(StandardCharsets.US_ASCII);
    if (keyIdBytes.length == 0 || keyIdBytes.length > 32 || nonce.length != NONCE_BYTES) {
      throw new CryptographyUnavailableException();
    }
    ByteBuffer header = ByteBuffer.allocate(FIXED_HEADER_BYTES + keyIdBytes.length);
    header.put(MAGIC);
    header.put(FORMAT_VERSION);
    header.put((byte) keyIdBytes.length);
    header.put(keyIdBytes);
    header.putLong(expiryMillis);
    header.put(nonce);
    return header.array();
  }

  private static Header parseHeader(byte[] encoded) {
    if (encoded == null
        || encoded.length < FIXED_HEADER_BYTES + 1 + TAG_BYTES + 1
        || encoded.length > MAX_ENVELOPE_BYTES) {
      throw new IntegrityException();
    }
    ByteBuffer input = ByteBuffer.wrap(encoded);
    byte[] magic = new byte[MAGIC.length];
    input.get(magic);
    if (!Arrays.equals(magic, MAGIC) || input.get() != FORMAT_VERSION) {
      throw new IntegrityException();
    }
    int keyIdLength = Byte.toUnsignedInt(input.get());
    if (keyIdLength < 1
        || keyIdLength > 32
        || input.remaining() < keyIdLength + Long.BYTES + NONCE_BYTES + TAG_BYTES + 1) {
      throw new IntegrityException();
    }
    byte[] keyIdBytes = new byte[keyIdLength];
    input.get(keyIdBytes);
    for (byte value : keyIdBytes) {
      if (Byte.toUnsignedInt(value) > 0x7e || Byte.toUnsignedInt(value) < 0x21) {
        throw new IntegrityException();
      }
    }
    String keyId = new String(keyIdBytes, StandardCharsets.US_ASCII);
    if (!keyId.matches("[A-Za-z0-9_-]{1,32}")) {
      throw new IntegrityException();
    }
    long expiryMillis = input.getLong();
    if (expiryMillis <= 0) {
      throw new IntegrityException();
    }
    byte[] nonce = new byte[NONCE_BYTES];
    input.get(nonce);
    int ciphertextBytes = input.remaining();
    if (ciphertextBytes < TAG_BYTES + 1 || ciphertextBytes > MAX_RESPONSE_BYTES + TAG_BYTES) {
      throw new IntegrityException();
    }
    return new Header(keyId, expiryMillis, nonce, input.position());
  }

  private static byte[] canonicalBinding(Binding binding) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      output.write(BINDING_DOMAIN);
      writeString(output, binding.operation());
      writeString(output, binding.requestId());
      writeBytes(output, binding.requestDigest());
      writeString(output, binding.callerWorkload());
      writeString(output, binding.bindingId());
      writeOptionalString(output, binding.lineageId());
      writeOptionalString(output, binding.replacementId());
      writeOptionalString(output, binding.leaseId());
      output.writeLong(binding.issuanceFence());
      writeBytes(output, binding.authorityTupleCanonicalBytes());
      writeBytes(output, binding.membershipVersionMapCanonicalBytes());
      writeBytes(output, binding.authorityEvidenceBundleCanonicalBytes());
    } catch (IOException ex) {
      throw new IntegrityException();
    }
    byte[] encoded = bytes.toByteArray();
    if (encoded.length > MAX_BINDING_BYTES) {
      throw new IllegalArgumentException("Response-envelope binding exceeds its size bound");
    }
    return encoded;
  }

  private static void writeOptionalString(DataOutputStream output, Optional<String> value)
      throws IOException {
    output.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      writeString(output, value.orElseThrow());
    }
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
    output.writeInt(value.length);
    output.write(value);
  }

  private static byte[] associatedData(byte[] header, byte[] binding) {
    ByteBuffer aad =
        ByteBuffer.allocate(AAD_DOMAIN.length + Integer.BYTES * 2 + header.length + binding.length);
    aad.put(AAD_DOMAIN);
    aad.putInt(header.length);
    aad.put(header);
    aad.putInt(binding.length);
    aad.put(binding);
    return aad.array();
  }

  private static byte[] concatenate(byte[] header, byte[] ciphertext) {
    if (header.length + ciphertext.length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Response envelope exceeds its size bound");
    }
    byte[] result = Arrays.copyOf(header, header.length + ciphertext.length);
    System.arraycopy(ciphertext, 0, result, header.length, ciphertext.length);
    return result;
  }

  private static void validateResponse(byte[] response) {
    if (response == null || response.length == 0 || response.length > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Response bytes are empty or exceed their size bound");
    }
  }

  private static long exactEpochMillis(Instant instant) {
    Objects.requireNonNull(instant, "immutableRecoveryExpiry");
    try {
      long millis = instant.toEpochMilli();
      if (!Instant.ofEpochMilli(millis).equals(instant) || millis <= 0) {
        throw new IllegalArgumentException("Recovery expiry must be a positive exact millisecond");
      }
      return millis;
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Recovery expiry is outside the supported range");
    }
  }

  private static void validateIdentifier(String value, String fieldName) {
    if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(fieldName + " is not a canonical identifier");
    }
  }

  private static void validateOptional(Optional<String> value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    value.ifPresent(item -> validateIdentifier(item, fieldName));
  }

  private record Header(String keyId, long expiryMillis, byte[] nonce, int headerLength) {}

  /**
   * Immutable, typed operation binding. The owner must derive every field from durable evidence.
   */
  public record Binding(
      String operation,
      String requestId,
      byte[] requestDigest,
      String callerWorkload,
      String bindingId,
      Optional<String> lineageId,
      Optional<String> replacementId,
      Optional<String> leaseId,
      long issuanceFence,
      byte[] authorityTupleCanonicalBytes,
      byte[] membershipVersionMapCanonicalBytes,
      byte[] authorityEvidenceBundleCanonicalBytes) {
    public Binding {
      validateIdentifier(operation, "operation");
      validateIdentifier(requestId, "requestId");
      validateIdentifier(callerWorkload, "callerWorkload");
      validateIdentifier(bindingId, "bindingId");
      validateOptional(lineageId, "lineageId");
      validateOptional(replacementId, "replacementId");
      validateOptional(leaseId, "leaseId");
      if (requestDigest == null || requestDigest.length != 32) {
        throw new IllegalArgumentException("requestDigest must contain exactly 32 bytes");
      }
      if (authorityTupleCanonicalBytes == null
          || authorityTupleCanonicalBytes.length == 0
          || authorityTupleCanonicalBytes.length > 8 * 1024) {
        throw new IllegalArgumentException("authority tuple bytes are empty or exceed their bound");
      }
      if (membershipVersionMapCanonicalBytes == null
          || membershipVersionMapCanonicalBytes.length == 0
          || membershipVersionMapCanonicalBytes.length > 4 * 1024) {
        throw new IllegalArgumentException(
            "membership version map bytes are empty or exceed their bound");
      }
      if (authorityEvidenceBundleCanonicalBytes == null
          || authorityEvidenceBundleCanonicalBytes.length == 0
          || authorityEvidenceBundleCanonicalBytes.length > 8 * 1024) {
        throw new IllegalArgumentException(
            "authority evidence bundle bytes are empty or exceed their bound");
      }
      if (issuanceFence <= 0) {
        throw new IllegalArgumentException("issuanceFence must be positive");
      }
      requestDigest = requestDigest.clone();
      authorityTupleCanonicalBytes = authorityTupleCanonicalBytes.clone();
      membershipVersionMapCanonicalBytes = membershipVersionMapCanonicalBytes.clone();
      authorityEvidenceBundleCanonicalBytes = authorityEvidenceBundleCanonicalBytes.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }

    @Override
    public byte[] authorityTupleCanonicalBytes() {
      return authorityTupleCanonicalBytes.clone();
    }

    @Override
    public byte[] membershipVersionMapCanonicalBytes() {
      return membershipVersionMapCanonicalBytes.clone();
    }

    @Override
    public byte[] authorityEvidenceBundleCanonicalBytes() {
      return authorityEvidenceBundleCanonicalBytes.clone();
    }

    @Override
    public String toString() {
      return "AccountResponseEnvelopeCryptography.Binding[redacted]";
    }
  }

  /** Defensively copied opaque ciphertext; no response bytes are exposed through diagnostics. */
  public record EncryptedResponseEnvelope(byte[] bytes) {
    public EncryptedResponseEnvelope {
      if (bytes == null) {
        throw new IllegalArgumentException("Envelope bytes are required");
      }
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public String toString() {
      return "EncryptedResponseEnvelope[redacted]";
    }
  }

  /** Integrity/format failure; callers must fail closed and must not expose a token. */
  public static final class IntegrityException extends RuntimeException {
    private IntegrityException() {
      super("Account response envelope failed integrity validation");
    }
  }

  /** Maps only the immutable owner-recorded response horizon to the canonical terminal outcome. */
  public static final class ResponseRecoveryExpiredException extends RuntimeException {
    private ResponseRecoveryExpiredException() {
      super("Account response recovery horizon has expired");
    }

    public String errorCode() {
      return "RESPONSE_RECOVERY_EXPIRED";
    }
  }

  /** Retryable provider/runtime failure, without including secret or response material. */
  public static final class CryptographyUnavailableException extends RuntimeException {
    private CryptographyUnavailableException() {
      super("Account response-envelope cryptography is unavailable");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }
}
