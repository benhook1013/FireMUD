package unit.net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import net.firedevops.firemud.accountservice.security.AccountControlUiResponseEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException.Failure;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountControlUiResponseEnvelopeCryptoTest {
  private static final String ACCOUNT_ID = "b61a90bc-cdb4-42ac-9067-08667de2ac84";
  private static final String OPERATION_ID = "5d2421a0-dca2-4f74-90db-f29345302029";
  private static final String REQUEST_ID = "0418d715-8b6f-47cf-8625-85229f481419";
  private static final String TOKEN_HASH = "12".repeat(32);
  private static final Instant ISSUED_AT = Instant.parse("2030-05-06T07:08:09Z");
  private static final Instant EXPIRES_AT = Instant.parse("2030-05-06T08:08:09Z");

  @TempDir private Path temporaryDirectory;

  private Path manifestPath;

  @BeforeEach
  void setUp() {
    manifestPath = temporaryDirectory.resolve("manifest.v1");
  }

  @Test
  void exactResponseDecryptsAfterCryptoObjectRecreationAndChecksItsDigest() throws Exception {
    writeManifest(true);
    AccountControlUiResponseEnvelopeBinding binding = binding();
    byte[] exactResponse =
        "the exact original control-ui response".getBytes(StandardCharsets.UTF_8);
    AccountEnvelopeCrypto writer = new AccountEnvelopeCrypto(manifestPath);

    AccountEncryptedEnvelope envelope = writer.encryptControlUiResponse(binding, exactResponse);
    byte[] recovered =
        new AccountEnvelopeCrypto(manifestPath).decryptControlUiResponse(envelope, binding);

    assertEquals("k1", envelope.keyId());
    assertEquals(AccountEnvelopePurpose.CONTROL_UI_RESPONSE, envelope.purpose());
    assertArrayEquals(exactResponse, recovered);
    assertEquals("control-ui", binding.profile());
    assertEquals("control-ui", binding.audience());
    assertFailure(
        Failure.INVALID_ENVELOPE,
        () ->
            writer.encryptControlUiResponse(
                binding, "changed response".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rotationKeepsTheOriginalEnvelopeBytesDecryptableUntilEvidenceBasedRemoval()
      throws Exception {
    writeManifest(true);
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountControlUiResponseEnvelopeBinding binding = binding();
    byte[] exactResponse =
        "the exact original control-ui response".getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope originalEnvelope =
        crypto.encryptControlUiResponse(binding, exactResponse);
    byte[] originalNonce = originalEnvelope.nonce();
    byte[] originalCiphertext = originalEnvelope.ciphertext();

    writeRotatedManifest(true);
    AccountEnvelopeCrypto restartedReader = new AccountEnvelopeCrypto(manifestPath);
    assertEquals("k2", restartedReader.encryptControlUiResponse(binding(), exactResponse).keyId());
    assertArrayEquals(originalNonce, originalEnvelope.nonce());
    assertArrayEquals(originalCiphertext, originalEnvelope.ciphertext());
    assertArrayEquals(
        exactResponse, restartedReader.decryptControlUiResponse(originalEnvelope, binding));

    writeRotatedManifest(false);
    assertFailure(
        Failure.KEY_UNAVAILABLE,
        () -> restartedReader.decryptControlUiResponse(originalEnvelope, binding));
  }

  @Test
  void everyVariableBindingFieldIsAuthenticated() throws Exception {
    writeManifest(true);
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    byte[] response = "response body".getBytes(StandardCharsets.UTF_8);
    AccountControlUiResponseEnvelopeBinding binding =
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT);
    AccountEncryptedEnvelope envelope = crypto.encryptControlUiResponse(binding, response);

    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            "08cd8f48-7040-450a-a3de-ece2bb90dc20",
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            "27acd7d4-4d1c-44e6-baea-0c902f5f039e",
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            "853745a3-6cf9-4962-a4e4-6d668b022c2f",
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(11),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            "34".repeat(32),
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digest(12),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(13),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(14),
            ISSUED_AT,
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT.plusSeconds(1),
            EXPIRES_AT));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT.plusSeconds(1)));
  }

  @Test
  void wrongPurposeRelabelingAndMissingPurposeFailClosedWithoutFallback() throws Exception {
    writeManifest(true);
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    byte[] response = "response body".getBytes(StandardCharsets.UTF_8);
    AccountControlUiResponseEnvelopeBinding binding =
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digestFor(response),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT);
    AccountEncryptedEnvelope envelope = crypto.encryptControlUiResponse(binding, response);

    AccountEncryptedEnvelope relabeled =
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            envelope.nonce(),
            envelope.ciphertext());
    assertFailure(
        Failure.PURPOSE_MISMATCH, () -> crypto.decryptControlUiResponse(relabeled, binding));
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () ->
            crypto.decrypt(
                relabeled, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, connectBinding()));

    writeManifest(false);
    assertFailure(
        Failure.KEY_UNAVAILABLE, () -> crypto.encryptControlUiResponse(binding, response));
    AccountEnvelopeBinding oldPurposeBinding = connectBinding();
    AccountEncryptedEnvelope oldPurposeEnvelope =
        crypto.encrypt(
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            oldPurposeBinding,
            "old purpose still works".getBytes(StandardCharsets.UTF_8));
    assertEquals(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, oldPurposeEnvelope.purpose());
  }

  @Test
  void bindingCopiesDigestBytesComparesByValueAndRedactsItsRepresentation() throws Exception {
    byte[] requestDigest = digest(1);
    byte[] responseDigest = digest(2);
    byte[] captureDigest = digest(3);
    byte[] fenceDigest = digest(4);
    AccountControlUiResponseEnvelopeBinding binding =
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            requestDigest,
            TOKEN_HASH,
            responseDigest,
            captureDigest,
            fenceDigest,
            ISSUED_AT,
            EXPIRES_AT);
    Arrays.fill(requestDigest, (byte) 0);
    Arrays.fill(responseDigest, (byte) 0);
    Arrays.fill(captureDigest, (byte) 0);
    Arrays.fill(fenceDigest, (byte) 0);

    AccountControlUiResponseEnvelopeBinding equalBinding =
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digest(2),
            digest(3),
            digest(4),
            ISSUED_AT,
            EXPIRES_AT);
    byte[] exposedRequestDigest = binding.requestDigest();
    byte[] exposedResponseDigest = binding.responseDigest();
    byte[] exposedCaptureDigest = binding.authorityCaptureDigest();
    byte[] exposedFenceDigest = binding.issuanceFenceDigest();
    Arrays.fill(exposedRequestDigest, (byte) 0);
    Arrays.fill(exposedResponseDigest, (byte) 0);
    Arrays.fill(exposedCaptureDigest, (byte) 0);
    Arrays.fill(exposedFenceDigest, (byte) 0);

    assertEquals(equalBinding, binding);
    assertEquals(equalBinding.hashCode(), binding.hashCode());
    assertArrayEquals(digest(1), binding.requestDigest());
    assertArrayEquals(digest(2), binding.responseDigest());
    assertArrayEquals(digest(3), binding.authorityCaptureDigest());
    assertArrayEquals(digest(4), binding.issuanceFenceDigest());
    assertFalse(binding.toString().contains(ACCOUNT_ID));
    assertFalse(binding.toString().contains(TOKEN_HASH));
    assertFalse(binding.toString().contains("12".repeat(32)));
    assertNotEquals(
        binding,
        binding(
            ACCOUNT_ID,
            OPERATION_ID,
            REQUEST_ID,
            digest(1),
            TOKEN_HASH,
            digest(2),
            digest(3),
            digest(5),
            ISSUED_AT,
            EXPIRES_AT));
  }

  @Test
  void malformedIdentifiersDigestsHashesAndInstantsAreRejected() throws Exception {
    byte[] responseDigest = digest(2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                "B61A90BC-CDB4-42AC-9067-08667DE2AC84",
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                "00000000-0000-0000-0000-000000000000",
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                "not-a-uuid",
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                "not-a-uuid",
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                REQUEST_ID,
                new byte[31],
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                "AB".repeat(32),
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                new byte[31],
                digest(4),
                ISSUED_AT,
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                ISSUED_AT.plusNanos(1),
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                Instant.ofEpochSecond(0),
                EXPIRES_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            binding(
                ACCOUNT_ID,
                OPERATION_ID,
                REQUEST_ID,
                digest(1),
                TOKEN_HASH,
                responseDigest,
                digest(3),
                digest(4),
                EXPIRES_AT,
                ISSUED_AT));
  }

  private void writeManifest(boolean includeControlUiPurpose) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("version=1");
    lines.add("activeKeyId=k1");
    lines.add("key:k1:bare-login=" + encode(key(1)));
    lines.add("key:k1:connect-token=" + encode(key(2)));
    lines.add("key:k1:pending-reset=" + encode(key(3)));
    if (includeControlUiPurpose) {
      lines.add("key:k1:control-ui-response=" + encode(key(4)));
    }
    Files.writeString(manifestPath, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
  }

  private void writeRotatedManifest(boolean retainOriginalKey) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("version=1");
    lines.add("activeKeyId=k2");
    if (retainOriginalKey) {
      appendKeySet(lines, "k1", 1);
    }
    appendKeySet(lines, "k2", 5);
    Files.writeString(manifestPath, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
  }

  private static void appendKeySet(List<String> lines, String keyId, int firstKey) {
    lines.add("key:" + keyId + ":bare-login=" + encode(key(firstKey)));
    lines.add("key:" + keyId + ":connect-token=" + encode(key(firstKey + 1)));
    lines.add("key:" + keyId + ":pending-reset=" + encode(key(firstKey + 2)));
    lines.add("key:" + keyId + ":control-ui-response=" + encode(key(firstKey + 3)));
  }

  private static AccountControlUiResponseEnvelopeBinding binding() throws Exception {
    byte[] response = "the exact original control-ui response".getBytes(StandardCharsets.UTF_8);
    return binding(
        ACCOUNT_ID,
        OPERATION_ID,
        REQUEST_ID,
        digest(1),
        TOKEN_HASH,
        digestFor(response),
        digest(3),
        digest(4),
        ISSUED_AT,
        EXPIRES_AT);
  }

  private static AccountControlUiResponseEnvelopeBinding binding(
      String accountId,
      String operationId,
      String requestId,
      byte[] requestDigest,
      String tokenHash,
      byte[] responseDigest,
      byte[] authorityCaptureDigest,
      byte[] issuanceFenceDigest,
      Instant issuedAt,
      Instant expiresAt) {
    return new AccountControlUiResponseEnvelopeBinding(
        accountId,
        operationId,
        requestId,
        requestDigest,
        tokenHash,
        responseDigest,
        authorityCaptureDigest,
        issuanceFenceDigest,
        issuedAt,
        expiresAt);
  }

  private static AccountEnvelopeBinding connectBinding() {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
        "connect-op-1",
        "connect-request-1",
        "account-17",
        "tenant-a",
        "connect-scope-a",
        null,
        digest(1),
        digest(2),
        digest(3),
        digest(4),
        digest(5));
  }

  private static void assertAuthenticationFailure(
      AccountEnvelopeCrypto crypto,
      AccountEncryptedEnvelope envelope,
      AccountControlUiResponseEnvelopeBinding binding) {
    assertFailure(
        Failure.AUTHENTICATION_FAILED, () -> crypto.decryptControlUiResponse(envelope, binding));
  }

  private static void assertFailure(Failure expected, Runnable action) {
    AccountEnvelopeCryptoException exception =
        assertThrows(AccountEnvelopeCryptoException.class, action::run);
    assertEquals(expected, exception.failure());
  }

  private static byte[] digest(int value) {
    byte[] digest = new byte[32];
    Arrays.fill(digest, (byte) value);
    return digest;
  }

  private static byte[] digestFor(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static String encode(byte[] key) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private static byte[] key(int value) {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) value);
    return key;
  }
}
