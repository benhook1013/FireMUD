package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipAuthorityEventProducerCanonicalJoinTest {
  @Test
  void canonicalFirstJoinRequiresWritableOwnerTransactionBeforeRepositoryReads() {
    Repositories repositories = new Repositories();
    boolean previousActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean previousReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    try {
      assertThatThrownBy(
              () ->
                  repositories
                      .producer()
                      .publishCanonicalFirstJoinMembershipChange(
                          null, UUID.randomUUID().toString(), "verified-caller"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      repositories.verifyNoReads();

      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
      assertThatThrownBy(
              () ->
                  repositories
                      .producer()
                      .publishCanonicalFirstJoinMembershipChange(
                          null, UUID.randomUUID().toString(), "verified-caller"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable owner transaction");
      repositories.verifyNoReads();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previousActive);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(previousReadOnly);
    }
  }

  @Test
  void existingUuidMembershipReadRequiresOwnerTransactionBeforeRepositoryReads() {
    Repositories repositories = new Repositories();
    boolean previousActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean previousReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    try {
      assertThatThrownBy(
              () ->
                  repositories
                      .producer()
                      .readExistingRuntimeMembershipSnapshot(UUID.randomUUID(), UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      repositories.verifyNoReads();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previousActive);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(previousReadOnly);
    }
  }

  private static final class Repositories {
    private final AccountJoinOperationRepository joinOperations =
        mock(AccountJoinOperationRepository.class);
    private final AccountMembershipPairAuthorityRepository pairAuthority =
        mock(AccountMembershipPairAuthorityRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountTenantIdentityResolver tenantIdentities =
        mock(AccountTenantIdentityResolver.class);
    private final FreshTenantIdentityAssociationRepository freshAssociations =
        mock(FreshTenantIdentityAssociationRepository.class);
    private final AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    private final AccountAuthorityOutboxRepository authorityOutbox =
        mock(AccountAuthorityOutboxRepository.class);
    private final AccountMembershipTransitionReceiptRepository transitionReceipts =
        mock(AccountMembershipTransitionReceiptRepository.class);
    private final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    private final AccountTenantMembershipRoleSnapshotRepository roleSnapshots =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);

    private AccountMembershipAuthorityEventProducer producer() {
      return new AccountMembershipAuthorityEventProducer(
          joinOperations,
          pairAuthority,
          accounts,
          tenantIdentities,
          freshAssociations,
          generations,
          authorityOutbox,
          mock(AccountPasswordResetOperationRepository.class),
          mock(AccountLogoutAllOperationRepository.class),
          org.mockito.Mockito.mock(
              net.firedevops.firemud.accountservice.repository
                  .AccountSecurityStateOperationRepository.class),
          transitionReceipts,
          memberships,
          roleSnapshots,
          mock(net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository.class),
          mock(
              net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository
                  .class));
    }

    private void verifyNoReads() {
      verifyNoInteractions(
          joinOperations,
          pairAuthority,
          accounts,
          tenantIdentities,
          freshAssociations,
          generations,
          authorityOutbox,
          transitionReceipts,
          memberships,
          roleSnapshots);
    }
  }
}
