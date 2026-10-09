package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.KeyUnavailableException;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationFingerprintKeyring.Snapshot;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationReferenceFingerprint.ReferenceKind;
import org.junit.jupiter.api.Test;

class AccountOperatorAuthorizationFingerprintKeyringTest {
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration RETENTION_WINDOW = Duration.ofMinutes(3);
  private static final byte[] REFERENCE =
      "opaque-reference".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  private static final Instant REFERENCE_EXPIRY = NOW.plusSeconds(60);
  private static final Instant RESPONSE_EXPIRY = REFERENCE_EXPIRY.plusSeconds(30);

  @Test
  void reloadsOwnerSnapshotForEachOperationAndUsesOnlyItsActiveKeyForIssue() {
    AtomicInteger reads = new AtomicInteger();
    AccountOperatorAuthorizationFingerprintKeyring keyring =
        new AccountOperatorAuthorizationFingerprintKeyring(
            () -> {
              reads.incrementAndGet();
              return snapshot("active", "active-secret", List.of());
            },
            CLOCK,
            RETENTION_WINDOW);
    String expected =
        calculator("active", "active-secret").fingerprint(ReferenceKind.HUMAN_OPERATOR, REFERENCE);

    assertThat(keyring.fingerprintForIssue(ReferenceKind.HUMAN_OPERATOR, REFERENCE))
        .isEqualTo(expected);
    assertThat(
            keyring.matchesOriginal(
                ReferenceKind.HUMAN_OPERATOR,
                REFERENCE,
                expected,
                REFERENCE_EXPIRY,
                RESPONSE_EXPIRY))
        .isTrue();
    assertThat(reads).hasValue(2);
  }

  @Test
  void retainedOriginalKeyMustCoverTheImmutableExpiryAndExplicitRecoveryWindow() {
    Instant retainedUntil = RESPONSE_EXPIRY.plus(RETENTION_WINDOW).plusNanos(1);
    AccountOperatorAuthorizationFingerprintKeyring keyring =
        keyring(
            snapshot(
                "new-active",
                "active-secret",
                List.of(new KeyMaterial("original", key("original-secret"), retainedUntil))));
    String original =
        calculator("original", "original-secret")
            .fingerprint(ReferenceKind.HUMAN_OPERATOR, REFERENCE);

    assertThat(
            keyring.matchesOriginal(
                ReferenceKind.HUMAN_OPERATOR,
                REFERENCE,
                original,
                REFERENCE_EXPIRY,
                RESPONSE_EXPIRY))
        .isTrue();
  }

  @Test
  void missingWithdrawnExpiredOrInsufficientlyRetainedOriginalKeyFailsClosed() {
    String original =
        calculator("original", "original-secret")
            .fingerprint(ReferenceKind.HUMAN_OPERATOR, REFERENCE);
    List<Snapshot> unavailableSnapshots =
        List.of(
            snapshot("new-active", "active-secret", List.of()),
            snapshot(
                "new-active",
                "active-secret",
                List.of(new KeyMaterial("original", key("original-secret"), NOW))),
            snapshot(
                "new-active",
                "active-secret",
                List.of(
                    new KeyMaterial(
                        "original",
                        key("original-secret"),
                        RESPONSE_EXPIRY.plus(RETENTION_WINDOW)))));

    for (Snapshot snapshot : unavailableSnapshots) {
      AccountOperatorAuthorizationFingerprintKeyring keyring = keyring(snapshot);
      assertThatThrownBy(
              () ->
                  keyring.matchesOriginal(
                      ReferenceKind.HUMAN_OPERATOR,
                      REFERENCE,
                      original,
                      REFERENCE_EXPIRY,
                      RESPONSE_EXPIRY))
          .isInstanceOf(KeyUnavailableException.class);
    }
  }

  @Test
  void rejectsMalformedFingerprintBeforeReadingTheOwnerKeySource() {
    AtomicInteger reads = new AtomicInteger();
    AccountOperatorAuthorizationFingerprintKeyring keyring =
        new AccountOperatorAuthorizationFingerprintKeyring(
            () -> {
              reads.incrementAndGet();
              return snapshot("active", "active-secret", List.of());
            },
            CLOCK,
            RETENTION_WINDOW);

    assertThatThrownBy(
            () ->
                keyring.matchesOriginal(
                    ReferenceKind.HUMAN_OPERATOR,
                    REFERENCE,
                    "arfp/v1/invalid/not-a-digest",
                    REFERENCE_EXPIRY,
                    RESPONSE_EXPIRY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(reads).hasValue(0);
  }

  @Test
  void rejectsAmbiguousKeySnapshotsAndMissingRetentionPolicy() {
    assertThatThrownBy(
            () ->
                new Snapshot(
                    new KeyMaterial("duplicate", key("active-secret")),
                    List.of(
                        new KeyMaterial(
                            "duplicate", key("retained-secret"), NOW.plusSeconds(600)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountOperatorAuthorizationFingerprintKeyring(
                    () -> snapshot("active", "active-secret", List.of()), CLOCK, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountOperatorAuthorizationFingerprintKeyring keyring(Snapshot snapshot) {
    return new AccountOperatorAuthorizationFingerprintKeyring(
        () -> snapshot, CLOCK, RETENTION_WINDOW);
  }

  private static Snapshot snapshot(
      String activeId, String activeSecret, List<KeyMaterial> retainedKeys) {
    return new Snapshot(new KeyMaterial(activeId, key(activeSecret)), retainedKeys);
  }

  private static AccountOperatorAuthorizationReferenceFingerprint calculator(
      String keyId, String secret) {
    return new AccountOperatorAuthorizationReferenceFingerprint(keyId, key(secret));
  }

  private static SecretKeySpec key(String value) {
    return new SecretKeySpec(
        value.getBytes(java.nio.charset.StandardCharsets.US_ASCII), "HmacSHA256");
  }
}
