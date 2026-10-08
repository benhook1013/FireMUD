package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoredWorldSourceEvidenceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID REGISTRATION_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String TENANT_SLUG = "north-star";
  private static final String WORLD_SLUG = "violet-wilds";
  private static final String DISPLAY_NAME = "Café 🐉";
  private static final long SOURCE_GAME_ROW_ID = 42L;
  private static final String SOURCE_GAME_TENANT_KEY = "legacy-game-tenant-42";
  private static final String PROVENANCE = "RETAINED_GAME_V29";

  @Test
  void acceptsClosedNewAndRetainedSourceEvidenceAndRecomputesBothDigests() {
    assertThat(evidence("NEW_GAME_ROW").provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(evidence("RETAINED_GAME_V29").provenanceKind()).isEqualTo("RETAINED_GAME_V29");
  }

  @Test
  void acceptsMaximumSelectorBytesAndDisplayCodePoints() {
    String maximumTenantSlug = "a".repeat(120);
    String maximumWorldSlug = "b".repeat(120);
    String maximumDisplayName = "é".repeat(100);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            REGISTRATION_ID,
            TENANT_ID,
            maximumTenantSlug,
            maximumWorldSlug,
            maximumDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_ID,
            OPERATION_ID,
            requestDigest,
            TENANT_ID,
            maximumTenantSlug,
            maximumWorldSlug,
            maximumDisplayName,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE);

    assertThat(
            new AuthoredWorldSourceEvidence(
                    1,
                    NAMESPACE,
                    REGISTRATION_ID,
                    OPERATION_ID,
                    requestDigest,
                    TENANT_ID,
                    maximumTenantSlug,
                    maximumWorldSlug,
                    maximumDisplayName,
                    SOURCE_GAME_ROW_ID,
                    SOURCE_GAME_TENANT_KEY,
                    PROVENANCE,
                    evidenceDigest)
                .worldDisplayName())
        .hasSize(100);
  }

  @Test
  void acceptsSourceTenantKeyAtThirtySixCodePointsAndSeventyTwoUtf16Units() {
    String maximumSourceKey = "😀".repeat(36);
    AuthoredWorldSourceEvidence evidence = evidence(maximumSourceKey, PROVENANCE);

    assertThat(evidence.sourceGameTenantKey()).hasSize(72);
    assertThat(evidence.sourceGameTenantKey().codePointCount(0, 72)).isEqualTo(36);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        "😀".repeat(37),
        PROVENANCE);
  }

  @Test
  void rejectsUnsupportedVersionMalformedIdentitySelectorsAndDisplayName() {
    AuthoredWorldSourceEvidence valid = evidence(PROVENANCE);
    assertInvalid(
        2,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        "bad.namespace",
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        new UUID(0L, 0L),
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        new UUID(0L, 0L),
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        new UUID(0L, 0L),
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        "North-Star",
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        "a".repeat(121),
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        "bad_slug",
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        "  ",
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        "😀".repeat(101),
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        "bad" + (char) 0xd800,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertThat(valid.worldDisplayName()).isEqualTo(DISPLAY_NAME);
  }

  @Test
  void rejectsChangedRequestReceiptSourceBoundsAndUnknownProvenance() {
    AuthoredWorldSourceEvidence valid = evidence(PROVENANCE);
    assertThatThrownBy(
            () ->
                new AuthoredWorldSourceEvidence(
                    valid.schemaVersion(),
                    valid.targetNamespace(),
                    valid.registrationRequestId(),
                    valid.operationId(),
                    "sha256:" + "0".repeat(64),
                    valid.canonicalTenantId(),
                    valid.tenantSlug(),
                    valid.worldSlug(),
                    valid.worldDisplayName(),
                    valid.sourceGameRowId(),
                    valid.sourceGameTenantKey(),
                    valid.provenanceKind(),
                    valid.evidenceDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Request digest");
    assertThatThrownBy(
            () ->
                new AuthoredWorldSourceEvidence(
                    valid.schemaVersion(),
                    valid.targetNamespace(),
                    valid.registrationRequestId(),
                    valid.operationId(),
                    valid.requestDigest(),
                    valid.canonicalTenantId(),
                    valid.tenantSlug(),
                    valid.worldSlug(),
                    valid.worldDisplayName(),
                    valid.sourceGameRowId(),
                    valid.sourceGameTenantKey(),
                    valid.provenanceKind(),
                    "sha256:" + "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Evidence digest");
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        0L,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        "x".repeat(37),
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        "bad" + (char) 0xd800,
        PROVENANCE);
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        "OTHER");
    assertInvalid(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        "RETAINED_GAME_V30");
  }

  private static AuthoredWorldSourceEvidence evidence(String provenanceKind) {
    return evidence(SOURCE_GAME_TENANT_KEY, provenanceKind);
  }

  private static AuthoredWorldSourceEvidence evidence(
      String sourceGameTenantKey, String provenanceKind) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_ID, TENANT_ID, TENANT_SLUG, WORLD_SLUG, DISPLAY_NAME);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_ID,
            OPERATION_ID,
            requestDigest,
            TENANT_ID,
            TENANT_SLUG,
            WORLD_SLUG,
            DISPLAY_NAME,
            SOURCE_GAME_ROW_ID,
            sourceGameTenantKey,
            provenanceKind);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_ID,
        OPERATION_ID,
        requestDigest,
        TENANT_ID,
        TENANT_SLUG,
        WORLD_SLUG,
        DISPLAY_NAME,
        SOURCE_GAME_ROW_ID,
        sourceGameTenantKey,
        provenanceKind,
        evidenceDigest);
  }

  private static void assertInvalid(
      int schemaVersion,
      String targetNamespace,
      UUID registrationRequestId,
      UUID operationId,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    assertThatThrownBy(
            () -> {
              String requestDigest =
                  AuthoredWorldSourceDigest.requestDigest(
                      targetNamespace,
                      registrationRequestId,
                      canonicalTenantId,
                      tenantSlug,
                      worldSlug,
                      worldDisplayName);
              String evidenceDigest =
                  AuthoredWorldSourceDigest.evidenceDigest(
                      targetNamespace,
                      registrationRequestId,
                      operationId,
                      requestDigest,
                      canonicalTenantId,
                      tenantSlug,
                      worldSlug,
                      worldDisplayName,
                      sourceGameRowId,
                      sourceGameTenantKey,
                      provenanceKind);
              new AuthoredWorldSourceEvidence(
                  schemaVersion,
                  targetNamespace,
                  registrationRequestId,
                  operationId,
                  requestDigest,
                  canonicalTenantId,
                  tenantSlug,
                  worldSlug,
                  worldDisplayName,
                  sourceGameRowId,
                  sourceGameTenantKey,
                  provenanceKind,
                  evidenceDigest);
            })
        .isInstanceOf(IllegalArgumentException.class);
  }
}
