package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipTransitionReceiptRepositoryTest {
  @Test
  void appendRequiresAnActualAccountTransactionBeforeRepositoryAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipTransitionReceiptRepository repository =
        new AccountMembershipTransitionReceiptRepository(dsl);
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(false);

    try {
      assertThatThrownBy(
              () ->
                  repository.appendTransition(
                      membership("ACTIVE", true), "MEMBERSHIP_JOINED", "join-request"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void historicalRequestLookupRequiresAnActualAccountTransactionBeforeRepositoryAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipTransitionReceiptRepository repository =
        new AccountMembershipTransitionReceiptRepository(dsl);
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(false);

    try {
      assertThatThrownBy(() -> repository.findByRequestId("leave-request"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active owner transaction");
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void appendRejectsLeftReceiptForActiveMembershipBeforeAdvancingReceiptHead() {
    DSLContext dsl = mock(DSLContext.class);
    AccountMembershipTransitionReceiptRepository repository =
        new AccountMembershipTransitionReceiptRepository(dsl);
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(
              () ->
                  repository.appendTransition(
                      membership("ACTIVE", true), "MEMBERSHIP_LEFT", "leave-request"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("inactive non-admitting state");
      verifyNoInteractions(dsl);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  private static AccountTenantMembership membership(
      String lifecycleState, boolean gameplayAdmissionAllowed) {
    Account account = new Account();
    account.setId(42L);

    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(18L);
    membership.setAccount(account);
    membership.setTenantId(7L);
    membership.setLifecycleState(lifecycleState);
    membership.setGameplayAdmissionAllowed(gameplayAdmissionAllowed);
    membership.setMembershipVersion(4L);
    membership.setMembershipAuthorityGeneration(5L);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    return membership;
  }
}
