package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

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
  void authenticatesMaximumAuthorityBundleAndResponseWithoutChangingRawBindingBytes()
      throws IOException {
    writeManifest(activeManifest("key-one", 1));
    AccountResponseEnvelopeCryptography cryptography =
        cryptography(Clock.fixed(NOW, ZoneOffset.UTC));
    byte[] authorityBundle =
        new byte[AccountResponseEnvelopeCryptography.MAX_AUTHORITY_EVIDENCE_BUNDLE_BYTES];
    Arrays.fill(authorityBundle, (byte) 0x5a);
    var binding = bindingWithAuthorityBundle(authorityBundle);
    byte[] response = new byte[AccountResponseEnvelopeCryptography.MAX_RESPONSE_BYTES];
    Arrays.fill(response, (byte) 0x3c);

    var envelope = cryptography.encrypt(binding, response, RECOVERY_EXPIRY);

    assertThat(envelope.bytes().length).isLessThanOrEqualTo(160 * 1024);
    assertThat(cryptography.decrypt(envelope, binding, RECOVERY_EXPIRY)).containsExactly(response);

    byte[] changedAuthorityBundle = authorityBundle.clone();
    changedAuthorityBundle[changedAuthorityBundle.length / 2] ^= 0x01;
    assertThatThrownBy(
            () ->
                cryptography.decrypt(
                    envelope, bindingWithAuthorityBundle(changedAuthorityBundle), RECOVERY_EXPIRY))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
  }

  @Test
  void rejectsAuthorityResponseAndCiphertextBoundsPlusOne() throws IOException {
    writeManifest(activeManifest("key-one", 1));
    AccountResponseEnvelopeCryptography cryptography =
        cryptography(Clock.fixed(NOW, ZoneOffset.UTC));
    var binding = binding();

    assertThatThrownBy(
            () ->
                bindingWithAuthorityBundle(
                    new byte
                        [AccountResponseEnvelopeCryptography.MAX_AUTHORITY_EVIDENCE_BUNDLE_BYTES
                            + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                cryptography.encrypt(
                    binding,
                    new byte[AccountResponseEnvelopeCryptography.MAX_RESPONSE_BYTES + 1],
                    RECOVERY_EXPIRY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                cryptography.decrypt(
                    new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                        new byte[AccountResponseEnvelopeCryptography.MAX_ENVELOPE_BYTES + 1]),
                    binding,
                    RECOVERY_EXPIRY))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
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

  private static AccountResponseEnvelopeCryptography.Binding bindingWithAuthorityBundle(
      byte[] authorityEvidenceBundle) {
    var source = binding();
    return new AccountResponseEnvelopeCryptography.Binding(
        source.operation(),
        source.requestId(),
        source.requestDigest(),
        source.callerWorkload(),
        source.bindingId(),
        source.lineageId(),
        source.replacementId(),
        source.leaseId(),
        source.issuanceFence(),
        source.authorityTupleCanonicalBytes(),
        source.membershipVersionMapCanonicalBytes(),
        authorityEvidenceBundle);
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

  @Nested
  class DonorCryptographyRegressions {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final byte[] RESPONSE_BYTES = {0, 1, (byte) 0xff, 0, 42};

    @TempDir Path tempDirectory;

    @Test
    void springConstructsCryptoBeansWhenDedicatedKeyringIsDisabled() {
      try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "test", Map.of("firemud.account.response-envelope.keyring-path", "")));
        context.register(
            AccountResponseEnvelopeKeyring.class, AccountResponseEnvelopeCryptography.class);
        context.refresh();

        assertNotNull(context.getBean(AccountResponseEnvelopeKeyring.class));
        assertNotNull(context.getBean(AccountResponseEnvelopeCryptography.class));
      }
    }

    @Test
    void roundTripsByteExactResponseUnderActiveKeyAndCompleteTypedBinding() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active active-1 " + encodedKey(1));
      AccountResponseEnvelopeCryptography crypto = crypto(mount, NOW);
      Instant expiry = NOW.plusSeconds(60);

      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope encrypted =
          crypto.encrypt(binding("request-1"), RESPONSE_BYTES, expiry);

      assertArrayEquals(
          RESPONSE_BYTES, crypto(mount, NOW).decrypt(encrypted, binding("request-1"), expiry));
      assertTrue(encrypted.toString().contains("redacted"));
      byte[] encoded = encrypted.bytes();
      int keyIdLength = Byte.toUnsignedInt(encoded[5]);
      int nonceOffset = 6 + keyIdLength + Long.BYTES;
      assertTrue(nonceOffset + AccountResponseEnvelopeCryptography.NONCE_BYTES < encoded.length);
    }

    @Test
    void rotationEncryptsWithNewActiveKeyAndRetainsOldKeyOnlyForItsDeclaredHorizon()
        throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active prior-1 " + encodedKey(1));
      Instant expiry = NOW.plusSeconds(180);
      Instant retiringUntil = NOW.plusSeconds(120);
      AccountResponseEnvelopeCryptography oldCrypto = crypto(mount, NOW);
      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope prior =
          oldCrypto.encrypt(binding("request-1"), RESPONSE_BYTES, expiry);

      writeManifest(
          mount,
          "active active-2 " + encodedKey(2),
          "retiring prior-1 " + retiringUntil.toEpochMilli() + " " + encodedKey(1));
      AccountResponseEnvelopeCryptography rotatedCrypto = crypto(mount, NOW.plusSeconds(30));
      assertArrayEquals(RESPONSE_BYTES, rotatedCrypto.decrypt(prior, binding("request-1"), expiry));

      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope fresh =
          rotatedCrypto.encrypt(binding("request-2"), RESPONSE_BYTES, expiry);
      assertEquals("active-2", envelopeKeyId(fresh.bytes()));

      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> crypto(mount, retiringUntil).decrypt(prior, binding("request-1"), expiry));
    }

    @Test
    void missingRetainedKeyIsRetryableUnavailableBeforeTheOwnerRecordedExpiry() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active active-1 " + encodedKey(1));
      Instant expiry = NOW.plusSeconds(60);
      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope encrypted =
          crypto(mount, NOW).encrypt(binding("request-1"), RESPONSE_BYTES, expiry);
      Files.delete(mount.resolve("keyring"));

      AccountResponseEnvelopeKeyring.KeyUnavailableException failure =
          assertThrows(
              AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
              () ->
                  crypto(mount, NOW.plusSeconds(10))
                      .decrypt(encrypted, binding("request-1"), expiry));
      assertFalse(failure.getMessage().contains(mount.toString()));
      assertEquals("AUTH_UNAVAILABLE", failure.errorCode());
    }

    @Test
    void expiredOwnerRecordedHorizonIsTerminalEvenWhenKeyringIsUnavailable() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active active-1 " + encodedKey(1));
      Instant expiry = NOW.plusMillis(10);
      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope encrypted =
          crypto(mount, NOW).encrypt(binding("request-1"), RESPONSE_BYTES, expiry);
      Files.delete(mount.resolve("keyring"));

      AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException expired =
          assertThrows(
              AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException.class,
              () -> crypto(mount, expiry).decrypt(encrypted, binding("request-1"), expiry));
      assertEquals("RESPONSE_RECOVERY_EXPIRED", expired.errorCode());
    }

    @Test
    void changedOperationBindingExpiryOrCiphertextFailsIntegrity() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active active-1 " + encodedKey(1));
      AccountResponseEnvelopeCryptography crypto = crypto(mount, NOW);
      Instant expiry = NOW.plusSeconds(60);
      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope encrypted =
          crypto.encrypt(binding("request-1"), RESPONSE_BYTES, expiry);

      for (BindingVariant variant : BindingVariant.values()) {
        assertThrows(
            AccountResponseEnvelopeCryptography.IntegrityException.class,
            () -> crypto.decrypt(encrypted, variant.binding(), expiry));
      }
      assertThrows(
          AccountResponseEnvelopeCryptography.IntegrityException.class,
          () -> crypto.decrypt(encrypted, binding("request-1"), expiry.plusMillis(1)));

      byte[] tampered = encrypted.bytes();
      tampered[tampered.length - 1] ^= 1;
      assertThrows(
          AccountResponseEnvelopeCryptography.IntegrityException.class,
          () ->
              crypto.decrypt(
                  new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(tampered),
                  binding("request-1"),
                  expiry));

      byte[] nonceTampered = encrypted.bytes();
      int nonceOffset = 6 + Byte.toUnsignedInt(nonceTampered[5]) + Long.BYTES;
      nonceTampered[nonceOffset] ^= 1;
      assertThrows(
          AccountResponseEnvelopeCryptography.IntegrityException.class,
          () ->
              crypto.decrypt(
                  new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(nonceTampered),
                  binding("request-1"),
                  expiry));

      assertThrows(
          AccountResponseEnvelopeCryptography.IntegrityException.class,
          () ->
              crypto.decrypt(
                  new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                      Arrays.copyOf(encrypted.bytes(), encrypted.bytes().length - 1)),
                  binding("request-1"),
                  expiry));
    }

    @Test
    void wrongMaterialForAKeyIdFailsAuthentication() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active same-id " + encodedKey(1));
      Instant expiry = NOW.plusSeconds(60);
      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope encrypted =
          crypto(mount, NOW).encrypt(binding("request-1"), RESPONSE_BYTES, expiry);
      writeManifest(mount, "active same-id " + encodedKey(2));

      assertThrows(
          AccountResponseEnvelopeCryptography.IntegrityException.class,
          () -> crypto(mount, NOW.plusSeconds(1)).decrypt(encrypted, binding("request-1"), expiry));
    }

    @Test
    void enforcesResponseAndBindingBoundsBeforeCryptography() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active active-1 " + encodedKey(1));
      AccountResponseEnvelopeCryptography crypto = crypto(mount, NOW);
      byte[] oversizedResponse =
          new byte[AccountResponseEnvelopeCryptography.MAX_RESPONSE_BYTES + 1];

      assertThrows(
          IllegalArgumentException.class,
          () -> crypto.encrypt(binding("request-1"), oversizedResponse, NOW.plusSeconds(60)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new AccountResponseEnvelopeCryptography.Binding(
                  "issue",
                  "request-1",
                  new byte[32],
                  "spiffe://firemud/ns/test/sa/game-session-service",
                  "binding-1",
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  1,
                  new byte[8 * 1024 + 1],
                  new byte[] {'{', '}'},
                  new byte[] {2}));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new AccountResponseEnvelopeCryptography.Binding(
                  "issue",
                  "request-1",
                  new byte[32],
                  "spiffe://firemud/ns/test/sa/game-session-service",
                  "binding-1",
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  1,
                  new byte[] {1},
                  new byte[0],
                  new byte[] {2}));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new AccountResponseEnvelopeCryptography.Binding(
                  "issue",
                  "request-1",
                  new byte[32],
                  "spiffe://firemud/ns/test/sa/game-session-service",
                  "binding-1",
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  1,
                  new byte[] {1},
                  new byte[] {'{', '}'},
                  new byte[128 * 1024 + 1]));
      AccountResponseEnvelopeCryptography.Binding maximumComponentBinding =
          new AccountResponseEnvelopeCryptography.Binding(
              "issue",
              "request-1",
              new byte[32],
              "spiffe://firemud/ns/test/sa/game-session-service",
              "binding-1",
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              1,
              new byte[8 * 1024],
              new byte[4 * 1024],
              new byte[128 * 1024]);
      AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope maximumBindingEnvelope =
          crypto.encrypt(maximumComponentBinding, RESPONSE_BYTES, NOW.plusSeconds(60));
      assertArrayEquals(
          RESPONSE_BYTES,
          crypto.decrypt(maximumBindingEnvelope, maximumComponentBinding, NOW.plusSeconds(60)));
      assertThrows(
          AccountResponseEnvelopeCryptography.IntegrityException.class,
          () ->
              crypto.decrypt(
                  new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                      new byte[AccountResponseEnvelopeCryptography.MAX_ENVELOPE_BYTES + 1]),
                  binding("request-1"),
                  NOW.plusSeconds(60)));
    }

    private AccountResponseEnvelopeCryptography crypto(Path mount, Instant now) {
      return new AccountResponseEnvelopeCryptography(
          new AccountResponseEnvelopeKeyring(mount.toString()),
          Clock.fixed(now, ZoneOffset.UTC),
          new SecureRandom());
    }

    private AccountResponseEnvelopeCryptography.Binding binding(String requestId) {
      return new AccountResponseEnvelopeCryptography.Binding(
          "issue",
          requestId,
          new byte[32],
          "spiffe://firemud/ns/test/sa/game-session-service",
          "binding-1",
          Optional.of("lineage-1"),
          Optional.empty(),
          Optional.of("lease-1"),
          17,
          new byte[] {1, 2, 3},
          new byte[] {'{', '}'},
          new byte[] {4, 5, 6});
    }

    private enum BindingVariant {
      OPERATION,
      REQUEST_ID,
      REQUEST_DIGEST,
      CALLER,
      BINDING,
      LINEAGE,
      REPLACEMENT,
      LEASE,
      ISSUANCE_FENCE,
      AUTHORITY_TUPLE,
      MEMBERSHIP_VERSION_MAP,
      AUTHORITY_EVIDENCE_BUNDLE;

      private AccountResponseEnvelopeCryptography.Binding binding() {
        String operation = this == OPERATION ? "refresh" : "issue";
        String requestId = this == REQUEST_ID ? "request-2" : "request-1";
        byte[] requestDigest = new byte[32];
        if (this == REQUEST_DIGEST) {
          requestDigest[0] = 1;
        }
        String caller =
            this == CALLER
                ? "spiffe://firemud/ns/other/sa/game-session-service"
                : "spiffe://firemud/ns/test/sa/game-session-service";
        String bindingId = this == BINDING ? "binding-2" : "binding-1";
        Optional<String> lineage = Optional.of(this == LINEAGE ? "lineage-2" : "lineage-1");
        Optional<String> replacement =
            this == REPLACEMENT ? Optional.of("replacement-1") : Optional.empty();
        Optional<String> lease = Optional.of(this == LEASE ? "lease-2" : "lease-1");
        long fence = this == ISSUANCE_FENCE ? 18 : 17;
        byte[] authorityTuple =
            this == AUTHORITY_TUPLE ? new byte[] {1, 2, 4} : new byte[] {1, 2, 3};
        byte[] membershipVersionMap =
            this == MEMBERSHIP_VERSION_MAP
                ? new byte[] {'{', '"', 't', '"', ':', '1', '}'}
                : new byte[] {'{', '}'};
        byte[] authorityEvidence =
            this == AUTHORITY_EVIDENCE_BUNDLE ? new byte[] {4, 5, 7} : new byte[] {4, 5, 6};
        return new AccountResponseEnvelopeCryptography.Binding(
            operation,
            requestId,
            requestDigest,
            caller,
            bindingId,
            lineage,
            replacement,
            lease,
            fence,
            authorityTuple,
            membershipVersionMap,
            authorityEvidence);
      }
    }

    private static String envelopeKeyId(byte[] encoded) {
      int keyIdLength = Byte.toUnsignedInt(encoded[5]);
      return new String(encoded, 6, keyIdLength, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void writeManifest(Path mount, String... entries) throws Exception {
      Files.createDirectories(mount);
      StringBuilder content = new StringBuilder("firemud-account-response-envelope-keyring-v1\n");
      for (String entry : entries) {
        content.append(entry).append('\n');
      }
      Files.writeString(mount.resolve("keyring"), content.toString());
    }

    private static String encodedKey(int fill) {
      byte[] bytes = new byte[AccountResponseEnvelopeKeyring.KEY_BYTES];
      Arrays.fill(bytes, (byte) fill);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
  }
}
