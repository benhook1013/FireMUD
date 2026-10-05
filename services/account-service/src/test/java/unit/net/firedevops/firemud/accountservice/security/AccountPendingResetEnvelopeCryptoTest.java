package unit.net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException.Failure;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.AccountPendingResetEnvelopeBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountPendingResetEnvelopeCryptoTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("b61a90bc-cdb4-42ac-9067-08667de2ac84");
  private static final String TOKEN_HASH = "12".repeat(32);
  private static final LocalDateTime TOKEN_EXPIRES_AT =
      LocalDateTime.parse("2030-05-06T07:08:09.123");
  private static final String REQUEST_ID = "account-password-reset-request-v1:" + TOKEN_HASH;
  private static final String ARGON2_VERIFIER =
      "$argon2id$v=19$m=65536,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA$"
          + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String ARGON2I_VERIFIER =
      "$argon2i$v=19$m=65536,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA$"
          + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @TempDir private Path temporaryDirectory;

  private Path manifestPath;

  @BeforeEach
  void setUp() {
    manifestPath = temporaryDirectory.resolve("manifest.v1");
  }

  @Test
  void exactPendingVerifierDecryptsAfterCryptoObjectRecreation() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, 3));
    AccountPendingResetEnvelopeBinding binding = binding();
    byte[] encodedVerifier = ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8);
    AccountEnvelopeCrypto writer = new AccountEnvelopeCrypto(manifestPath);

    AccountEncryptedEnvelope envelope = writer.encryptPendingReset(binding, encodedVerifier);
    AccountEnvelopeCrypto restartedReader = new AccountEnvelopeCrypto(manifestPath);

    assertEquals("k1", envelope.keyId());
    assertEquals(AccountEnvelopePurpose.PENDING_PASSWORD_RESET, envelope.purpose());
    assertArrayEquals(encodedVerifier, restartedReader.decryptPendingReset(envelope, binding));
  }

  @Test
  void argon2iEncodingIsAccepted() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, 3));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    byte[] encodedVerifier = ARGON2I_VERIFIER.getBytes(StandardCharsets.UTF_8);
    AccountPendingResetEnvelopeBinding binding = bindingForVerifier(encodedVerifier);

    AccountEncryptedEnvelope envelope = crypto.encryptPendingReset(binding, encodedVerifier);

    assertArrayEquals(encodedVerifier, crypto.decryptPendingReset(envelope, binding));
  }

  @Test
  void currentAccountEncoderOutputRoundTripsWithoutRehashing() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, 3));
    Argon2 argon2 = Argon2Factory.create();
    char[] password = "component-only reset choice".toCharArray();
    byte[] encodedVerifier = null;
    byte[] recoveredVerifier = null;
    try {
      encodedVerifier = argon2.hash(2, 65536, 1, password).getBytes(StandardCharsets.UTF_8);
      AccountPendingResetEnvelopeBinding binding = bindingForVerifier(encodedVerifier);
      AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
      AccountEncryptedEnvelope envelope = crypto.encryptPendingReset(binding, encodedVerifier);
      recoveredVerifier =
          new AccountEnvelopeCrypto(manifestPath).decryptPendingReset(envelope, binding);
      assertArrayEquals(encodedVerifier, recoveredVerifier);
      assertTrue(argon2.verify(new String(recoveredVerifier, StandardCharsets.UTF_8), password));
    } finally {
      argon2.wipeArray(password);
      if (encodedVerifier != null) Arrays.fill(encodedVerifier, (byte) 0);
      if (recoveredVerifier != null) Arrays.fill(recoveredVerifier, (byte) 0);
    }
  }

  @Test
  void plaintextFastHashAndMalformedPhcWithMatchingDigestAreRejected() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, 3));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    List<byte[]> invalidVerifiers =
        List.of(
            "a submitted plaintext password".getBytes(StandardCharsets.UTF_8),
            "0".repeat(64).getBytes(StandardCharsets.US_ASCII),
            ("$argon2id$v=19$m=065536,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA$"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .getBytes(StandardCharsets.US_ASCII),
            ("$argon2id$v=19$m=0,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA$"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .getBytes(StandardCharsets.US_ASCII),
            ("$argon2id$v=19$m=65536,t=3,p=1$AAAAAAAAAAAAAAAAAAAA==$"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .getBytes(StandardCharsets.US_ASCII));

    for (byte[] invalidVerifier : invalidVerifiers) {
      AccountPendingResetEnvelopeBinding binding = bindingForVerifier(invalidVerifier);
      assertFailure(
          Failure.INVALID_ENVELOPE, () -> crypto.encryptPendingReset(binding, invalidVerifier));
    }
  }

  @Test
  void everyPendingResetBindingFieldAndCiphertextTamperingFailAuthentication() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, 3));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountPendingResetEnvelopeBinding binding = binding();
    byte[] verifier = ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope envelope = crypto.encryptPendingReset(binding, verifier);

    byte[] changedNonce = envelope.nonce();
    changedNonce[0] ^= 1;
    assertAuthenticationFailure(
        crypto,
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose(),
            changedNonce,
            envelope.ciphertext()),
        binding);

    byte[] changedCiphertext = envelope.ciphertext();
    changedCiphertext[0] ^= 1;
    assertAuthenticationFailure(
        crypto,
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose(),
            envelope.nonce(),
            changedCiphertext),
        binding);

    assertFailure(
        Failure.INVALID_ENVELOPE,
        () ->
            crypto.encryptPendingReset(
                binding(
                    ACCOUNT_ID,
                    TOKEN_HASH,
                    TOKEN_EXPIRES_AT,
                    REQUEST_ID,
                    digest(1),
                    digest(2),
                    digest(13)),
                verifier));

    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            TOKEN_HASH,
            TOKEN_EXPIRES_AT.plusSeconds(1),
            REQUEST_ID,
            digest(1),
            digest(2),
            digestFor(verifier)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            UUID.fromString("08cd8f48-7040-450a-a3de-ece2bb90dc20"),
            TOKEN_HASH,
            TOKEN_EXPIRES_AT,
            REQUEST_ID,
            digest(1),
            digest(2),
            digestFor(verifier)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            "34".repeat(32),
            TOKEN_EXPIRES_AT,
            REQUEST_ID,
            digest(1),
            digest(2),
            digestFor(verifier)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            TOKEN_HASH,
            TOKEN_EXPIRES_AT,
            REQUEST_ID + "-changed",
            digest(1),
            digest(2),
            digestFor(verifier)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            TOKEN_HASH,
            TOKEN_EXPIRES_AT,
            REQUEST_ID,
            digest(11),
            digest(2),
            digestFor(verifier)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            TOKEN_HASH,
            TOKEN_EXPIRES_AT,
            REQUEST_ID,
            digest(1),
            digest(12),
            digestFor(verifier)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        binding(
            ACCOUNT_ID,
            TOKEN_HASH,
            TOKEN_EXPIRES_AT,
            REQUEST_ID,
            digest(1),
            digest(2),
            digest(13)));
  }

  @Test
  void missingPendingKeyDeniesAndResponseEnvelopesCannotBeUsedAsPendingReset() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, null));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountPendingResetEnvelopeBinding pendingBinding = binding();
    AccountEnvelopeBinding responseBinding = responseBinding();
    AccountEncryptedEnvelope responseEnvelope =
        crypto.encrypt(
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            responseBinding,
            "response credential".getBytes(StandardCharsets.UTF_8));

    assertFailure(
        Failure.KEY_UNAVAILABLE,
        () ->
            crypto.encryptPendingReset(
                pendingBinding, ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8)));
    assertFailure(
        Failure.PURPOSE_MISMATCH,
        () -> crypto.decryptPendingReset(responseEnvelope, pendingBinding));

    writeManifest("k1", keySet("k1", 1, 2, 3));
    AccountEnvelopeCrypto fullRingCrypto = new AccountEnvelopeCrypto(manifestPath);
    AccountEncryptedEnvelope relabeledResponse =
        new AccountEncryptedEnvelope(
            responseEnvelope.formatVersion(),
            responseEnvelope.keyId(),
            AccountEnvelopePurpose.PENDING_PASSWORD_RESET,
            responseEnvelope.nonce(),
            responseEnvelope.ciphertext());
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () -> fullRingCrypto.decryptPendingReset(relabeledResponse, pendingBinding));

    AccountEncryptedEnvelope pendingEnvelope =
        fullRingCrypto.encryptPendingReset(
            pendingBinding, ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8));
    writeManifest("k1", keySet("k1", 1, 2, null));
    assertFailure(
        Failure.KEY_UNAVAILABLE,
        () ->
            new AccountEnvelopeCrypto(manifestPath)
                .decryptPendingReset(pendingEnvelope, pendingBinding));
  }

  @Test
  void retainedPendingKeyDecryptsExactVerifierWhileNewWritesUseOnlyActiveKey() throws Exception {
    KeySet oldKey = keySet("k1", 1, 2, 3);
    KeySet activeKey = keySet("k2", 4, 5, 6);
    writeManifest("k1", oldKey);
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountPendingResetEnvelopeBinding originalBinding = binding();
    byte[] originalVerifier = ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope originalEnvelope =
        crypto.encryptPendingReset(originalBinding, originalVerifier);

    writeManifest("k2", oldKey, activeKey);
    byte[] laterVerifier = ARGON2I_VERIFIER.getBytes(StandardCharsets.UTF_8);
    AccountPendingResetEnvelopeBinding laterBinding =
        binding(
            ACCOUNT_ID,
            "56".repeat(32),
            TOKEN_EXPIRES_AT.plusDays(1),
            "account-password-reset-request-v1:" + "56".repeat(32),
            digest(21),
            digest(22),
            digestFor(laterVerifier));
    AccountEncryptedEnvelope laterEnvelope =
        crypto.encryptPendingReset(laterBinding, laterVerifier);

    assertEquals("k2", laterEnvelope.keyId());
    assertArrayEquals(
        originalVerifier, crypto.decryptPendingReset(originalEnvelope, originalBinding));
    assertArrayEquals(laterVerifier, crypto.decryptPendingReset(laterEnvelope, laterBinding));

    writeManifest("k2", activeKey);
    assertFailure(
        Failure.KEY_UNAVAILABLE,
        () -> crypto.decryptPendingReset(originalEnvelope, originalBinding));
    assertArrayEquals(laterVerifier, crypto.decryptPendingReset(laterEnvelope, laterBinding));
  }

  @Test
  void retainedResponseOnlyIdsNeedNoPendingResetKey() throws Exception {
    KeySet oldResponseOnlyKey = keySet("k1", 1, 2, null);
    KeySet activeKey = keySet("k2", 4, 5, 6);
    writeManifest("k1", oldResponseOnlyKey);
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding responseBinding = responseBinding();
    byte[] exactResponse = "exact response bytes".getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope oldResponse =
        crypto.encrypt(
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, responseBinding, exactResponse);

    writeManifest("k2", oldResponseOnlyKey, activeKey);

    assertArrayEquals(
        exactResponse,
        crypto.decrypt(
            oldResponse, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, responseBinding));
    assertEquals(
        "k2",
        crypto
            .encryptPendingReset(binding(), ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8))
            .keyId());
  }

  @Test
  void pendingBindingAndErrorsDoNotExposeVerifierOrTokenHash() throws Exception {
    writeManifest("k1", keySet("k1", 1, 2, 3));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountPendingResetEnvelopeBinding binding = binding();
    byte[] verifier = ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope envelope = crypto.encryptPendingReset(binding, verifier);

    assertFalse(binding.toString().contains(TOKEN_HASH));
    assertFalse(envelope.toString().contains(ARGON2_VERIFIER));
    assertFalse(envelope.toString().contains(TOKEN_HASH));
    AccountEnvelopeCryptoException exception =
        assertThrows(
            AccountEnvelopeCryptoException.class,
            () ->
                crypto.decryptPendingReset(
                    envelope,
                    binding(
                        ACCOUNT_ID,
                        TOKEN_HASH,
                        TOKEN_EXPIRES_AT,
                        REQUEST_ID,
                        digest(1),
                        digest(2),
                        digest(99))));
    assertFalse(exception.getMessage().contains(ARGON2_VERIFIER));
    assertFalse(exception.getMessage().contains(TOKEN_HASH));
  }

  private void writeManifest(String activeKeyId, KeySet... keySets) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("version=1");
    lines.add("activeKeyId=" + activeKeyId);
    for (KeySet keySet : keySets) {
      lines.add("key:" + keySet.keyId() + ":bare-login=" + encode(keySet.bareLoginKey()));
      lines.add("key:" + keySet.keyId() + ":connect-token=" + encode(keySet.connectTokenKey()));
      if (keySet.pendingResetKey() != null) {
        lines.add("key:" + keySet.keyId() + ":pending-reset=" + encode(keySet.pendingResetKey()));
      }
    }
    Files.writeString(manifestPath, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
  }

  private static KeySet keySet(
      String keyId, int connectValue, int loginValue, Integer pendingValue) {
    return new KeySet(
        keyId, key(connectValue), key(loginValue), pendingValue == null ? null : key(pendingValue));
  }

  private static String encode(byte[] key) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private static byte[] key(int value) {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) value);
    return key;
  }

  private static AccountPendingResetEnvelopeBinding binding() throws Exception {
    return bindingForVerifier(ARGON2_VERIFIER.getBytes(StandardCharsets.UTF_8));
  }

  private static AccountPendingResetEnvelopeBinding bindingForVerifier(byte[] verifier)
      throws Exception {
    return binding(
        ACCOUNT_ID,
        TOKEN_HASH,
        TOKEN_EXPIRES_AT,
        REQUEST_ID,
        digest(1),
        digest(2),
        digestFor(verifier));
  }

  private static AccountPendingResetEnvelopeBinding binding(
      UUID accountId,
      String tokenHash,
      LocalDateTime tokenExpiresAt,
      String requestId,
      byte[] requestDigest,
      byte[] sourceCaptureDigest,
      byte[] targetVerifierDigest) {
    return new AccountPendingResetEnvelopeBinding(
        accountId,
        tokenHash,
        tokenExpiresAt,
        requestId,
        requestDigest,
        sourceCaptureDigest,
        targetVerifierDigest);
  }

  private static AccountEnvelopeBinding responseBinding() {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
        "connect-op-1",
        "request-1",
        ACCOUNT_ID.toString(),
        "tenant-a",
        "connect-scope-a",
        null,
        digest(1),
        digest(2),
        digest(3),
        digest(4),
        digest(5));
  }

  private static byte[] digest(int value) {
    byte[] digest = new byte[32];
    Arrays.fill(digest, (byte) value);
    return digest;
  }

  private static byte[] digestFor(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static void assertFailure(Failure expected, Runnable operation) {
    AccountEnvelopeCryptoException exception =
        assertThrows(AccountEnvelopeCryptoException.class, operation::run);
    assertEquals(expected, exception.failure());
  }

  private static void assertAuthenticationFailure(
      AccountEnvelopeCrypto crypto,
      AccountEncryptedEnvelope envelope,
      AccountPendingResetEnvelopeBinding binding) {
    assertFailure(
        Failure.AUTHENTICATION_FAILED, () -> crypto.decryptPendingReset(envelope, binding));
  }

  private record KeySet(
      String keyId, byte[] connectTokenKey, byte[] bareLoginKey, byte[] pendingResetKey) {}
}
