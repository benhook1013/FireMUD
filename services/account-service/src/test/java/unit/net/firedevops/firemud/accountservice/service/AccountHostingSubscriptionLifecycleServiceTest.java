package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingSubscriptionLifecycleServiceTest {
  private final AccountHostingSubscriptionLifecycleRepository repository =
      mock(AccountHostingSubscriptionLifecycleRepository.class);
  private final StripeHostingSubscriptionObserver observer =
      mock(StripeHostingSubscriptionObserver.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountHostingSubscriptionLifecycleService service =
      new AccountHostingSubscriptionLifecycleService(repository, observer, transactions);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void ambientTransactionCannotSpanSdkReadOrImplicitlyCommitClaims() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertRefusal();
    verifyNoInteractions(repository, observer, transactions);
  }

  @Test
  void ambientSynchronizationAlsoRefusesBeforeAnyProviderOrSqlCall() {
    TransactionSynchronizationManager.initSynchronization();
    assertRefusal();
    verifyNoInteractions(repository, observer, transactions);
  }

  private void assertRefusal() {
    assertThatThrownBy(() -> service.claimProvisioning(UUID.randomUUID(), 1, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient SQL transaction");
    assertThatThrownBy(
            () ->
                service.reconcile(
                    UUID.randomUUID(), UUID.randomUUID(), 2, "sub_controlled", UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient SQL transaction");
    assertThatThrownBy(() -> service.readCurrent(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient SQL transaction");
  }
}
