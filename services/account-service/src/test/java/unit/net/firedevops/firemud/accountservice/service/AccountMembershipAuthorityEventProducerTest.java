package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import org.junit.jupiter.api.Test;

class AccountMembershipAuthorityEventProducerTest {
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
            mock(AccountMembershipTransitionReceiptRepository.class),
            mock(AccountTenantMembershipRepository.class),
            mock(AccountTenantMembershipRoleSnapshotRepository.class));

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
            mock(AccountMembershipTransitionReceiptRepository.class),
            mock(AccountTenantMembershipRepository.class),
            mock(AccountTenantMembershipRoleSnapshotRepository.class));

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
}
