package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreshTenantCreatorDigestTest {
  private static final UUID INITIATOR_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID AUTHORIZATION_OPERATION_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void exactTupleProducesStableCanonicalSha256Digest() {
    FreshTenantCreationEvidence source = freshTenantEvidence();

    String first =
        FreshTenantCreatorDigest.evidenceDigest(
            1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);
    String retry =
        FreshTenantCreatorDigest.evidenceDigest(
            1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);

    assertThat(first).matches("sha256:[0-9a-f]{64}").isEqualTo(retry);
  }

  @Test
  void rejectsInvalidSchemaNilIdsAndNoncanonicalAccountDigest() {
    FreshTenantCreationEvidence source = freshTenantEvidence();

    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    2, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1, source, new UUID(0L, 0L), AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1, source, INITIATOR_ID, new UUID(0L, 0L), AUTHORIZATION_DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, "bad"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static FreshTenantCreationEvidence freshTenantEvidence() {
    UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID tenantId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    String sourceKey = "new-game-owner-key";
    String requestDigest =
        GameTenantCreationDigest.requestDigest("prod", requestId, sourceKey, "World", null);
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        tenantId,
        42L,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            tenantId,
            42L,
            sourceKey,
            "NEW_GAME_ROW"));
  }
}
