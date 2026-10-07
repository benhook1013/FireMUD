package net.firedevops.firemud.accountservice.service.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class AccountResponseEnvelopeCryptographyTest {
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
    byte[] oversizedResponse = new byte[AccountResponseEnvelopeCryptography.MAX_RESPONSE_BYTES + 1];

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
    AccountResponseEnvelopeCryptography.Binding bindingOverAggregateLimit =
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
            new byte[8 * 1024]);
    assertThrows(
        IllegalArgumentException.class,
        () -> crypto.encrypt(bindingOverAggregateLimit, RESPONSE_BYTES, NOW.plusSeconds(60)));
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
      byte[] authorityTuple = this == AUTHORITY_TUPLE ? new byte[] {1, 2, 4} : new byte[] {1, 2, 3};
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
