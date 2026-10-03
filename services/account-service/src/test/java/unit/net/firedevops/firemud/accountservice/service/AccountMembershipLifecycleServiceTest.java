package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipTransitionReceiptRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class AccountMembershipLifecycleServiceTest {
  @Test
  void invalidStableRequestIdIsRejectedBeforeTransactionOrOwnerReads() {
    AccountJoinOperationRepository joinOperations = mock(AccountJoinOperationRepository.class);
    AccountRepository accounts = mock(AccountRepository.class);
    AccountTenantIdentityResolver tenantIdentity = mock(AccountTenantIdentityResolver.class);
    AccountTenantMembershipRepository memberships = mock(AccountTenantMembershipRepository.class);
    AccountTenantMembershipRoleSnapshotRepository roles =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    AccountMembershipTransitionReceiptRepository receipts =
        mock(AccountMembershipTransitionReceiptRepository.class);
    AccountAuthorityOutboxRepository authorityOutbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuditOutboxRepository auditOutbox = mock(AccountAuditOutboxRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    AccountMembershipLifecycleService service =
        new AccountMembershipLifecycleService(
            joinOperations,
            accounts,
            tenantIdentity,
            memberships,
            roles,
            receipts,
            authorityOutbox,
            auditOutbox,
            producer,
            transactions);

    assertThatThrownBy(() -> service.leave(42L, 7L, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Stable Account membership request ID");
    assertThatThrownBy(() -> service.leave(42L, 7L, "r".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Stable Account membership request ID");

    verifyNoInteractions(
        joinOperations,
        accounts,
        tenantIdentity,
        memberships,
        roles,
        receipts,
        authorityOutbox,
        auditOutbox,
        producer,
        transactions);
  }

  @Test
  void membershipVersionOverflowLeavesMembershipAndRoleRepositoriesUnchanged() {
    AccountJoinOperationRepository joinOperations = mock(AccountJoinOperationRepository.class);
    AccountRepository accounts = mock(AccountRepository.class);
    AccountTenantIdentityResolver tenantIdentity = mock(AccountTenantIdentityResolver.class);
    AccountTenantMembershipRepository memberships = mock(AccountTenantMembershipRepository.class);
    AccountTenantMembershipRoleSnapshotRepository roles =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    AccountMembershipTransitionReceiptRepository receipts =
        mock(AccountMembershipTransitionReceiptRepository.class);
    AccountAuthorityOutboxRepository authorityOutbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuditOutboxRepository auditOutbox = mock(AccountAuditOutboxRepository.class);
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);

    long accountId = 42L;
    long legacyTenantId = 7L;
    UUID accountUuid = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID tenantUuid = UUID.fromString("22222222-2222-4222-8222-222222222222");
    String requestId = "leave-overflow";
    Account account = new Account();
    account.setId(accountId);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(accountId);
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(tenantIdentity.resolve(legacyTenantId))
        .thenReturn(
            new ApprovedAssociation(
                legacyTenantId,
                tenantUuid,
                "legacy-tenant",
                100L,
                "sha256:" + "a".repeat(64),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "sha256:" + "b".repeat(64),
                "signature",
                "account-service-test",
                "key",
                "reviewer",
                "approval",
                "2026-01-01T00:00:00Z",
                1,
                1));
    when(receipts.findByRequestId(requestId)).thenReturn(Optional.empty());
    when(joinOperations.find(requestId)).thenReturn(Optional.empty());
    when(authorityOutbox.findEvent(
            "account:auth-authority:v1:membership/" + accountUuid + "/" + tenantUuid, requestId))
        .thenReturn(Optional.empty());
    when(auditOutbox.findMembershipLeftEnvelopeForUpdate(
            any(UUID.class), org.mockito.ArgumentMatchers.eq(legacyTenantId)))
        .thenReturn(Optional.empty());

    AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot active =
        mock(AccountMembershipAuthorityEventProducer.PositiveMembershipSnapshot.class);
    when(active.accountId()).thenReturn(accountUuid.toString());
    when(active.tenantId()).thenReturn(tenantUuid.toString());
    when(active.membershipExists()).thenReturn(true);
    when(active.membershipLifecycleState()).thenReturn("ACTIVE");
    when(active.gameplayAdmissionAllowed()).thenReturn(true);
    when(active.membershipVersion())
        .thenReturn(Map.of(tenantUuid.toString(), Long.toString(Long.MAX_VALUE)));
    when(active.membershipAuthorityGeneration()).thenReturn("1");
    when(active.roles()).thenReturn(List.of("player"));
    when(producer.readCurrentPairBoundPositiveMembershipSnapshot(accountId, legacyTenantId))
        .thenReturn(active);
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(18L);
    membership.setAccount(account);
    membership.setTenantId(legacyTenantId);
    membership.setLifecycleState("ACTIVE");
    membership.setGameplayAdmissionAllowed(true);
    membership.setMembershipVersion(Long.MAX_VALUE);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    when(memberships.findByAccountIdAndTenantId(accountId, legacyTenantId))
        .thenReturn(Optional.of(membership));
    when(roles.findForUpdate(accountId, legacyTenantId, 18L, Long.MAX_VALUE))
        .thenReturn(
            Optional.of(
                new RoleSnapshot(
                    accountId, legacyTenantId, 18L, Long.MAX_VALUE, List.of("player"))));

    AccountMembershipLifecycleService service =
        new AccountMembershipLifecycleService(
            joinOperations,
            accounts,
            tenantIdentity,
            memberships,
            roles,
            receipts,
            authorityOutbox,
            auditOutbox,
            producer,
            transactions);

    assertThatThrownBy(() -> service.leave(accountId, legacyTenantId, requestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("membership version is exhausted");

    assertThat(membership.getLifecycleState()).isEqualTo("ACTIVE");
    assertThat(membership.isGameplayAdmissionAllowed()).isTrue();
    assertThat(membership.getMembershipVersion()).isEqualTo(Long.MAX_VALUE);
    verify(memberships, never()).saveAndFlush(any(AccountTenantMembership.class));
    verify(roles, never()).replace(any(AccountTenantMembership.class), anyLong(), any());
    verify(transactions).rollback(transactionStatus);
  }
}
