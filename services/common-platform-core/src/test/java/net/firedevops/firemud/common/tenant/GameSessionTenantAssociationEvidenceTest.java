package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameSessionTenantAssociationEvidenceTest {
  private static final UUID OPERATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String GAME_SESSION_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void emitsFixedCompleteUtf8FramingAndManifestDigestVector() {
    GameSessionTenantAssociationEvidence evidence = evidence();

    assertThat(new String(evidence.preimage(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo(
            "55:game-design/game-session-retained-tenant-association/v2"
                + "1:2"
                + "36:11111111-1111-4111-8111-111111111111"
                + "7:firemud"
                + "14:owner-key-2026"
                + "10:owner 🧙"
                + "17:review café-🐉"
                + "20:2026-10-01T00:00:00Z"
                + "20:2026-09-30T00:00:00Z"
                + "16:9007199254740993"
                + "36:22222222-2222-4222-8222-222222222222"
                + "2:42"
                + "18:legacy-game-key-42"
                + "17:RETAINED_GAME_V29"
                + "71:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                + "71:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(evidence.manifestDigest())
        .isEqualTo("sha256:1d9feea3a8f6daefd7ff1a3789989a9d5f4d0794e9bc9dbefe2f4d9e61788c46");
  }

  @Test
  void exposesLargeCanonicalBigintTextsWithoutFloatingPointLoss() {
    GameSessionTenantAssociationEvidence aboveExactDoubleInteger = evidence();
    assertThat(aboveExactDoubleInteger.legacyGameSessionTenantIdValue())
        .isEqualTo(9_007_199_254_740_993L);

    GameSessionTenantAssociationEvidence maxBigint =
        copyIds(evidence(), Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE));
    assertThat(maxBigint.legacyGameSessionTenantIdValue()).isEqualTo(Long.MAX_VALUE);
    assertThat(maxBigint.sourceGameRowIdValue()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void changedValidFieldChangesSharedPreimageAndDigest() {
    GameSessionTenantAssociationEvidence source = evidence();
    for (GameSessionTenantAssociationEvidence changed : changedFields(source)) {
      assertThat(changed.preimage()).isNotEqualTo(source.preimage());
      assertThat(changed.manifestDigest()).isNotEqualTo(source.manifestDigest());
    }
    assertThatThrownBy(() -> copyWithSchemaVersion(source, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema version");
  }

  @Test
  void preservesApprovalTextExactlyAndRequiresCanonicalUtcInstant() {
    GameSessionTenantAssociationEvidence spaced =
        new GameSessionTenantAssociationEvidence(
            2,
            OPERATION_ID,
            "firemud",
            "owner-key-2026",
            " owner ",
            " reference ",
            "2026-10-01T00:00:00Z",
            "2026-09-30T00:00:00Z",
            "41",
            TENANT_ID,
            "42",
            " legacy-game-key ",
            "RETAINED_GAME_V29",
            "sha256:" + "b".repeat(64),
            GAME_SESSION_DIGEST);
    assertThat(spaced.approvedBy()).isEqualTo(" owner ");
    assertThat(spaced.approvalReference()).isEqualTo(" reference ");
    assertThat(spaced.sourceGameTenantKey()).isEqualTo(" legacy-game-key ");

    assertInvalid(() -> copySignedAt(evidence(), "2026-10-01T00:00:00+00:00"));
    assertInvalid(() -> copySignedAt(evidence(), "2026-10-01T00:00:00.000Z"));
    assertInvalid(() -> copySignedAt(evidence(), "not-an-instant"));
    assertInvalid(() -> copySourceCapturedAt(evidence(), "2026-10-01T00:00:00.000000001Z"));
    assertInvalid(() -> copySourceCapturedAt(evidence(), "2026-09-30T00:00:00+00:00"));
  }

  @Test
  void requiresCanonicalUtcMicrosecondPrecisionForBothEvidenceInstants() {
    GameSessionTenantAssociationEvidence source = evidence();

    assertThat(copySignedAt(source, "2026-10-01T00:00:00.123456Z").signedAt())
        .isEqualTo("2026-10-01T00:00:00.123456Z");
    assertThat(copySourceCapturedAt(source, "2026-09-30T00:00:00.123456Z").sourceCapturedAt())
        .isEqualTo("2026-09-30T00:00:00.123456Z");
    assertThatThrownBy(() -> copySignedAt(source, "2026-10-01T00:00:00.123456001Z"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("signedAt must have microsecond precision");
    assertThatThrownBy(() -> copySourceCapturedAt(source, "2026-09-30T00:00:00.123456001Z"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sourceCapturedAt must have microsecond precision");
  }

  @Test
  void expiresRawEvidenceAtTheExactThirtyDayCaptureBoundary() {
    GameSessionTenantAssociationEvidence source = evidence();

    assertThat(source.rawExpiredAt(Instant.parse("2026-10-29T23:59:59.999999999Z"))).isFalse();
    assertThat(source.rawExpiredAt(Instant.parse("2026-10-30T00:00:00Z"))).isTrue();
    assertThat(source.rawExpiresAt()).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
  }

  @Test
  void acceptsUtf8ApprovalLimitsAndTheRuntimeTenantSourceKeyBounds() {
    GameSessionTenantAssociationEvidence source = evidence();
    GameSessionTenantAssociationEvidence maximumLabels =
        copyWithLabels(source, "é".repeat(64), "é".repeat(128), "é".repeat(256));
    GameSessionTenantAssociationEvidence maximumSourceKey =
        copyWithSourceKey(source, "🐉".repeat(36));

    assertThat(maximumLabels.signerKeyId()).hasSize(64);
    assertThat(maximumLabels.approvedBy()).hasSize(128);
    assertThat(maximumLabels.approvalReference()).hasSize(256);
    assertThat(maximumSourceKey.sourceGameTenantKey()).hasSize(72);
  }

  @Test
  void rejectsMalformedIdentifiersBoundsUnicodeAndClosedEnums() {
    GameSessionTenantAssociationEvidence source = evidence();
    assertInvalid(() -> withIds(source, "0", source.sourceGameRowId()));
    assertInvalid(() -> withIds(source, "01", source.sourceGameRowId()));
    assertInvalid(() -> withIds(source, "+1", source.sourceGameRowId()));
    assertInvalid(() -> withIds(source, "9223372036854775808", source.sourceGameRowId()));
    assertInvalid(() -> withIds(source, source.legacyGameSessionTenantId(), "00"));
    assertInvalid(() -> withIds(source, source.legacyGameSessionTenantId(), "9223372036854775808"));
    assertInvalid(() -> copyWithUuid(source, new UUID(0L, 0L), source.canonicalTenantId()));
    assertInvalid(() -> copyWithUuid(source, source.operationId(), new UUID(0L, 0L)));
    assertInvalid(() -> copyWithNamespace(source, "firemud.invalid"));
    assertInvalid(() -> copyWithSourceKey(source, " "));
    assertInvalid(() -> copyWithSourceKey(source, "x".repeat(37)));
    assertInvalid(() -> copyWithSourceKey(source, "x" + (char) 0xd800));
    assertInvalid(
        () ->
            copyWithLabels(
                source, "k".repeat(129), source.approvedBy(), source.approvalReference()));
    assertInvalid(
        () ->
            copyWithLabels(
                source, source.signerKeyId(), "a".repeat(257), source.approvalReference()));
    assertInvalid(
        () -> copyWithLabels(source, source.signerKeyId(), source.approvedBy(), "r".repeat(513)));
    assertInvalid(
        () ->
            copyWithLabels(
                source, source.signerKeyId(), "owner\nname", source.approvalReference()));
    assertInvalid(() -> copyWithProvenance(source, "OTHER"));
    assertInvalid(() -> copyWithEvidenceDigest(source, "SHA256:" + "a".repeat(64)));
  }

  private static GameSessionTenantAssociationEvidence evidence() {
    return new GameSessionTenantAssociationEvidence(
        2,
        OPERATION_ID,
        "firemud",
        "owner-key-2026",
        "owner 🧙",
        "review café-🐉",
        Instant.parse("2026-10-01T00:00:00Z").toString(),
        Instant.parse("2026-09-30T00:00:00Z").toString(),
        "9007199254740993",
        TENANT_ID,
        "42",
        "legacy-game-key-42",
        "RETAINED_GAME_V29",
        "sha256:" + "b".repeat(64),
        GAME_SESSION_DIGEST);
  }

  private static GameSessionTenantAssociationEvidence copySignedAt(
      GameSessionTenantAssociationEvidence source, String signedAt) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        signedAt,
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyIds(
      GameSessionTenantAssociationEvidence source,
      String legacyGameSessionTenantId,
      String sourceGameRowId) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        legacyGameSessionTenantId,
        source.canonicalTenantId(),
        sourceGameRowId,
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithSchemaVersion(
      GameSessionTenantAssociationEvidence source, int schemaVersion) {
    return copy(
        source,
        schemaVersion,
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copySourceCapturedAt(
      GameSessionTenantAssociationEvidence source, String sourceCapturedAt) {
    return new GameSessionTenantAssociationEvidence(
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        sourceCapturedAt,
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionProjectionDigest(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithNamespace(
      GameSessionTenantAssociationEvidence source, String namespace) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        namespace,
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithUuid(
      GameSessionTenantAssociationEvidence source, UUID operationId, UUID canonicalTenantId) {
    return copy(
        source,
        source.schemaVersion(),
        operationId,
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        canonicalTenantId,
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithSourceKey(
      GameSessionTenantAssociationEvidence source, String sourceGameTenantKey) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        sourceGameTenantKey,
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithLabels(
      GameSessionTenantAssociationEvidence source,
      String signerKeyId,
      String approvedBy,
      String approvalReference) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        signerKeyId,
        approvedBy,
        approvalReference,
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithProvenance(
      GameSessionTenantAssociationEvidence source, String provenanceKind) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        provenanceKind,
        source.gameSessionEvidenceDigest());
  }

  private static GameSessionTenantAssociationEvidence copyWithEvidenceDigest(
      GameSessionTenantAssociationEvidence source, String gameSessionEvidenceDigest) {
    return copy(
        source,
        source.schemaVersion(),
        source.operationId(),
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        gameSessionEvidenceDigest);
  }

  private static GameSessionTenantAssociationEvidence withIds(
      GameSessionTenantAssociationEvidence source,
      String legacyGameSessionTenantId,
      String sourceGameRowId) {
    return copyIds(source, legacyGameSessionTenantId, sourceGameRowId);
  }

  private static GameSessionTenantAssociationEvidence copy(
      GameSessionTenantAssociationEvidence source,
      int schemaVersion,
      UUID operationId,
      String targetNamespace,
      String signerKeyId,
      String approvedBy,
      String approvalReference,
      String signedAt,
      String legacyGameSessionTenantId,
      UUID canonicalTenantId,
      String sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      String gameSessionEvidenceDigest) {
    return new GameSessionTenantAssociationEvidence(
        schemaVersion,
        operationId,
        targetNamespace,
        signerKeyId,
        approvedBy,
        approvalReference,
        signedAt,
        source.sourceCapturedAt(),
        legacyGameSessionTenantId,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        source.gameSessionProjectionDigest(),
        gameSessionEvidenceDigest);
  }

  private static void assertInvalid(Runnable construction) {
    assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
  }

  private static java.util.List<GameSessionTenantAssociationEvidence> changedFields(
      GameSessionTenantAssociationEvidence source) {
    return java.util.List.of(
        copyIds(source, "9007199254740994", source.sourceGameRowId()),
        copyWithNamespace(source, "firemud-dev"),
        copyWithLabels(source, "owner-key-2027", source.approvedBy(), source.approvalReference()),
        copyWithLabels(source, source.signerKeyId(), "another owner", source.approvalReference()),
        copyWithLabels(
            source, source.signerKeyId(), source.approvedBy(), "another-review-reference"),
        copySignedAt(source, "2026-10-01T00:00:01Z"),
        copyWithOperationId(source, UUID.fromString("33333333-3333-4333-8333-333333333333")),
        copyWithUuid(
            source, source.operationId(), UUID.fromString("33333333-3333-4333-8333-333333333333")),
        copyIds(source, source.legacyGameSessionTenantId(), "43"),
        copyWithSourceKey(source, "another-source-key"),
        copySourceCapturedAt(source, "2026-09-29T00:00:00Z"),
        copyWithProvenance(source, "NEW_GAME_ROW"),
        copyWithEvidenceDigest(source, "sha256:" + "b".repeat(64)));
  }

  private static GameSessionTenantAssociationEvidence copyWithOperationId(
      GameSessionTenantAssociationEvidence source, UUID operationId) {
    return copy(
        source,
        source.schemaVersion(),
        operationId,
        source.targetNamespace(),
        source.signerKeyId(),
        source.approvedBy(),
        source.approvalReference(),
        source.signedAt(),
        source.legacyGameSessionTenantId(),
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        source.gameSessionEvidenceDigest());
  }
}
