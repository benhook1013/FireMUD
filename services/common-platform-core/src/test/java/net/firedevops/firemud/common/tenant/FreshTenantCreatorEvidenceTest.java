package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreshTenantCreatorEvidenceTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID AUTHORIZATION_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void acceptsCreatorQualificationBoundToFreshCreationAndAccountAuthorization() {
    FreshTenantCreatorEvidence evidence = validEvidence();

    assertThat(evidence.schemaVersion()).isEqualTo(1);
    assertThat(evidence.creationEvidence()).isEqualTo(creationEvidence());
    assertThat(evidence.initiatingAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(evidence.accountAuthorizationOperationId()).isEqualTo(AUTHORIZATION_OPERATION_ID);
    assertThat(evidence.accountAuthorizationDigest()).isEqualTo(AUTHORIZATION_DIGEST);
    assertThat(evidence.evidenceDigest())
        .isEqualTo(
            FreshTenantCreatorDigest.evidenceDigest(
                evidence.schemaVersion(),
                evidence.creationEvidence(),
                evidence.initiatingAccountId(),
                evidence.accountAuthorizationOperationId(),
                evidence.accountAuthorizationDigest()));
  }

  @Test
  void rejectsReusedOriginalDigestWhenAnyCreatorIdentityOrCreationEvidenceChanges() {
    FreshTenantCreatorEvidence valid = validEvidence();
    String originalDigest = valid.evidenceDigest();

    assertInvalid(
        1,
        creationEvidence(),
        UUID.fromString("66666666-6666-4666-8666-666666666666"),
        AUTHORIZATION_OPERATION_ID,
        AUTHORIZATION_DIGEST,
        originalDigest);
    assertInvalid(
        1,
        creationEvidence(),
        ACCOUNT_ID,
        UUID.fromString("77777777-7777-4777-8777-777777777777"),
        AUTHORIZATION_DIGEST,
        originalDigest);
    assertInvalid(
        1,
        creationEvidence(),
        ACCOUNT_ID,
        AUTHORIZATION_OPERATION_ID,
        "sha256:" + "b".repeat(64),
        originalDigest);
    assertInvalid(
        1,
        creationEvidence(43L, "legacy-α"),
        ACCOUNT_ID,
        AUTHORIZATION_OPERATION_ID,
        AUTHORIZATION_DIGEST,
        originalDigest);
    assertInvalid(
        1,
        creationEvidence(42L, "legacy-beta"),
        ACCOUNT_ID,
        AUTHORIZATION_OPERATION_ID,
        AUTHORIZATION_DIGEST,
        originalDigest);
  }

  @Test
  void rejectsUnsupportedVersionNilIdentitiesAndMalformedAuthorizationDigest() {
    FreshTenantCreationEvidence creation = creationEvidence();
    String correctDigest =
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST);

    assertInvalid(
        2, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST, correctDigest);
    assertInvalid(
        1,
        creation,
        new UUID(0L, 0L),
        AUTHORIZATION_OPERATION_ID,
        AUTHORIZATION_DIGEST,
        correctDigest);
    assertInvalid(1, creation, ACCOUNT_ID, new UUID(0L, 0L), AUTHORIZATION_DIGEST, correctDigest);
    assertThatThrownBy(
            () ->
                FreshTenantCreatorDigest.evidenceDigest(
                    1,
                    creation,
                    ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    "SHA256:" + "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static FreshTenantCreatorEvidence validEvidence() {
    FreshTenantCreationEvidence creation = creationEvidence();
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        ACCOUNT_ID,
        AUTHORIZATION_OPERATION_ID,
        AUTHORIZATION_DIGEST,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, ACCOUNT_ID, AUTHORIZATION_OPERATION_ID, AUTHORIZATION_DIGEST));
  }

  private static FreshTenantCreationEvidence creationEvidence() {
    return creationEvidence(42L, "legacy-α");
  }

  private static FreshTenantCreationEvidence creationEvidence(long rowId, String sourceKey) {
    UUID requestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID tenantId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    String requestDigest = "sha256:" + "c".repeat(64);
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        tenantId,
        rowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            requestId,
            operationId,
            requestDigest,
            tenantId,
            rowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }

  private static void assertInvalid(
      int schemaVersion,
      FreshTenantCreationEvidence creation,
      UUID accountId,
      UUID authorizationOperationId,
      String authorizationDigest,
      String evidenceDigest) {
    assertThatThrownBy(
            () ->
                new FreshTenantCreatorEvidence(
                    schemaVersion,
                    creation,
                    accountId,
                    authorizationOperationId,
                    authorizationDigest,
                    evidenceDigest))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
