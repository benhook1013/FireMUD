package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreshTenantCreatorEvidenceTest {
  private static final UUID INITIATOR_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID AUTHORIZATION_OPERATION_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void acceptsExactCreatorQualificationWithoutChangingOriginalCreationEvidence() {
    FreshTenantCreationEvidence source = freshTenantEvidence();
    String creatorDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);

    FreshTenantCreatorEvidence qualification =
        new FreshTenantCreatorEvidence(
            1,
            source,
            INITIATOR_ID,
            AUTHORIZATION_OPERATION_ID,
            AUTHORIZATION_DIGEST,
            creatorDigest);

    assertThat(qualification.creationEvidence()).isSameAs(source);
    assertThat(qualification.initiatingAccountId()).isEqualTo(INITIATOR_ID);
    assertThat(qualification.accountAuthorizationOperationId())
        .isEqualTo(AUTHORIZATION_OPERATION_ID);
    assertThat(qualification.accountAuthorizationDigest()).isEqualTo(AUTHORIZATION_DIGEST);
    assertThat(qualification.evidenceDigest()).isEqualTo(creatorDigest);
  }

  @Test
  void rejectsUnsupportedVersionNilIdentitiesMalformedAuthorizationDigestAndChangedBinding() {
    FreshTenantCreationEvidence source = freshTenantEvidence();
    String digest =
        FreshTenantCreatorDigest.evidenceDigest(
            1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);

    assertInvalid(
        2, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST, digest);
    assertInvalid(
        1, source, new UUID(0L, 0L), AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST, digest);
    assertInvalid(1, source, INITIATOR_ID, new UUID(0L, 0L), AUTHORIZATION_DIGEST, digest);
    assertInvalid(
        1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, "SHA256:" + "a".repeat(64), digest);
    assertInvalid(
        1,
        source,
        INITIATOR_ID,
        AUTHORIZATION_OPERATION_ID,
        AUTHORIZATION_DIGEST,
        "sha256:" + "b".repeat(64));
  }

  @Test
  void creatorDigestBindsEveryCreationReceiptAndAuthorizationIdentity() {
    FreshTenantCreationEvidence source = freshTenantEvidence();
    String original =
        FreshTenantCreatorDigest.evidenceDigest(
            1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);

    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1,
                source,
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST))
        .isNotEqualTo(original);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1,
                source,
                INITIATOR_ID,
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                AUTHORIZATION_DIGEST))
        .isNotEqualTo(original);
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1, source, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, "sha256:" + "b".repeat(64)))
        .isNotEqualTo(original);

    FreshTenantCreationEvidence changedSource =
        freshTenantEvidence(
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            source.sourceGameRowId() + 1,
            source.sourceGameTenantKey());
    assertThat(
            FreshTenantCreatorDigest.evidenceDigest(
                1, changedSource, INITIATOR_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST))
        .isNotEqualTo(original);
  }

  private static void assertInvalid(
      int schemaVersion,
      FreshTenantCreationEvidence source,
      UUID initiatingAccountId,
      UUID authorizationOperationId,
      String authorizationDigest,
      String evidenceDigest) {
    assertThatThrownBy(
            () ->
                new FreshTenantCreatorEvidence(
                    schemaVersion,
                    source,
                    initiatingAccountId,
                    authorizationOperationId,
                    authorizationDigest,
                    evidenceDigest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static FreshTenantCreationEvidence freshTenantEvidence() {
    return freshTenantEvidence(
        UUID.fromString("33333333-3333-4333-8333-333333333333"), 42L, "new-game-owner-key");
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(
      UUID canonicalTenantId, long sourceRowId, String sourceKey) {
    UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    String requestDigest =
        GameTenantCreationDigest.requestDigest("prod", requestId, sourceKey, "World", null);
    String sourceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        sourceDigest);
  }
}
