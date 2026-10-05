package net.firedevops.firemud.accountservice.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipAuthorityEventProducerTest {
  @Test
  void inactiveMembershipSnapshotRequiresOwnerTransactionBeforeRepositoryReads() {
    AccountJoinOperationRepository joinOperationRepository =
        mock(AccountJoinOperationRepository.class);
    AccountMembershipPairAuthorityRepository pairAuthorityRepository =
        mock(AccountMembershipPairAuthorityRepository.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    AccountTenantIdentityResolver tenantIdentityResolver =
        mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshAssociationRepository =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountAuthorityGenerationRepository generationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository outboxRepository =
        mock(AccountAuthorityOutboxRepository.class);
    AccountMembershipTransitionReceiptRepository receiptRepository =
        mock(AccountMembershipTransitionReceiptRepository.class);
    AccountTenantMembershipRepository membershipRepository =
        mock(AccountTenantMembershipRepository.class);
    AccountTenantMembershipRoleSnapshotRepository rolesRepository =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperationRepository,
            pairAuthorityRepository,
            accountRepository,
            tenantIdentityResolver,
            freshAssociationRepository,
            generationRepository,
            outboxRepository,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            receiptRepository,
            membershipRepository,
            rolesRepository,
            mock(
                net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository
                    .class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantRoleOperationRepository.class));
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(false);

    try {
      assertThatThrownBy(() -> producer.readCurrentPairBoundInactiveMembershipSnapshot(42L, 7L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      assertThatThrownBy(() -> producer.publishLeftMembershipChange(42L, 7L, "leave-request", null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      verifyNoInteractions(
          joinOperationRepository,
          pairAuthorityRepository,
          accountRepository,
          tenantIdentityResolver,
          freshAssociationRepository,
          generationRepository,
          outboxRepository,
          receiptRepository,
          membershipRepository,
          rolesRepository);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void freshSnapshotRequiresOwnerTransactionBeforeRepositoryReads() {
    AccountJoinOperationRepository joinOperationRepository =
        mock(AccountJoinOperationRepository.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository freshAssociationRepository =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperationRepository,
            mock(AccountMembershipPairAuthorityRepository.class),
            accountRepository,
            mock(AccountTenantIdentityResolver.class),
            freshAssociationRepository,
            mock(AccountAuthorityGenerationRepository.class),
            mock(AccountAuthorityOutboxRepository.class),
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountMembershipTransitionReceiptRepository.class),
            mock(AccountTenantMembershipRepository.class),
            mock(AccountTenantMembershipRoleSnapshotRepository.class),
            mock(
                net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository
                    .class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantRoleOperationRepository.class));

    assertThatThrownBy(
            () ->
                producer.readFreshNeverJoinedMembershipSnapshot(
                    UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(joinOperationRepository, accountRepository, freshAssociationRepository);
  }

  @Test
  void canonicalRuntimeSnapshotRequiresOwnerTransactionBeforeIdentityReads() {
    AccountJoinOperationRepository joinOperationRepository =
        mock(AccountJoinOperationRepository.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    AccountTenantIdentityResolver tenantIdentityResolver =
        mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshAssociationRepository =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperationRepository,
            mock(AccountMembershipPairAuthorityRepository.class),
            accountRepository,
            tenantIdentityResolver,
            freshAssociationRepository,
            mock(AccountAuthorityGenerationRepository.class),
            mock(AccountAuthorityOutboxRepository.class),
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountMembershipTransitionReceiptRepository.class),
            mock(AccountTenantMembershipRepository.class),
            mock(AccountTenantMembershipRoleSnapshotRepository.class),
            mock(
                net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository
                    .class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantRoleOperationRepository.class));

    assertThatThrownBy(
            () -> producer.readRuntimeMembershipSnapshot(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(
        joinOperationRepository,
        accountRepository,
        tenantIdentityResolver,
        freshAssociationRepository);
  }

  @Test
  void existingPositiveSnapshotRequiresOwnerTransactionBeforeIdentityReads() {
    AccountJoinOperationRepository joinOperationRepository =
        mock(AccountJoinOperationRepository.class);
    AccountMembershipPairAuthorityRepository pairAuthorityRepository =
        mock(AccountMembershipPairAuthorityRepository.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    AccountTenantIdentityResolver tenantIdentityResolver =
        mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshAssociationRepository =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountAuthorityGenerationRepository authorityGenerationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository authorityOutboxRepository =
        mock(AccountAuthorityOutboxRepository.class);
    AccountMembershipTransitionReceiptRepository transitionReceiptRepository =
        mock(AccountMembershipTransitionReceiptRepository.class);
    AccountTenantMembershipRepository membershipRepository =
        mock(AccountTenantMembershipRepository.class);
    AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperationRepository,
            pairAuthorityRepository,
            accountRepository,
            tenantIdentityResolver,
            freshAssociationRepository,
            authorityGenerationRepository,
            authorityOutboxRepository,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            transitionReceiptRepository,
            membershipRepository,
            roleSnapshotRepository,
            mock(
                net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository
                    .class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantRoleOperationRepository.class));

    assertThatThrownBy(
            () ->
                producer.readExistingPairBoundPositiveMembershipSnapshot(
                    UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(
        joinOperationRepository,
        pairAuthorityRepository,
        accountRepository,
        tenantIdentityResolver,
        freshAssociationRepository,
        authorityGenerationRepository,
        authorityOutboxRepository,
        transitionReceiptRepository,
        membershipRepository,
        roleSnapshotRepository);
  }

  @Test
  void existingRuntimeSnapshotRequiresOwnerTransactionBeforeIdentityReads() {
    AccountJoinOperationRepository joinOperationRepository =
        mock(AccountJoinOperationRepository.class);
    AccountMembershipPairAuthorityRepository pairAuthorityRepository =
        mock(AccountMembershipPairAuthorityRepository.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    AccountTenantIdentityResolver tenantIdentityResolver =
        mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshAssociationRepository =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountAuthorityGenerationRepository authorityGenerationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository authorityOutboxRepository =
        mock(AccountAuthorityOutboxRepository.class);
    AccountMembershipTransitionReceiptRepository transitionReceiptRepository =
        mock(AccountMembershipTransitionReceiptRepository.class);
    AccountTenantMembershipRepository membershipRepository =
        mock(AccountTenantMembershipRepository.class);
    AccountTenantMembershipRoleSnapshotRepository roleSnapshotRepository =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperationRepository,
            pairAuthorityRepository,
            accountRepository,
            tenantIdentityResolver,
            freshAssociationRepository,
            authorityGenerationRepository,
            authorityOutboxRepository,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            transitionReceiptRepository,
            membershipRepository,
            roleSnapshotRepository,
            mock(
                net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository
                    .class),
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountTenantRoleOperationRepository.class));

    assertThatThrownBy(
            () ->
                producer.readExistingRuntimeMembershipSnapshot(
                    UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(
        joinOperationRepository,
        pairAuthorityRepository,
        accountRepository,
        tenantIdentityResolver,
        freshAssociationRepository,
        authorityGenerationRepository,
        authorityOutboxRepository,
        transitionReceiptRepository,
        membershipRepository,
        roleSnapshotRepository);
  }
}
