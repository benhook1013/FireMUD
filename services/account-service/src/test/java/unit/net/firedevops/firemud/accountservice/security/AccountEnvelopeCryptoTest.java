package unit.net.firedevops.firemud.accountservice.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCryptoException.Failure;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountEnvelopeCryptoTest {
  @TempDir private Path temporaryDirectory;

  private Path manifestPath;

  @BeforeEach
  void setUp() {
    manifestPath = temporaryDirectory.resolve("manifest.v1");
  }

  @Test
  void exactEnvelopeBytesDecryptAfterCryptoObjectRecreationForBothPurposes() throws Exception {
    KeyPair firstKey = new KeyPair("k1", key(1), key(2));
    writeManifest("k1", firstKey);
    AccountEnvelopeCrypto writer = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding binding = connectBinding("connect-op-1", "request-1");
    byte[] exactResult = "the exact previously issued result".getBytes(StandardCharsets.UTF_8);
    AccountEnvelopeBinding loginBinding = loginBinding("login-op-1", "login-request-1");
    byte[] exactLoginResult = "the exact bare LOGIN result".getBytes(StandardCharsets.UTF_8);

    AccountEncryptedEnvelope envelope =
        writer.encrypt(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding, exactResult);
    AccountEncryptedEnvelope loginEnvelope =
        writer.encrypt(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, loginBinding, exactLoginResult);
    AccountEnvelopeCrypto recoveryReader = new AccountEnvelopeCrypto(manifestPath);

    assertEquals("k1", envelope.keyId());
    assertEquals(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, envelope.purpose());
    assertArrayEquals(
        exactResult,
        recoveryReader.decrypt(envelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding));
    assertEquals("k1", loginEnvelope.keyId());
    assertEquals(AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, loginEnvelope.purpose());
    assertArrayEquals(
        exactLoginResult,
        recoveryReader.decrypt(
            loginEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, loginBinding));
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () ->
            recoveryReader.decrypt(
                envelope,
                AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                connectBinding("connect-op-other", "request-1")));
  }

  @Test
  void rotationWritesWithNewIdRetainsOldDecryptKeyAndFailsAfterOldKeyRemoval() throws Exception {
    KeyPair firstKey = new KeyPair("k1", key(1), key(2));
    KeyPair secondKey = new KeyPair("k2", key(3), key(4));
    writeManifest("k1", firstKey);
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding firstBinding = connectBinding("connect-op-1", "request-1");
    AccountEnvelopeBinding secondBinding = connectBinding("connect-op-2", "request-2");
    AccountEnvelopeBinding firstLoginBinding = loginBinding("login-op-1", "login-request-1");
    AccountEnvelopeBinding secondLoginBinding = loginBinding("login-op-2", "login-request-2");
    byte[] firstResult = "first issuance result".getBytes(StandardCharsets.UTF_8);
    byte[] secondResult = "second issuance result".getBytes(StandardCharsets.UTF_8);
    byte[] firstLoginResult = "first bare LOGIN result".getBytes(StandardCharsets.UTF_8);
    byte[] secondLoginResult = "second bare LOGIN result".getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope firstEnvelope =
        crypto.encrypt(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, firstBinding, firstResult);
    AccountEncryptedEnvelope firstLoginEnvelope =
        crypto.encrypt(
            AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, firstLoginBinding, firstLoginResult);

    writeManifest("k2", firstKey, secondKey);
    AccountEncryptedEnvelope secondEnvelope =
        crypto.encrypt(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, secondBinding, secondResult);
    AccountEncryptedEnvelope secondLoginEnvelope =
        crypto.encrypt(
            AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, secondLoginBinding, secondLoginResult);

    assertEquals("k2", secondEnvelope.keyId());
    assertEquals("k2", secondLoginEnvelope.keyId());
    assertArrayEquals(
        firstResult,
        crypto.decrypt(firstEnvelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, firstBinding));
    assertArrayEquals(
        secondResult,
        crypto.decrypt(
            secondEnvelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, secondBinding));
    assertArrayEquals(
        firstLoginResult,
        crypto.decrypt(
            firstLoginEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, firstLoginBinding));
    assertArrayEquals(
        secondLoginResult,
        crypto.decrypt(
            secondLoginEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, secondLoginBinding));

    writeManifest("k2", secondKey);
    assertFailure(
        Failure.KEY_UNAVAILABLE,
        () ->
            crypto.decrypt(
                firstEnvelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, firstBinding));
    assertFailure(
        Failure.KEY_UNAVAILABLE,
        () ->
            crypto.decrypt(
                firstLoginEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, firstLoginBinding));
    assertArrayEquals(
        secondResult,
        crypto.decrypt(
            secondEnvelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, secondBinding));
    assertArrayEquals(
        secondLoginResult,
        crypto.decrypt(
            secondLoginEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, secondLoginBinding));
  }

  @Test
  void wrongPurposeAndOperationCannotAuthenticateWithOverlappingKeyIds() throws Exception {
    writeManifest("k1", new KeyPair("k1", key(1), key(2)));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding connectBinding = connectBinding("connect-op-1", "request-1");
    AccountEncryptedEnvelope connectEnvelope =
        crypto.encrypt(
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            connectBinding,
            "token result".getBytes(StandardCharsets.UTF_8));
    AccountEnvelopeBinding loginBinding = loginBinding("login-op-1", "request-1");

    assertFailure(
        Failure.PURPOSE_MISMATCH,
        () ->
            crypto.decrypt(
                connectEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, loginBinding));

    AccountEncryptedEnvelope relabeledEnvelope =
        new AccountEncryptedEnvelope(
            connectEnvelope.formatVersion(),
            connectEnvelope.keyId(),
            AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
            connectEnvelope.nonce(),
            connectEnvelope.ciphertext());
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () ->
            crypto.decrypt(
                relabeledEnvelope, AccountEnvelopePurpose.BARE_LOGIN_RESPONSE, loginBinding));
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () ->
            crypto.decrypt(
                connectEnvelope,
                AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                connectBinding("connect-op-1", "request-other")));

    AccountEncryptedEnvelope loginEnvelope =
        crypto.encrypt(
            AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
            loginBinding,
            "delegation result".getBytes(StandardCharsets.UTF_8));
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () ->
            crypto.decrypt(
                loginEnvelope,
                AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
                loginBinding("login-op-1", "request-1", "connect-op-other")));
  }

  @Test
  void nonceCiphertextAndEveryAuthorityBindingChangeFailAuthentication() throws Exception {
    writeManifest("k1", new KeyPair("k1", key(1), key(2)));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding binding = connectBinding("connect-op-1", "request-1");
    AccountEncryptedEnvelope envelope =
        crypto.encrypt(
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            binding,
            "exact credential response".getBytes(StandardCharsets.UTF_8));

    byte[] changedNonce = envelope.nonce();
    changedNonce[0] ^= 1;
    AccountEncryptedEnvelope nonceTampered =
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose(),
            changedNonce,
            envelope.ciphertext());
    assertAuthenticationFailure(crypto, nonceTampered, binding);

    byte[] changedCiphertext = envelope.ciphertext();
    changedCiphertext[0] ^= 1;
    AccountEncryptedEnvelope ciphertextTampered =
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose(),
            envelope.nonce(),
            changedCiphertext);
    assertAuthenticationFailure(crypto, ciphertextTampered, binding);

    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-other",
            "tenant-a",
            "connect-scope-a",
            digest(1),
            digest(2),
            digest(3),
            digest(4),
            digest(5)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-17",
            "tenant-a",
            "connect-scope-other",
            digest(1),
            digest(2),
            digest(3),
            digest(4),
            digest(5)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-17",
            "tenant-a",
            "connect-scope-a",
            digest(11),
            digest(2),
            digest(3),
            digest(4),
            digest(5)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-17",
            "tenant-a",
            "connect-scope-a",
            digest(1),
            digest(12),
            digest(3),
            digest(4),
            digest(5)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-17",
            "tenant-a",
            "connect-scope-a",
            digest(1),
            digest(2),
            digest(13),
            digest(4),
            digest(5)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-17",
            "tenant-a",
            "connect-scope-a",
            digest(1),
            digest(2),
            digest(3),
            digest(14),
            digest(5)));
    assertAuthenticationFailure(
        crypto,
        envelope,
        connectBinding(
            "connect-op-1",
            "request-1",
            "account-17",
            "tenant-a",
            "connect-scope-a",
            digest(1),
            digest(2),
            digest(3),
            digest(4),
            digest(15)));
  }

  @Test
  void missingMalformedAndCrossPurposeReusedKeysFailClosed() throws Exception {
    AccountEnvelopeCrypto missingRing = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding binding = connectBinding("connect-op-1", "request-1");
    assertFailure(
        Failure.KEY_RING_UNAVAILABLE,
        () ->
            missingRing.encrypt(
                AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                binding,
                "credential".getBytes(StandardCharsets.UTF_8)));

    Files.writeString(manifestPath, "version=2\nactiveKeyId=k1\n", StandardCharsets.US_ASCII);
    assertFailure(
        Failure.KEY_RING_MALFORMED,
        () ->
            missingRing.encrypt(
                AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                binding,
                "credential".getBytes(StandardCharsets.UTF_8)));

    byte[] reusedKey = key(9);
    writeManifest("k1", new KeyPair("k1", reusedKey, reusedKey));
    assertFailure(
        Failure.KEY_RING_MALFORMED,
        () ->
            missingRing.encrypt(
                AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
                binding,
                "credential".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void envelopeAndBindingByteArraysAreDefensivelyCopied() throws Exception {
    writeManifest("k1", new KeyPair("k1", key(1), key(2)));
    AccountEnvelopeCrypto crypto = new AccountEnvelopeCrypto(manifestPath);
    AccountEnvelopeBinding binding = connectBinding("connect-op-1", "request-1");
    byte[] plaintext = "sensitive response".getBytes(StandardCharsets.UTF_8);
    AccountEncryptedEnvelope envelope =
        crypto.encrypt(AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding, plaintext);
    byte[] exposedNonce = envelope.nonce();
    byte[] exposedDigest = binding.requestDigest();
    Arrays.fill(exposedNonce, (byte) 0);
    Arrays.fill(exposedDigest, (byte) 0);

    AccountEncryptedEnvelope envelopeCopy =
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose(),
            envelope.nonce(),
            envelope.ciphertext());
    AccountEnvelopeBinding bindingCopy = connectBinding("connect-op-1", "request-1");
    byte[] changedNonce = envelope.nonce();
    changedNonce[0] ^= 1;

    assertEquals(envelope, envelopeCopy);
    assertEquals(envelope.hashCode(), envelopeCopy.hashCode());
    assertEquals(binding, bindingCopy);
    assertEquals(binding.hashCode(), bindingCopy.hashCode());
    assertNotEquals(
        envelope,
        new AccountEncryptedEnvelope(
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.purpose(),
            changedNonce,
            envelope.ciphertext()));

    assertArrayEquals(
        plaintext,
        crypto.decrypt(envelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding));
  }

  private void writeManifest(String activeKeyId, KeyPair... keyPairs) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("version=1");
    lines.add("activeKeyId=" + activeKeyId);
    for (KeyPair keyPair : keyPairs) {
      lines.add("key:" + keyPair.keyId() + ":bare-login=" + encode(keyPair.bareLoginKey()));
      lines.add("key:" + keyPair.keyId() + ":connect-token=" + encode(keyPair.connectTokenKey()));
    }
    Files.writeString(manifestPath, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
  }

  private static String encode(byte[] key) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private static byte[] key(int value) {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) value);
    return key;
  }

  private static AccountEnvelopeBinding connectBinding(String operationId, String requestId) {
    return connectBinding(
        operationId,
        requestId,
        "account-17",
        "tenant-a",
        "connect-scope-a",
        digest(1),
        digest(2),
        digest(3),
        digest(4),
        digest(5));
  }

  private static AccountEnvelopeBinding connectBinding(
      String operationId,
      String requestId,
      String accountId,
      String tenantId,
      String connectScopeId,
      byte[] requestDigest,
      byte[] contextEvidenceDigest,
      byte[] authorityTupleDigest,
      byte[] issuanceFenceDigest,
      byte[] postconditionDigest) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE,
        operationId,
        requestId,
        accountId,
        tenantId,
        connectScopeId,
        null,
        requestDigest,
        contextEvidenceDigest,
        authorityTupleDigest,
        issuanceFenceDigest,
        postconditionDigest);
  }

  private static AccountEnvelopeBinding loginBinding(String operationId, String requestId) {
    return loginBinding(operationId, requestId, "connect-op-1");
  }

  private static AccountEnvelopeBinding loginBinding(
      String operationId, String requestId, String sourceConnectOperationId) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
        operationId,
        requestId,
        "account-17",
        "tenant-a",
        "connect-scope-a",
        sourceConnectOperationId,
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

  private static void assertFailure(Failure expected, Runnable operation) {
    AccountEnvelopeCryptoException exception =
        assertThrows(AccountEnvelopeCryptoException.class, operation::run);
    assertEquals(expected, exception.failure());
  }

  private static void assertAuthenticationFailure(
      AccountEnvelopeCrypto crypto,
      AccountEncryptedEnvelope envelope,
      AccountEnvelopeBinding binding) {
    assertFailure(
        Failure.AUTHENTICATION_FAILED,
        () -> crypto.decrypt(envelope, AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE, binding));
  }

  private record KeyPair(String keyId, byte[] connectTokenKey, byte[] bareLoginKey) {}
}
