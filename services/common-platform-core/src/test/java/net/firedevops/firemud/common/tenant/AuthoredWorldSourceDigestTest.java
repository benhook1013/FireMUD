package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoredWorldSourceDigestTest {
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
  private static final String PROVENANCE = "RETAINED_GAME_V30";

  @Test
  void emitsFixedLengthFramedRequestAndReceiptVectorsForMultibyteDisplayName() {
    String requestDigest = requestDigest();

    assertThat(requestDigest)
        .isEqualTo("sha256:2f2e50fd98aede5418d7b52b8c837e12b092edd4044624b4047d21123cb35ba3");
    assertThat(
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
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isEqualTo("sha256:847d6cce8840ef21e3c3e5507d24ed71f3ad8a847f33572d6fc7f9b51a4a127c");
  }

  @Test
  void everyRequestAndReceiptFieldChangesItsDigest() {
    String requestDigest = requestDigest();
    String evidenceDigest =
        evidenceDigest(
            NAMESPACE,
            REGISTRATION_ID,
            OPERATION_ID,
            requestDigest,
            TENANT_ID,
            TENANT_SLUG,
            WORLD_SLUG,
            DISPLAY_NAME,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE);

    assertThat(
            AuthoredWorldSourceDigest.requestDigest(
                "other", REGISTRATION_ID, TENANT_ID, TENANT_SLUG, WORLD_SLUG, DISPLAY_NAME))
        .isNotEqualTo(requestDigest);
    assertThat(
            AuthoredWorldSourceDigest.requestDigest(
                NAMESPACE, UUID.randomUUID(), TENANT_ID, TENANT_SLUG, WORLD_SLUG, DISPLAY_NAME))
        .isNotEqualTo(requestDigest);
    assertThat(
            AuthoredWorldSourceDigest.requestDigest(
                NAMESPACE,
                REGISTRATION_ID,
                UUID.randomUUID(),
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME))
        .isNotEqualTo(requestDigest);
    assertThat(
            AuthoredWorldSourceDigest.requestDigest(
                NAMESPACE, REGISTRATION_ID, TENANT_ID, "south-star", WORLD_SLUG, DISPLAY_NAME))
        .isNotEqualTo(requestDigest);
    assertThat(
            AuthoredWorldSourceDigest.requestDigest(
                NAMESPACE, REGISTRATION_ID, TENANT_ID, TENANT_SLUG, "green-wilds", DISPLAY_NAME))
        .isNotEqualTo(requestDigest);
    assertThat(
            AuthoredWorldSourceDigest.requestDigest(
                NAMESPACE, REGISTRATION_ID, TENANT_ID, TENANT_SLUG, WORLD_SLUG, "Café 🐲"))
        .isNotEqualTo(requestDigest);

    assertThat(
            evidenceDigest(
                "other",
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                UUID.randomUUID(),
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                UUID.randomUUID(),
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                "sha256:" + "a".repeat(64),
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                UUID.randomUUID(),
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                "south-star",
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                "green-wilds",
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                "Café 🐲",
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID + 1,
                SOURCE_GAME_TENANT_KEY,
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                "another-game-key",
                PROVENANCE))
        .isNotEqualTo(evidenceDigest);
    assertThat(
            evidenceDigest(
                NAMESPACE,
                REGISTRATION_ID,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                TENANT_SLUG,
                WORLD_SLUG,
                DISPLAY_NAME,
                SOURCE_GAME_ROW_ID,
                SOURCE_GAME_TENANT_KEY,
                "NEW_GAME_ROW"))
        .isNotEqualTo(evidenceDigest);
  }

  private static String requestDigest() {
    return AuthoredWorldSourceDigest.requestDigest(
        NAMESPACE, REGISTRATION_ID, TENANT_ID, TENANT_SLUG, WORLD_SLUG, DISPLAY_NAME);
  }

  private static String evidenceDigest(
      String namespace,
      UUID registrationRequestId,
      UUID operationId,
      String requestDigest,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String displayName,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind) {
    return AuthoredWorldSourceDigest.evidenceDigest(
        namespace,
        registrationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind);
  }
}
