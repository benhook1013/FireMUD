package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stripe.exception.StripeException;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionProvisioner;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePayment;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Disposition;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingSubscriptionProvisioningServiceTest {
  private final AccountHostingSubscriptionLifecycleRepository repository =
      mock(AccountHostingSubscriptionLifecycleRepository.class);
  private final StripeHostingSubscriptionProvisioner provider =
      mock(StripeHostingSubscriptionProvisioner.class);
  private final StripeHostingFirstInvoicePaymentClient firstInvoiceProvider =
      mock(StripeHostingFirstInvoicePaymentClient.class);
  private final AccountHostingFirstInvoicePaymentRepository firstInvoices =
      mock(AccountHostingFirstInvoicePaymentRepository.class);
  private final LocalTransactions transactions = new LocalTransactions();
  private final AccountHostingSubscriptionProvisioningService service =
      new AccountHostingSubscriptionProvisioningService(
          repository, provider, firstInvoiceProvider, firstInvoices, transactions);
  private final ProvisioningClaim claim = mock(ProvisioningClaim.class);

  @AfterEach
  void clearAmbient() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentCaptureRecoversOnlyExactRetainedWinner(boolean retainedWinner) throws Exception {
    ProviderDispatch dispatch = mock(ProviderDispatch.class);
    when(dispatch.claim()).thenReturn(claim);
    when(repository.prepareProviderDispatch(any(), any())).thenReturn(dispatch);
    when(repository.readProviderDispatch(dispatch)).thenReturn(dispatch);
    var located = mock(StripeHostingSubscriptionProvisioner.Located.class);
    when(located.providerSubscriptionId()).thenReturn("sub_original");
    when(provider.provision(dispatch)).thenReturn(located);
    var observed = mock(StripeHostingFirstInvoicePaymentClient.Observation.class);
    when(firstInvoiceProvider.capture(dispatch, "sub_original")).thenReturn(observed);
    var failure = new FenceRejectedException("concurrent capture advanced version");
    when(firstInvoices.retainAwaitingPayment(dispatch, "sub_original", observed))
        .thenThrow(failure);
    var winner = mock(AccountHostingFirstInvoicePayment.class);
    when(firstInvoices.findAwaitingPayment(claim)).thenReturn(null, retainedWinner ? winner : null);
    if (retainedWinner) {
      assertThat(service.provision(claim, UUID.randomUUID()))
          .isEqualTo(new AccountHostingSubscriptionProvisioningService.AwaitingPayment(winner));
    } else {
      assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID())).isSameAs(failure);
    }
    verify(provider).provision(dispatch);
    verify(firstInvoiceProvider).capture(dispatch, "sub_original");
    verify(firstInvoices, times(2)).findAwaitingPayment(claim);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void onlyExpectedProviderCaptureRefusalDefersTheOriginalClaim(boolean evidenceRefused)
      throws Exception {
    var dispatch = mock(ProviderDispatch.class);
    when(dispatch.claim()).thenReturn(claim);
    when(repository.prepareProviderDispatch(any(), any())).thenReturn(dispatch);
    when(repository.readProviderDispatch(dispatch)).thenReturn(dispatch);
    var located = mock(StripeHostingSubscriptionProvisioner.Located.class);
    when(located.providerSubscriptionId()).thenReturn("sub_original");
    when(provider.provision(dispatch)).thenReturn(located);
    var refusal =
        evidenceRefused
            ? new StripeHostingFirstInvoicePaymentClient.ProviderEvidenceRefused()
            : new IllegalStateException("unexpected programming error");
    when(firstInvoiceProvider.capture(dispatch, "sub_original")).thenThrow(refusal);
    if (evidenceRefused) {
      when(repository.deferUnresolved(claim)).thenReturn(Disposition.WAITING);
      assertThat(service.provision(claim, UUID.randomUUID()))
          .isEqualTo(
              new AccountHostingSubscriptionProvisioningService.Pending(
                  Disposition.WAITING,
                  new StripeHostingSubscriptionProvisioner.Pending(
                      "FIRST_INVOICE_UNRESOLVED", null)));
      verify(repository).deferUnresolved(claim);
    } else {
      assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID())).isSameAs(refusal);
      verify(repository, never()).deferUnresolved(any());
    }
    verify(provider).provision(dispatch);
    verify(firstInvoiceProvider).capture(dispatch, "sub_original");
    verify(firstInvoices, never()).retainAwaitingPayment(any(), any(), any());
  }

  @Test
  void claimCommitAndIndependentReadbackPrecedeProviderAndUnknownOutcomeDefersWithoutLifecycle() {
    ProviderDispatch prepared = mock(ProviderDispatch.class);
    ProviderDispatch readback = mock(ProviderDispatch.class);
    when(readback.claim()).thenReturn(claim);
    when(repository.prepareProviderDispatch(any(), any()))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          return prepared;
        });
    when(repository.readProviderDispatch(prepared))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          assertThat(transactions.commits).isEqualTo(2);
          return readback;
        });
    when(provider.provision(readback))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
          assertThat(transactions.commits).isEqualTo(3);
          return new StripeHostingSubscriptionProvisioner.Pending("UNKNOWN", null);
        });
    when(repository.deferUnresolved(claim))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          return Disposition.WAITING;
        });
    var result =
        (AccountHostingSubscriptionProvisioningService.Pending)
            service.provision(claim, UUID.randomUUID());
    assertThat(result.disposition()).isEqualTo(Disposition.WAITING);
    assertThat(transactions.commits).isEqualTo(4);
    verify(firstInvoices).findAwaitingPayment(claim);
    verifyNoMoreInteractions(firstInvoices);
    verifyNoInteractions(firstInvoiceProvider);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DISPATCH_LEASE_EXPIRED", "PROVIDER_OUTCOME_UNKNOWN"})
  void expiredAttemptReturnsTypedPendingWithoutStartingLifecycle(String providerReason) {
    ProviderDispatch prepared = mock(ProviderDispatch.class);
    ProviderDispatch readback = mock(ProviderDispatch.class);
    when(readback.claim()).thenReturn(claim);
    when(repository.prepareProviderDispatch(any(), any())).thenReturn(prepared);
    when(repository.readProviderDispatch(prepared)).thenReturn(readback);
    var providerResult = new StripeHostingSubscriptionProvisioner.Pending(providerReason, null);
    when(provider.provision(readback)).thenReturn(providerResult);
    when(repository.deferUnresolved(claim))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          return Disposition.ATTEMPT_EXPIRED;
        });

    assertThat(service.provision(claim, UUID.randomUUID()))
        .isEqualTo(
            new AccountHostingSubscriptionProvisioningService.Pending(
                Disposition.ATTEMPT_EXPIRED, providerResult));
    assertThat(transactions.commits).isEqualTo(4);
    verify(firstInvoices).findAwaitingPayment(claim);
    verifyNoMoreInteractions(firstInvoices);
    verifyNoInteractions(firstInvoiceProvider);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusedDispatchCommitsLocalDeferralWithoutCallingProvider(boolean readbackRefused) {
    ProviderDispatch prepared = mock(ProviderDispatch.class);
    if (readbackRefused) {
      when(repository.prepareProviderDispatch(any(), any())).thenReturn(prepared);
      when(repository.readProviderDispatch(prepared))
          .thenThrow(new FenceRejectedException("wallet observation advanced"));
    } else {
      when(repository.prepareProviderDispatch(any(), any()))
          .thenThrow(new FenceRejectedException("wallet observation advanced"));
    }
    when(repository.deferUnresolved(claim))
        .thenAnswer(call -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          return Disposition.TERMINAL_FAILURE;
        });

    assertThat(service.provision(claim, UUID.randomUUID()))
        .isEqualTo(
            new AccountHostingSubscriptionProvisioningService.Pending(
                Disposition.TERMINAL_FAILURE,
                new StripeHostingSubscriptionProvisioner.Pending("DISPATCH_FENCE_REJECTED", null)));
    assertThat(transactions.commits).isEqualTo(readbackRefused ? 4 : 3);
    verify(repository).deferUnresolved(claim);
    verify(firstInvoices, times(2)).findAwaitingPayment(claim);
    verifyNoMoreInteractions(firstInvoices);
    verifyNoInteractions(provider, firstInvoiceProvider);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void fencedDeferralReturnsInvoiceRetainedByTheSameClaim(boolean captureFailure) throws Exception {
    var winner = mock(AccountHostingFirstInvoicePayment.class);
    var refusal = new FenceRejectedException("sibling retained invoice before deferral");
    when(repository.deferUnresolved(claim)).thenThrow(refusal);
    if (captureFailure) {
      var dispatch = mock(ProviderDispatch.class);
      var located = mock(StripeHostingSubscriptionProvisioner.Located.class);
      when(dispatch.claim()).thenReturn(claim);
      when(located.providerSubscriptionId()).thenReturn("sub_original");
      when(repository.prepareProviderDispatch(any(), any())).thenReturn(dispatch);
      when(repository.readProviderDispatch(dispatch)).thenReturn(dispatch);
      when(provider.provision(dispatch)).thenReturn(located);
      when(firstInvoiceProvider.capture(dispatch, "sub_original"))
          .thenThrow(mock(StripeException.class));
      when(firstInvoices.findAwaitingPayment(claim)).thenReturn(null, winner);
    } else {
      when(repository.prepareProviderDispatch(any(), any()))
          .thenThrow(new FenceRejectedException("dispatch refused"));
      when(firstInvoices.findAwaitingPayment(claim)).thenReturn(null, null, winner);
    }

    assertThat(service.provision(claim, UUID.randomUUID()))
        .isEqualTo(new AccountHostingSubscriptionProvisioningService.AwaitingPayment(winner));
    verify(repository).deferUnresolved(claim);
    verify(firstInvoices, times(captureFailure ? 2 : 3)).findAwaitingPayment(claim);
    verify(firstInvoices, never()).retainAwaitingPayment(any(), any(), any());
    if (captureFailure) {
      verify(provider).provision(any());
      verify(firstInvoiceProvider).capture(any(), any());
    } else {
      verifyNoInteractions(provider, firstInvoiceProvider);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusedDispatchCannotHideStaleClaimSettlementFailure(boolean replacementRetained) {
    var stale = new FenceRejectedException("stale retained attempt");
    when(repository.prepareProviderDispatch(any(), any()))
        .thenThrow(new FenceRejectedException("dispatch refused"));
    when(repository.deferUnresolved(claim)).thenThrow(stale);
    if (replacementRetained) {
      when(firstInvoices.findAwaitingPayment(claim))
          .thenReturn(null)
          .thenReturn(null)
          .thenThrow(new FenceRejectedException("invoice belongs to a replacement claim"));
    }

    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID())).isSameAs(stale);
    assertThat(transactions.commits).isEqualTo(replacementRetained ? 2 : 3);
    verify(firstInvoices, times(3)).findAwaitingPayment(claim);
    verifyNoMoreInteractions(firstInvoices);
    verifyNoInteractions(provider, firstInvoiceProvider);
  }

  @Test
  void failedIndependentReadbackNeverCallsProvider() {
    ProviderDispatch prepared = mock(ProviderDispatch.class);
    when(repository.prepareProviderDispatch(any(), any())).thenReturn(prepared);
    when(repository.readProviderDispatch(prepared))
        .thenThrow(new IllegalStateException("readback rejected"));
    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID()))
        .hasMessageContaining("readback rejected");
    assertThat(transactions.commits).isEqualTo(2);
    verify(firstInvoices).findAwaitingPayment(claim);
    verifyNoMoreInteractions(firstInvoices);
    verifyNoInteractions(provider, firstInvoiceProvider);
  }

  @Test
  void ambientTransactionAndSynchronizationRefuseBeforeAnyEffects() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID()))
        .hasMessageContaining("ambient SQL");
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.provision(claim, UUID.randomUUID()))
        .hasMessageContaining("ambient SQL");
    verifyNoInteractions(repository, provider, firstInvoiceProvider, firstInvoices);
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
