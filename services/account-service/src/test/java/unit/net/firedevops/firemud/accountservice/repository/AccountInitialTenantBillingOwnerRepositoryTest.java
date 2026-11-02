package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository.InitialRequest;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountInitialTenantBillingOwnerRepositoryTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CONSENT_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void writeRequiresAnActiveWritableTransactionBeforeAnyDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountInitialTenantBillingOwnerRepository repository =
        new AccountInitialTenantBillingOwnerRepository(dsl);
    InitialRequest request = request(freshTenantCreator(ACCOUNT_UUID, freshTenant(TENANT_UUID)));

    assertThatThrownBy(() -> repository.establishInitial(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    verifyNoInteractions(dsl);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.establishInitial(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    verifyNoInteractions(dsl);
  }

  @Test
  void requestRequiresExplicitAffirmativeConsentAndPositiveVersion() {
    FreshTenantCreatorEvidence creator = freshTenantCreator(ACCOUNT_UUID, freshTenant(TENANT_UUID));

    assertThatThrownBy(
            () -> new InitialRequest(REQUEST_ID, creator, CONSENT_OPERATION_ID, 1, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be affirmative");
    assertThatThrownBy(() -> new InitialRequest(REQUEST_ID, creator, CONSENT_OPERATION_ID, 0, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  @Test
  void requestAndConsentDigestsBindEveryInitialOwnerInputAndDefensivelyCopyBytes() {
    FreshTenantCreationEvidence creation = freshTenant(TENANT_UUID);
    FreshTenantCreatorEvidence creator = freshTenantCreator(ACCOUNT_UUID, creation);
    InitialRequest original = request(creator);
    var encoded = AccountInitialTenantBillingOwnerDigest.request(original);
    var exactRetry = AccountInitialTenantBillingOwnerDigest.request(original);
    var changedConsent =
        AccountInitialTenantBillingOwnerDigest.request(
            new InitialRequest(
                REQUEST_ID,
                creator,
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                1,
                true));
    var changedStatementVersion =
        AccountInitialTenantBillingOwnerDigest.request(
            new InitialRequest(REQUEST_ID, creator, CONSENT_OPERATION_ID, 2, true));

    assertThat(encoded.requestPayload()).containsExactly(exactRetry.requestPayload());
    assertThat(encoded.requestDigest()).isEqualTo(exactRetry.requestDigest());
    assertThat(encoded.consentPayload()).containsExactly(exactRetry.consentPayload());
    assertThat(encoded.consentDigest()).isEqualTo(exactRetry.consentDigest());
    assertThat(encoded.requestDigest()).startsWith("sha256:");
    assertThat(encoded.consentDigest()).startsWith("sha256:");
    assertThat(changedConsent.requestDigest()).isNotEqualTo(encoded.requestDigest());
    assertThat(changedStatementVersion.requestDigest()).isNotEqualTo(encoded.requestDigest());

    byte[] leaked = encoded.requestPayload();
    leaked[0] ^= 0x01;
    assertThat(encoded.requestPayload()).containsExactly(exactRetry.requestPayload());
  }

  private static InitialRequest request(FreshTenantCreatorEvidence creator) {
    return new InitialRequest(REQUEST_ID, creator, CONSENT_OPERATION_ID, 1, true);
  }

  private static FreshTenantCreatorEvidence freshTenantCreator(
      UUID accountUuid, FreshTenantCreationEvidence creation) {
    UUID authorizationOperationId = UUID.fromString("66666666-6666-4666-8666-666666666666");
    String authorizationDigest = "sha256:" + "a".repeat(64);
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        accountUuid,
        authorizationOperationId,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, accountUuid, authorizationOperationId, authorizationDigest));
  }

  private static FreshTenantCreationEvidence freshTenant(UUID tenantUuid) {
    UUID creationRequestId = UUID.fromString("77777777-7777-4777-8777-777777777777");
    UUID creationOperationId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    String sourceTenantKey = "fresh-game-tenant";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            "prod", creationRequestId, sourceTenantKey, "name", "description");
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            creationRequestId,
            creationOperationId,
            requestDigest,
            tenantUuid,
            42L,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        creationRequestId,
        creationOperationId,
        requestDigest,
        tenantUuid,
        42L,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }
}
