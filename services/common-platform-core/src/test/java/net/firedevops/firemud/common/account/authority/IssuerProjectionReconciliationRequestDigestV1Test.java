package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssuerProjectionReconciliationRequestDigestV1Test {
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String CALLER_IDENTITY =
      "spiffe://firemud/ns/account-unit/sa/game-session-service";
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final UUID REQUEST_ID = UUID.fromString("a3a69671-8149-4d97-86f8-25a2582fdd68");

  @Test
  void digestMatchesFixedAsciiAndUtf8ByteFramingVectors() {
    assertThat(
            IssuerProjectionReconciliationRequestDigestV1.digest(
                ISSUER_ID, CALLER_IDENTITY, PROJECTION_KEY, REQUEST_ID))
        .isEqualTo("240027543295dc990703ec833578b423f1cdc51e91333d8b58c91d6c5f571d15");

    assertThat(
            IssuerProjectionReconciliationRequestDigestV1.digest(
                ISSUER_ID,
                "spiffe://firemud/ns/ä/sa/game-session-service",
                PROJECTION_KEY,
                REQUEST_ID))
        .isEqualTo("197ffafb768bc6973f08219426121d55d09403777ee4963d341d218c4a76bcec");
  }

  @Test
  void changingAnyValidRequestBindingChangesTheDigest() {
    String original = digest(ISSUER_ID, CALLER_IDENTITY, PROJECTION_KEY, REQUEST_ID);

    assertThat(
            digest(
                ISSUER_ID + "/next",
                CALLER_IDENTITY,
                "session:game:auth:issuer-generation:v1:" + ISSUER_ID + "/next",
                REQUEST_ID))
        .isNotEqualTo(original);
    assertThat(digest(ISSUER_ID, CALLER_IDENTITY + "/changed", PROJECTION_KEY, REQUEST_ID))
        .isNotEqualTo(original);
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY,
                PROJECTION_KEY,
                UUID.fromString("b3a69671-8149-4d97-86f8-25a2582fdd68")))
        .isNotEqualTo(original);
  }

  @Test
  void rejectsNonDerivedProjectionKeysAndNilRequestIds() {
    assertThatThrownBy(
            () -> digest(ISSUER_ID, CALLER_IDENTITY, PROJECTION_KEY + ":other", REQUEST_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact derived issuer projection key");
    assertThatThrownBy(() -> digest(ISSUER_ID, CALLER_IDENTITY, PROJECTION_KEY, new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
  }

  private static String digest(
      String issuerId, String callerIdentity, String projectionKey, UUID requestId) {
    return IssuerProjectionReconciliationRequestDigestV1.digest(
        issuerId, callerIdentity, projectionKey, requestId);
  }
}
