package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountResponseEnvelopeCryptographyTest {
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final Instant RECOVERY_EXPIRY = NOW.plusSeconds(600);
  private static final String KEYRING_HEADER = "firemud-account-response-envelope-keyring-v1";

  @TempDir Path temporaryDirectory;

  @Test
  void encryptsAndDecryptsExactResponseBytes() throws IOException {
    writeManifest(activeManifest("key-one", 1));
    AccountResponseEnvelopeCryptography cryptography =
        cryptography(Clock.fixed(NOW, ZoneOffset.UTC));
    byte[] response = "test-only exact response".getBytes(StandardCharsets.UTF_8);

    AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope envelope =
        cryptography.encrypt(binding(), response, RECOVERY_EXPIRY);

    assertThat(envelope.bytes()).isNotEqualTo(response);
    assertThat(cryptography.decrypt(envelope, binding(), RECOVERY_EXPIRY)).isEqualTo(response);
  }

  @Test
  void rejectsTamperingEveryChangedBindingFieldAndChangedExpiry() throws IOException {
    writeManifest(activeManifest("key-one", 1));
    AccountResponseEnvelopeCryptography cryptography =
        cryptography(Clock.fixed(NOW, ZoneOffset.UTC));
    var binding = binding();
    var envelope =
        cryptography.encrypt(
            binding, "test-only exact response".getBytes(StandardCharsets.UTF_8), RECOVERY_EXPIRY);

    byte[] tamperedBytes = envelope.bytes();
    tamperedBytes[tamperedBytes.length - 1] ^= 0x01;
    var tamperedEnvelope =
        new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(tamperedBytes);
    assertThatThrownBy(() -> cryptography.decrypt(tamperedEnvelope, binding, RECOVERY_EXPIRY))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);

    for (int field = 0; field < 12; field++) {
      var changedBinding = changedBinding(binding, field);
      assertThatThrownBy(() -> cryptography.decrypt(envelope, changedBinding, RECOVERY_EXPIRY))
          .as("binding field %s must be authenticated", field)
          .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
    }

    assertThatThrownBy(() -> cryptography.decrypt(envelope, binding, RECOVERY_EXPIRY.plusMillis(1)))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
  }

  @Test
  void onlyDecryptsWithRetainedKeysAndRejectsWithdrawnKeys() throws IOException {
    writeManifest(activeManifest("key-one", 1));
    AccountResponseEnvelopeCryptography cryptography =
        cryptography(Clock.fixed(NOW, ZoneOffset.UTC));
    var binding = binding();
    var envelope =
        cryptography.encrypt(
            binding, "test-only exact response".getBytes(StandardCharsets.UTF_8), RECOVERY_EXPIRY);

    long decryptUntil = RECOVERY_EXPIRY.plusSeconds(60).toEpochMilli();
    writeManifest(
        KEYRING_HEADER
            + "\nactive key-two "
            + encodedKey(2)
            + "\nretiring key-one "
            + decryptUntil
            + " "
            + encodedKey(1)
            + "\n");

    assertThat(cryptography.decrypt(envelope, binding, RECOVERY_EXPIRY))
        .isEqualTo("test-only exact response".getBytes(StandardCharsets.UTF_8));

    writeManifest(activeManifest("key-two", 2));
    assertThatThrownBy(() -> cryptography.decrypt(envelope, binding, RECOVERY_EXPIRY))
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
  }

  @Test
  void rejectsDecryptionAtTheImmutableRecoveryExpiry() throws IOException {
    writeManifest(activeManifest("key-one", 1));
    var envelope =
        cryptography(Clock.fixed(NOW, ZoneOffset.UTC))
            .encrypt(
                binding(),
                "test-only exact response".getBytes(StandardCharsets.UTF_8),
                RECOVERY_EXPIRY);
    var expiredCryptography = cryptography(Clock.fixed(RECOVERY_EXPIRY, ZoneOffset.UTC));

    assertThatThrownBy(() -> expiredCryptography.decrypt(envelope, binding(), RECOVERY_EXPIRY))
        .isInstanceOf(AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException)
                                failure)
                            .errorCode())
                    .isEqualTo("RESPONSE_RECOVERY_EXPIRED"));
  }

  private AccountResponseEnvelopeCryptography cryptography(Clock clock) {
    return new AccountResponseEnvelopeCryptography(
        new AccountResponseEnvelopeKeyring(temporaryDirectory.toString()),
        clock,
        new SecureRandom());
  }

  private void writeManifest(String contents) throws IOException {
    Files.writeString(temporaryDirectory.resolve("keyring"), contents, StandardCharsets.US_ASCII);
  }

  private static String activeManifest(String keyId, int keyValue) {
    return KEYRING_HEADER + "\nactive " + keyId + " " + encodedKey(keyValue) + "\n";
  }

  private static String encodedKey(int value) {
    byte[] key = new byte[AccountResponseEnvelopeKeyring.KEY_BYTES];
    Arrays.fill(key, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private static AccountResponseEnvelopeCryptography.Binding binding() {
    return new AccountResponseEnvelopeCryptography.Binding(
        "test-envelope",
        "request-01",
        filledBytes((byte) 1),
        "spiffe://firemud/ns/test/sa/logging-admin-service",
        "binding-01",
        Optional.of("lineage-01"),
        Optional.of("replacement-01"),
        Optional.of("lease-01"),
        7,
        bytes("{\"authority\":\"tuple-01\"}"),
        bytes("{\"tenant-01\":\"membership-01\"}"),
        bytes("{\"bundleVersion\":\"authorityEvidenceBundle/v1\"}"));
  }

  private static AccountResponseEnvelopeCryptography.Binding changedBinding(
      AccountResponseEnvelopeCryptography.Binding source, int field) {
    String operation = source.operation();
    String requestId = source.requestId();
    byte[] requestDigest = source.requestDigest();
    String callerWorkload = source.callerWorkload();
    String bindingId = source.bindingId();
    Optional<String> lineageId = source.lineageId();
    Optional<String> replacementId = source.replacementId();
    Optional<String> leaseId = source.leaseId();
    long issuanceFence = source.issuanceFence();
    byte[] authorityTuple = source.authorityTupleCanonicalBytes();
    byte[] membershipVersionMap = source.membershipVersionMapCanonicalBytes();
    byte[] authorityEvidenceBundle = source.authorityEvidenceBundleCanonicalBytes();

    switch (field) {
      case 0 -> operation = "other-operation";
      case 1 -> requestId = "request-02";
      case 2 -> requestDigest = filledBytes((byte) 2);
      case 3 -> callerWorkload = "spiffe://firemud/ns/other/sa/logging-admin-service";
      case 4 -> bindingId = "binding-02";
      case 5 -> lineageId = Optional.empty();
      case 6 -> replacementId = Optional.empty();
      case 7 -> leaseId = Optional.empty();
      case 8 -> issuanceFence = 8;
      case 9 -> authorityTuple = bytes("{\"authority\":\"tuple-02\"}");
      case 10 -> membershipVersionMap = bytes("{\"tenant-01\":\"membership-02\"}");
      case 11 -> authorityEvidenceBundle = bytes("{\"bundleVersion\":\"changed\"}");
      default -> throw new IllegalArgumentException("Unknown response-envelope binding field");
    }

    return new AccountResponseEnvelopeCryptography.Binding(
        operation,
        requestId,
        requestDigest,
        callerWorkload,
        bindingId,
        lineageId,
        replacementId,
        leaseId,
        issuanceFence,
        authorityTuple,
        membershipVersionMap,
        authorityEvidenceBundle);
  }

  private static byte[] filledBytes(byte value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, value);
    return bytes;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
