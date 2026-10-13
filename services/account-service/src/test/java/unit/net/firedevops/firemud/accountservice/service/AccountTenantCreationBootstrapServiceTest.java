package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Focused proof that no DTO-only or unregistered source can activate creator membership. */
@ExtendWith(MockitoExtension.class)
class AccountTenantCreationBootstrapServiceTest {
  @Mock private AccountRepository accountRepository;
  @Mock private FreshTenantIdentityAssociationRepository tenantAssociationRepository;
  @Mock private AccountCreatorMembershipSourceReader creatorSourceReader;
  @Mock private AccountMembershipPairAuthorityRepository pairAuthorityRepository;
  @Mock private AccountTenantMembershipRepository membershipRepository;
  @Mock private AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository;
  @Mock private AccountAuthorityOutboxRepository authorityOutboxRepository;
  @Mock private AccountAuditOutboxRepository auditOutboxRepository;
  @Mock private AccountTenantCreationBootstrapOperationRepository operationRepository;

  @Mock
  private ObjectProvider<AccountTenantCreationBootstrapAuthorizationSource>
      authorizationSourceProvider;

  @InjectMocks private AccountTenantCreationBootstrapService service;

  @AfterEach
  void clearSyntheticTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void exactCreatorDtoCannotCommitWithoutRegisteredCurrentAccountAuthorizationSource() {
    FreshTenantCreatorEvidence evidence = evidence();
    Account creator = account(evidence.initiatingAccountId());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    when(accountRepository.findByAccountUuidForUpdate(evidence.initiatingAccountId()))
        .thenReturn(Optional.of(creator));
    when(operationRepository.findForUpdate(evidence.accountAuthorizationOperationId()))
        .thenReturn(Optional.empty());
    when(operationRepository.findRequestIdByTenantForUpdate(
            evidence.creationEvidence().canonicalTenantId()))
        .thenReturn(Optional.empty());
    when(authorizationSourceProvider.getIfAvailable()).thenReturn(null);

    assertThatThrownBy(() -> service.bootstrap(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No current Account creator-authorization/source participant");

    verify(authorizationSourceProvider).getIfAvailable();
    verifyNoInteractions(
        tenantAssociationRepository,
        creatorSourceReader,
        pairAuthorityRepository,
        membershipRepository,
        roleSnapshotRepository,
        authorityOutboxRepository,
        auditOutboxRepository);
  }

  private static Account account(UUID accountUuid) {
    Account account = new Account();
    account.setId(17L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(17L);
    return account;
  }

  private static FreshTenantCreatorEvidence evidence() {
    UUID creatorUuid = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    UUID authorizationOperationId = UUID.randomUUID();
    String targetNamespace = "firemud-test";
    String tenantKey = "source-tenant-17";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace, creationRequestId, tenantKey, "Example", null);
    FreshTenantCreationEvidence creationEvidence =
        new FreshTenantCreationEvidence(
            1,
            targetNamespace,
            creationRequestId,
            creationOperationId,
            requestDigest,
            tenantUuid,
            17L,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                targetNamespace,
                creationRequestId,
                creationOperationId,
                requestDigest,
                tenantUuid,
                17L,
                tenantKey,
                "NEW_GAME_ROW"));
    String authorizationDigest = "sha256:" + "4".repeat(64);
    return new FreshTenantCreatorEvidence(
        1,
        creationEvidence,
        creatorUuid,
        authorizationOperationId,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creationEvidence, creatorUuid, authorizationOperationId, authorizationDigest));
  }
}
