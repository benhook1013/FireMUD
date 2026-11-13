package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePayment;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.TransitionResult;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCurrentRecord;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingFirstInvoicePaymentServiceTest {
  private final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
  private final AccountRecentReauthenticationService reauthentication =
      mock(AccountRecentReauthenticationService.class);
  private final AccountHostingFirstInvoicePaymentRepository payments =
      mock(AccountHostingFirstInvoicePaymentRepository.class);
  private final StripeEventInboxRepository inbox = mock(StripeEventInboxRepository.class);
  private final StripeHostingFirstInvoicePaymentClient provider =
      mock(StripeHostingFirstInvoicePaymentClient.class);
  private final StripeHostingWebhookVerifier verifier = mock(StripeHostingWebhookVerifier.class);
  private final AccountHostingSubscriptionLifecycleService lifecycle =
      mock(AccountHostingSubscriptionLifecycleService.class);
  private final AccountHostingFirstInvoicePaymentService service =
      new AccountHostingFirstInvoicePaymentService(
          actors,
          reauthentication,
          payments,
          inbox,
          provider,
          verifier,
          lifecycle,
          new LocalTransactions());

  @AfterEach
  void clearAmbient() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void
      delayedSignedSuccessAfterAuthorizationExpiryOrAuthorityAdvanceIsReadOnlyAndAtomicallyReadBack()
          throws Exception {
    var candidate = signedCandidate();
    var invoice = candidate.invoice();
    UUID lifecycleRequestId = candidate.lifecycleRequestId();
    long expectedVersion = candidate.expectedRowVersion();
    when(candidate.validUntil()).thenReturn(Instant.EPOCH);
    var event = mock(StripeHostingWebhookVerifier.VerifiedEvent.class);
    when(verifier.verify("signed", "header")).thenReturn(event);
    when(payments.candidateForSignedEvent(event)).thenReturn(candidate);
    when(payments.completed(invoice.subscriptionId(), lifecycleRequestId))
        .thenReturn(false, true, true, true);
    var observed = mock(StripeHostingFirstInvoicePaymentClient.Observation.class);
    when(provider.observe(invoice))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return observed;
            });
    when(payments.retainSignedOutcome(event, observed))
        .thenReturn(
            new AccountHostingFirstInvoicePaymentRepository.SignedOutcome(candidate, false));
    var committed = new TransitionResult(lifecycleRequestId, Outcome.COMMITTED, 5, null);
    when(lifecycle.reconcile(
            lifecycleRequestId,
            invoice.creationRequestId(),
            expectedVersion,
            invoice.providerSubscriptionId(),
            null))
        .thenReturn(committed);
    var receipt = mock(AccountHostingSubscriptionCurrentReceipt.class);
    var current = mock(AccountSubscriptionCurrentRecord.class);
    UUID subscriptionId = invoice.subscriptionId();
    when(current.subscriptionId()).thenReturn(subscriptionId);
    when(receipt.current()).thenReturn(current);
    when(receipt.receiptRequestId()).thenReturn(lifecycleRequestId);
    when(lifecycle.readCurrent(invoice.tenantId())).thenReturn(receipt);

    assertThat(service.completeSigned("signed", "header").state()).isEqualTo("COMMITTED");
    assertThat(service.completeSigned("signed", "header").state()).isEqualTo("COMMITTED");
    verify(lifecycle, times(1)).readCurrent(invoice.tenantId());
    verify(provider, times(1)).observe(invoice);
    verify(provider, times(0)).confirm(any(), any());
    verify(payments, times(1)).retainSignedOutcome(event, observed);
    verifyNoInteractions(actors, reauthentication);
  }

  @Test
  void paidTerminalReconciliationReturnsOperatorState() throws Exception {
    var candidate = signedCandidate();
    var invoice = candidate.invoice();
    UUID lifecycleRequestId = candidate.lifecycleRequestId();
    var event = mock(StripeHostingWebhookVerifier.VerifiedEvent.class);
    var observed = mock(StripeHostingFirstInvoicePaymentClient.Observation.class);
    when(verifier.verify("signed", "header")).thenReturn(event);
    when(payments.candidateForSignedEvent(event)).thenReturn(candidate);
    when(provider.observe(invoice)).thenReturn(observed);
    when(payments.retainSignedOutcome(event, observed))
        .thenReturn(
            new AccountHostingFirstInvoicePaymentRepository.SignedOutcome(candidate, false));
    when(lifecycle.reconcile(
            lifecycleRequestId,
            invoice.creationRequestId(),
            candidate.expectedRowVersion(),
            invoice.providerSubscriptionId(),
            null))
        .thenReturn(new TransitionResult(lifecycleRequestId, Outcome.TERMINAL_FAILURE, 0, null));

    assertThat(service.completeSigned("signed", "header"))
        .isEqualTo(
            new AccountHostingFirstInvoicePaymentService.Completion(
                "OPERATOR_RECONCILIATION_REQUIRED", candidate.requestId()));
    verify(lifecycle, times(0)).readCurrent(invoice.tenantId());
  }

  @Test
  void exactLatePaidConflictReplayNeverReadsProviderOrChangesLifecycle() throws Exception {
    var event = mock(StripeHostingWebhookVerifier.VerifiedEvent.class);
    UUID requestId = UUID.randomUUID();
    when(verifier.verify("signed", "header")).thenReturn(event);
    when(payments.retainedSignedConflictRequestId(event)).thenReturn(requestId);

    assertThat(service.completeSigned("signed", "header"))
        .isEqualTo(
            new AccountHostingFirstInvoicePaymentService.Completion(
                "OPERATOR_RECONCILIATION_REQUIRED", requestId));

    verify(inbox).retainOrReplay(event);
    verify(payments).retainedSignedConflictRequestId(event);
    verifyNoInteractions(provider, lifecycle, actors, reauthentication);
  }

  @Test
  void unrelatedSignedEventIsRetainedWithoutDispatchOrLifecycleWork() throws Exception {
    var event = mock(StripeHostingWebhookVerifier.VerifiedEvent.class);
    when(verifier.verify("signed", "header")).thenReturn(event);

    assertThat(service.completeSigned("signed", "header"))
        .isEqualTo(new AccountHostingFirstInvoicePaymentService.Completion("IGNORED", null));

    verify(inbox).retainOrReplay(event);
    verify(payments).candidateForSignedEvent(event);
    verifyNoInteractions(provider, lifecycle, actors, reauthentication);
  }

  @Test
  void alreadyCommittedPaymentReplaySurvivesLaterLifecycleAdvanceWithoutNewProviderRead()
      throws Exception {
    var candidate = signedCandidate();
    var invoice = candidate.invoice();
    UUID lifecycleRequestId = candidate.lifecycleRequestId();
    var event = mock(StripeHostingWebhookVerifier.VerifiedEvent.class);
    when(verifier.verify("signed", "header")).thenReturn(event);
    when(payments.candidateForSignedEvent(event)).thenReturn(candidate);
    when(payments.completed(invoice.subscriptionId(), lifecycleRequestId)).thenReturn(true);
    assertThat(service.completeSigned("signed", "header").state()).isEqualTo("COMMITTED");
    verifyNoInteractions(provider, lifecycle, actors, reauthentication);
    verify(payments, times(2)).completed(invoice.subscriptionId(), lifecycleRequestId);
  }

  @Test
  void ambientSqlAndForgedSignatureNeverReachProviderOrCompletion() throws Exception {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.completeSigned("forged", "header"))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    when(verifier.verify("forged", "header"))
        .thenThrow(new IllegalArgumentException("signature refused"));
    assertThatThrownBy(() -> service.completeSigned("forged", "header"))
        .hasMessageContaining("signature refused");
    verifyNoInteractions(provider, inbox, payments, lifecycle);
  }

  @Test
  void renewedProofIsConsumedForItsOwnIdentityAndClaimRechecksCurrentOwnerAfterProviderReads()
      throws Exception {
    UUID account = UUID.randomUUID(),
        original = UUID.randomUUID(),
        authorization = UUID.randomUUID(),
        subscription = UUID.randomUUID(),
        evidenceId = UUID.randomUUID();
    var actor = mock(AccountControlUiActorService.AccountAuthenticatedActor.class);
    when(actor.accountId()).thenReturn(account);
    when(actors.withCurrentAccount(eq("bearer"), any()))
        .thenAnswer(
            call -> {
              java.util.function.Function<
                      AccountControlUiActorService.AccountAuthenticatedActor, Object>
                  action = call.getArgument(1);
              return action.apply(actor);
            });
    var proof = mock(AccountRecentReauthenticationEvidence.class);
    when(proof.billingContext())
        .thenReturn(mock(AccountRecentReauthenticationEvidence.BillingContext.class));
    when(reauthentication.requireCurrentBillingEvidenceLocked(evidenceId, account, authorization))
        .thenReturn(proof);
    var prepared = mock(ConfirmationDispatch.class);
    when(prepared.reauthenticationEvidenceId()).thenReturn(evidenceId);
    when(prepared.authorizationRequestId()).thenReturn(authorization);
    var dispatch = mock(ConfirmationDispatch.class);
    when(dispatch.reauthenticationEvidenceId()).thenReturn(evidenceId);
    when(dispatch.authorizationRequestId()).thenReturn(authorization);
    when(payments.renewAuthorization(
            account, original, authorization, subscription, 3, 1, "tuple", proof))
        .thenReturn(prepared);
    when(payments.readDispatch(prepared, account)).thenReturn(dispatch);
    when(payments.claimCommand(dispatch, account)).thenReturn(null);
    when(provider.confirm(eq(dispatch), any()))
        .thenAnswer(
            call -> {
              java.util.function.Supplier<
                      net.firedevops.firemud.accountservice.repository
                          .AccountHostingFirstInvoicePaymentRepository.CommandClaim>
                  claim = call.getArgument(1);
              assertThat(claim.get()).isNull();
              return null;
            });
    service.renew("bearer", subscription, original, authorization, 3, 1, evidenceId, "tuple");
    verify(reauthentication)
        .validateAndConsumeLocked(
            proof, account, AccountRecentReauthenticationPolicy.BILLING_PURPOSE, authorization);
    verify(reauthentication, times(3))
        .requireCurrentBillingEvidenceLocked(evidenceId, account, authorization);
    verify(payments).claimCommand(dispatch, account);
    verifyNoInteractions(inbox, lifecycle, verifier);
  }

  private static ConfirmationDispatch signedCandidate() {
    var invoice =
        new AccountHostingFirstInvoicePayment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "sub_exact",
            "cus_exact",
            "pm_exact",
            "in_exact",
            "pi_exact",
            2400,
            "usd",
            "sha256:" + "a".repeat(64),
            "price_exact",
            1,
            1,
            "sha256:" + "b".repeat(64),
            false,
            Instant.parse("2026-11-01T00:00:00Z"),
            Instant.parse("2026-11-01T23:00:00Z"));
    var candidate = mock(ConfirmationDispatch.class);
    when(candidate.invoice()).thenReturn(invoice);
    when(candidate.requestId()).thenReturn(UUID.randomUUID());
    when(candidate.lifecycleRequestId()).thenReturn(UUID.randomUUID());
    when(candidate.expectedRowVersion()).thenReturn(3L);
    return candidate;
  }

  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
