package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionProvisioner;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Disposition;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingSubscriptionProvisioningServiceTest {
  private final AccountHostingSubscriptionLifecycleRepository repository =
      mock(AccountHostingSubscriptionLifecycleRepository.class);
  private final StripeHostingSubscriptionProvisioner provider =
      mock(StripeHostingSubscriptionProvisioner.class);
  private final AccountHostingSubscriptionLifecycleService lifecycle =
      mock(AccountHostingSubscriptionLifecycleService.class);
  private final LocalTransactions transactions = new LocalTransactions();
  private final AccountHostingSubscriptionProvisioningService service =
      new AccountHostingSubscriptionProvisioningService(
          repository, provider, lifecycle, transactions);
  private final ProvisioningClaim claim = mock(ProvisioningClaim.class);

  @AfterEach
  void clearAmbient() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void claimCommitAndIndependentReadbackPrecedeProviderAndUnknownOutcomeDefersWithoutLifecycle() {
    ProviderDispatch prepared = mock(ProviderDispatch.class);
    ProviderDispatch readback = mock(ProviderDispatch.class);
    when(readback.claim()).thenReturn(claim);
    when(repository.prepareProviderDispatch(any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return prepared;
            });
    when(repository.readProviderDispatch(prepared))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(transactions.commits).isEqualTo(1);
              return readback;
            });
    when(provider.provision(readback))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              assertThat(transactions.commits).isEqualTo(2);
              return new StripeHostingSubscriptionProvisioner.Pending("UNKNOWN", null);
            });
    when(repository.deferUnresolved(claim))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return Disposition.WAITING;
            });
    var result =
        (AccountHostingSubscriptionProvisioningService.Pending)
            service.provision(claim, UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.disposition()).isEqualTo(Disposition.WAITING);
    assertThat(transactions.commits).isEqualTo(3);
    verifyNoInteractions(lifecycle);
  }

  @Test
  void failedIndependentReadbackNeverCallsProvider() {
    ProviderDispatch prepared = mock(ProviderDispatch.class);
    when(repository.prepareProviderDispatch(any(), any())).thenReturn(prepared);
    when(repository.readProviderDispatch(prepared))
        .thenThrow(new IllegalStateException("readback rejected"));
    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID(), UUID.randomUUID()))
        .hasMessageContaining("readback rejected");
    assertThat(transactions.commits).isEqualTo(1);
    verifyNoInteractions(provider, lifecycle);
  }

  @Test
  void ambientTransactionAndSynchronizationRefuseBeforeAnyEffects() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID(), UUID.randomUUID()))
        .hasMessageContaining("ambient SQL");
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID(), UUID.randomUUID()))
        .hasMessageContaining("ambient SQL");
    verifyNoInteractions(repository, provider, lifecycle);
  }

  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
