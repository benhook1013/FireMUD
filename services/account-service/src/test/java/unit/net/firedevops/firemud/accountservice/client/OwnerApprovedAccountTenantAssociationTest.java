package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerApprovedAccountTenantAssociationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void rejectsNilCanonicalIdentityAndNoncanonicalSignature() {
    assertThatThrownBy(
            () -> evidence(new UUID(0L, 0L), Base64.getEncoder().encodeToString(new byte[64])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> evidence(UUID.randomUUID(), "not-a-signature"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidDigestAndSignedAt() {
    assertThatThrownBy(
            () ->
                new OwnerApprovedAccountTenantAssociation(
                    9,
                    UUID.randomUUID(),
                    "legacy-game-tenant",
                    4,
                    "sha256:bad",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    UUID.randomUUID(),
                    DIGEST,
                    Base64.getEncoder().encodeToString(new byte[64]),
                    "firemud",
                    "key-1",
                    "operator",
                    "ticket-1",
                    "not-an-instant",
                    1,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OwnerApprovedAccountTenantAssociation evidence(UUID canonical, String signature) {
    return new OwnerApprovedAccountTenantAssociation(
        9,
        canonical,
        "legacy-game-tenant",
        4,
        DIGEST,
        Instant.parse("2026-10-01T00:00:00Z"),
        UUID.randomUUID(),
        DIGEST,
        signature,
        "firemud",
        "key-1",
        "operator",
        "ticket-1",
        "2026-10-01T00:00:00Z",
        1,
        1);
  }
}
