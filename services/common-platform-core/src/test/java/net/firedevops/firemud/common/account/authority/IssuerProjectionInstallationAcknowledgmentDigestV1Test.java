package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssuerProjectionInstallationAcknowledgmentDigestV1Test {
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String CALLER_IDENTITY =
      "spiffe://firemud/ns/firemud-test/sa/game-session-service";
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final UUID CAPTURE_OPERATION_ID =
      UUID.fromString("91b0eb2d-f7b1-4d88-8db4-2b81cacfc96a");
  private static final UUID CAPTURE_REQUEST_ID =
      UUID.fromString("f50e8400-e29b-41d4-a716-446655440000");
  private static final String CAPTURE_REQUEST_DIGEST = "a".repeat(64);
  private static final String PROJECTION_JSON =
      "{\"schemaVersion\":\"game-session-auth-issuer-projection/v1\","
          + "\"issuerId\":\"https://account.example.test/issuer\","
          + "\"lastAppliedIssuerGeneration\":\"1\","
          + "\"lastAppliedSourceOutboxSequence\":\"0\","
          + "\"outboxStreamKey\":\"account:auth-authority:v1:issuer/"
          + "https://account.example.test/issuer\","
          + "\"appliedAt\":\"2026-10-03T00:00:00Z\","
          + "\"appliedSourceEvidence\":{}}";

  @Test
  void digestMatchesFixedAsciiAndUnicodeVectors() {
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY,
                PROJECTION_KEY,
                CAPTURE_OPERATION_ID,
                CAPTURE_REQUEST_ID,
                CAPTURE_REQUEST_DIGEST,
                PROJECTION_JSON))
        .isEqualTo("18971fbc3cd529421f6f4af5bcf68a9b2e060bc6b4c5b73c74ac9daf00acc507");

    String unicodeProjection =
        PROJECTION_JSON.replace("2026-10-03T00:00:00Z", "2026-10-03T00:00:00Z-ä");
    assertThat(
            digest(
                ISSUER_ID,
                "spiffe://firemud/ns/ä/sa/game-session-service",
                PROJECTION_KEY,
                CAPTURE_OPERATION_ID,
                CAPTURE_REQUEST_ID,
                CAPTURE_REQUEST_DIGEST,
                unicodeProjection))
        .isEqualTo("658a31737ceb9a3c34b8810f437da1eedf67dc3b762bdde7ea6f717ad3a87e2e");
  }

  @Test
  void changingAnyVariablePreimageFieldChangesTheDigest() {
    String original =
        digest(
            ISSUER_ID,
            CALLER_IDENTITY,
            PROJECTION_KEY,
            CAPTURE_OPERATION_ID,
            CAPTURE_REQUEST_ID,
            CAPTURE_REQUEST_DIGEST,
            PROJECTION_JSON);

    assertThat(
            digest(
                ISSUER_ID + "/next",
                CALLER_IDENTITY,
                "session:game:auth:issuer-generation:v1:" + ISSUER_ID + "/next",
                CAPTURE_OPERATION_ID,
                CAPTURE_REQUEST_ID,
                CAPTURE_REQUEST_DIGEST,
                PROJECTION_JSON))
        .isNotEqualTo(original);
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY + "/changed",
                PROJECTION_KEY,
                CAPTURE_OPERATION_ID,
                CAPTURE_REQUEST_ID,
                CAPTURE_REQUEST_DIGEST,
                PROJECTION_JSON))
        .isNotEqualTo(original);
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY,
                PROJECTION_KEY,
                UUID.fromString("a1b0eb2d-f7b1-4d88-8db4-2b81cacfc96a"),
                CAPTURE_REQUEST_ID,
                CAPTURE_REQUEST_DIGEST,
                PROJECTION_JSON))
        .isNotEqualTo(original);
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY,
                PROJECTION_KEY,
                CAPTURE_OPERATION_ID,
                UUID.fromString("a50e8400-e29b-41d4-a716-446655440000"),
                CAPTURE_REQUEST_DIGEST,
                PROJECTION_JSON))
        .isNotEqualTo(original);
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY,
                PROJECTION_KEY,
                CAPTURE_OPERATION_ID,
                CAPTURE_REQUEST_ID,
                "b".repeat(64),
                PROJECTION_JSON))
        .isNotEqualTo(original);
    assertThat(
            digest(
                ISSUER_ID,
                CALLER_IDENTITY,
                PROJECTION_KEY,
                CAPTURE_OPERATION_ID,
                CAPTURE_REQUEST_ID,
                CAPTURE_REQUEST_DIGEST,
                PROJECTION_JSON + " "))
        .isNotEqualTo(original);
  }

  @Test
  void rejectsInvalidCaptureBindingsAndNilIds() {
    assertThatThrownBy(
            () ->
                digest(
                    ISSUER_ID,
                    CALLER_IDENTITY,
                    PROJECTION_KEY + ":other",
                    CAPTURE_OPERATION_ID,
                    CAPTURE_REQUEST_ID,
                    CAPTURE_REQUEST_DIGEST,
                    PROJECTION_JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capture binding");
    assertThatThrownBy(
            () ->
                IssuerProjectionInstallationAcknowledgmentDigestV1.digest(
                    ISSUER_ID,
                    CALLER_IDENTITY,
                    PROJECTION_KEY,
                    new UUID(0L, 0L),
                    CAPTURE_REQUEST_ID,
                    1,
                    CAPTURE_REQUEST_DIGEST,
                    PROJECTION_JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    assertThatThrownBy(
            () ->
                IssuerProjectionInstallationAcknowledgmentDigestV1.digest(
                    ISSUER_ID,
                    CALLER_IDENTITY,
                    PROJECTION_KEY,
                    CAPTURE_OPERATION_ID,
                    CAPTURE_REQUEST_ID,
                    2,
                    CAPTURE_REQUEST_DIGEST,
                    PROJECTION_JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capture binding");
    assertThatThrownBy(
            () ->
                digest(
                    ISSUER_ID,
                    CALLER_IDENTITY,
                    PROJECTION_KEY,
                    CAPTURE_OPERATION_ID,
                    CAPTURE_REQUEST_ID,
                    "A".repeat(64),
                    PROJECTION_JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capture binding");
  }

  @Test
  void rejectsMalformedUnicodeAndProjectionBytesOverTheStorageBound() {
    String unpairedSurrogate = String.valueOf((char) 0xd800);
    assertThatThrownBy(
            () ->
                digest(
                    ISSUER_ID,
                    CALLER_IDENTITY + unpairedSurrogate,
                    PROJECTION_KEY,
                    CAPTURE_OPERATION_ID,
                    CAPTURE_REQUEST_ID,
                    CAPTURE_REQUEST_DIGEST,
                    PROJECTION_JSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");
    assertThatThrownBy(
            () ->
                digest(
                    ISSUER_ID,
                    CALLER_IDENTITY,
                    PROJECTION_KEY,
                    CAPTURE_OPERATION_ID,
                    CAPTURE_REQUEST_ID,
                    CAPTURE_REQUEST_DIGEST,
                    PROJECTION_JSON + unpairedSurrogate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");
    assertThatThrownBy(
            () ->
                digest(
                    ISSUER_ID,
                    CALLER_IDENTITY,
                    PROJECTION_KEY,
                    CAPTURE_OPERATION_ID,
                    CAPTURE_REQUEST_ID,
                    CAPTURE_REQUEST_DIGEST,
                    "x"
                        .repeat(
                            IssuerProjectionInstallationAcknowledgmentDigestV1
                                    .MAX_INSTALLED_PROJECTION_UTF8_BYTES
                                + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65536 UTF-8 bytes");
  }

  private static String digest(
      String issuerId,
      String callerIdentity,
      String projectionKey,
      UUID captureOperationId,
      UUID captureRequestId,
      String captureRequestDigest,
      String installedProjectionJson) {
    return IssuerProjectionInstallationAcknowledgmentDigestV1.digest(
        issuerId,
        callerIdentity,
        projectionKey,
        captureOperationId,
        captureRequestId,
        1,
        captureRequestDigest,
        installedProjectionJson);
  }
}
