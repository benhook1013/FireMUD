package net.firedevops.firemud.accountservice.service.session;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.Handoff;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePayment;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Held owner composition. Provider IO follows durable consent and independent current readback. */
public final class AccountHostingFirstInvoicePaymentService {
  private final AccountControlUiActorService actors;
  private final AccountRecentReauthenticationService reauthentication;
  private final AccountHostingFirstInvoicePaymentRepository payments;
  private final StripeEventInboxRepository inbox;
  private final StripeHostingFirstInvoicePaymentClient provider;
  private final StripeHostingWebhookVerifier verifier;
  private final AccountHostingSubscriptionLifecycleService lifecycle;
  private final TransactionTemplate transaction;

  public AccountHostingFirstInvoicePaymentService(
      AccountControlUiActorService actors,
      AccountRecentReauthenticationService reauthentication,
      AccountHostingFirstInvoicePaymentRepository payments,
      StripeEventInboxRepository inbox,
      StripeHostingFirstInvoicePaymentClient provider,
      StripeHostingWebhookVerifier verifier,
      AccountHostingSubscriptionLifecycleService lifecycle,
      PlatformTransactionManager transactions) {
    this.actors = Objects.requireNonNull(actors);
    this.reauthentication = Objects.requireNonNull(reauthentication);
    this.payments = Objects.requireNonNull(payments);
    this.inbox = Objects.requireNonNull(inbox);
    this.provider = Objects.requireNonNull(provider);
    this.verifier = Objects.requireNonNull(verifier);
    this.lifecycle = Objects.requireNonNull(lifecycle);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** The authenticated subject, not a body account field, selects billing ownership. */
  public AccountHostingFirstInvoicePayment readOwner(String bearer, UUID subscriptionId) {
    outsideSql();
    return actors.withCurrentAccount(
        bearer, actor -> payments.requireOwner(subscriptionId, actor.accountId()));
  }

  public OwnerView describeOwner(String bearer, UUID subscriptionId) {
    outsideSql();
    return actors.withCurrentAccount(
        bearer,
        actor -> {
          var invoice = payments.requireOwner(subscriptionId, actor.accountId());
          return new OwnerView(
              invoice.subscriptionId(),
              invoice.invoiceId(),
              invoice.paymentIntentId(),
              invoice.paymentInstrumentId(),
              Long.toString(invoice.amount()),
              invoice.currency(),
              invoice.digest(),
              payments.ownerRowVersion(subscriptionId, actor.accountId()),
              payments.hasCompleted(subscriptionId) ? "COMMITTED" : "AWAITING_PAYMENT",
              invoice.expiresAt(),
              payments.confirmationRequestId(subscriptionId),
              payments.authorizationVersion(subscriptionId));
        });
  }

  public UUID reauthenticate(
      String bearer,
      UUID subscriptionId,
      UUID reauthenticationRequestId,
      UUID confirmationRequestId,
      String email,
      String secret) {
    outsideSql();
    var invoice = readOwner(bearer, subscriptionId);
    var evidence =
        reauthentication.reauthenticate(
            new AccountRecentReauthenticationService.Request(
                reauthenticationRequestId,
                invoice.ownerAccountId(),
                AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
                confirmationRequestId,
                email,
                secret));
    return evidence.evidenceId();
  }

  /** Genuine reauthentication bound to one exact invoice and its intended authorization attempt. */
  public UUID reauthenticate(
      String bearer,
      UUID subscriptionId,
      UUID reauthenticationRequestId,
      UUID authorizationRequestId,
      UUID originalRequestId,
      long expectedRowVersion,
      long expectedAuthorizationVersion,
      String acceptedTupleDigest,
      java.time.Instant invoiceExpiresAt,
      AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose authorizationPurpose,
      String email,
      String secret) {
    outsideSql();
    var snapshot =
        actors.withCurrentAccount(
            bearer,
            actor -> {
              var invoice = payments.requireOwner(subscriptionId, actor.accountId());
              return new OwnerReauthenticationSnapshot(
                  actor.accountId(),
                  invoice,
                  payments.ownerRowVersion(subscriptionId, actor.accountId()));
            });
    if (expectedRowVersion != snapshot.rowVersion()
        || !Objects.equals(acceptedTupleDigest, snapshot.invoice().digest())
        || !Objects.equals(invoiceExpiresAt, snapshot.invoice().expiresAt())) {
      throw new IllegalStateException("Exact current first-invoice reauthentication binding refused");
    }
    var context =
        new AccountRecentReauthenticationEvidence.BillingContext(
            snapshot.accountId(),
            subscriptionId,
            originalRequestId,
            acceptedTupleDigest,
            expectedRowVersion,
            invoiceExpiresAt,
            authorizationRequestId,
            expectedAuthorizationVersion,
            authorizationPurpose);
    var evidence =
        reauthentication.reauthenticate(
            new AccountRecentReauthenticationService.Request(
                reauthenticationRequestId,
                snapshot.accountId(),
                AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
                authorizationRequestId,
                context,
                email,
                secret));
    return actors.withCurrentAccount(
        bearer,
        actor -> {
          var current =
              reauthentication.requireCurrentBillingEvidenceLocked(
                  evidence.evidenceId(), actor.accountId(), authorizationRequestId);
          if (!context.equals(current.billingContext())) {
            throw new IllegalStateException("Exact billing evidence context refused");
          }
          payments.bindReauthenticationContext(
              actor.accountId(), subscriptionId, context, current);
          return current.evidenceId();
        });
  }

  /**
   * Explicit consent names the immutable tuple shown to the owner; browser success grants nothing.
   */
  public Handoff confirm(
      String bearer,
      UUID subscriptionId,
      UUID requestId,
      long expectedRowVersion,
      UUID reauthenticationEvidenceId,
      String acceptedTupleDigest)
      throws StripeException {
    outsideSql();
    var prepared =
        actors.withCurrentAccount(
            bearer,
            actor -> {
              var evidence =
                  reauthentication.requireCurrentBillingEvidenceLocked(
                      reauthenticationEvidenceId, actor.accountId(), requestId);
              reauthentication.validateAndConsumeLocked(
                  evidence,
                  actor.accountId(),
                  AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
                  requestId);
              return payments.prepareConfirmation(
                  actor.accountId(),
                  requestId,
                  subscriptionId,
                  expectedRowVersion,
                  acceptedTupleDigest,
                  evidence);
            });
    return dispatch(bearer, prepared);
  }

  /** Explicit new primary proof and consent, fenced against any concurrent authorization. */
  public Handoff renew(
      String bearer,
      UUID subscriptionId,
      UUID requestId,
      UUID authorizationRequestId,
      long expectedRowVersion,
      long expectedAuthorizationVersion,
      UUID reauthenticationEvidenceId,
      String acceptedTupleDigest)
      throws StripeException {
    outsideSql();
    var prepared =
        actors.withCurrentAccount(
            bearer,
            actor -> {
              var evidence =
                  reauthentication.requireCurrentBillingEvidenceLocked(
                      reauthenticationEvidenceId, actor.accountId(), authorizationRequestId);
              reauthentication.validateAndConsumeLocked(
                  evidence,
                  actor.accountId(),
                  AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
                  authorizationRequestId);
              return payments.renewAuthorization(
                  actor.accountId(),
                  requestId,
                  authorizationRequestId,
                  subscriptionId,
                  expectedRowVersion,
                  expectedAuthorizationVersion,
                  acceptedTupleDigest,
                  evidence);
            });
    return dispatch(bearer, prepared);
  }

  private Handoff dispatch(
      String bearer, AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch prepared)
      throws StripeException {
    var dispatch =
        actors.withCurrentAccount(
            bearer,
            actor -> {
              reauthentication.requireCurrentBillingEvidenceLocked(
                  prepared.reauthenticationEvidenceId(),
                  actor.accountId(),
                  prepared.authorizationRequestId());
              return payments.readDispatch(prepared, actor.accountId());
            });
    outsideSql();
    return provider.confirm(
        dispatch,
        () ->
            actors.withCurrentAccount(
                bearer,
                actor -> {
                  reauthentication.requireCurrentBillingEvidenceLocked(
                      dispatch.reauthenticationEvidenceId(),
                      actor.accountId(),
                      dispatch.authorizationRequestId());
                  return payments.claimCommand(dispatch, actor.accountId());
                }));
  }

  /** Immutable intake commits separately. A failed fulfillment resumes this exact completion. */
  public Completion completeSigned(String payload, String signature)
      throws SignatureVerificationException, StripeException {
    outsideSql();
    var event = verifier.verify(payload, signature);
    transaction.executeWithoutResult(status -> inbox.retainOrReplay(event));
    var candidate =
        Objects.requireNonNull(
            transaction.execute(status -> payments.candidateForSignedEvent(event)));
    boolean alreadyCompleted =
        Boolean.TRUE.equals(
            transaction.execute(
                status ->
                    payments.completed(
                        candidate.invoice().subscriptionId(), candidate.lifecycleRequestId())));
    if (!alreadyCompleted) {
      var observed = provider.observe(candidate.invoice());
      transaction.executeWithoutResult(
          status -> {
            payments.retainSignedSuccess(event, observed);
          });
      var transition =
          lifecycle.reconcile(
              candidate.lifecycleRequestId(),
              candidate.invoice().creationRequestId(),
              candidate.expectedRowVersion(),
              candidate.invoice().providerSubscriptionId(),
              null);
      if (transition.outcome() != Outcome.COMMITTED)
        return new Completion("RECONCILING", candidate.requestId());
    }
    // A later cancellation/authority checkpoint cannot erase the immutable paid outcome.
    // Newly committed fulfillment additionally proves the independent current authority read.
    if (!alreadyCompleted) {
      var current = lifecycle.readCurrent(candidate.invoice().tenantId());
      if (!candidate.invoice().subscriptionId().equals(current.current().subscriptionId())
          || !candidate.lifecycleRequestId().equals(current.receiptRequestId())) {
        throw new IllegalStateException("Independent first-invoice authority readback refused");
      }
    }
    if (!Boolean.TRUE.equals(
        transaction.execute(
            status ->
                payments.completed(
                    candidate.invoice().subscriptionId(), candidate.lifecycleRequestId())))) {
      throw new IllegalStateException("Independent first-invoice fulfillment readback refused");
    }
    return new Completion("COMMITTED", candidate.requestId());
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("First-invoice checkout refuses ambient SQL");
    }
  }

  public record OwnerView(
      UUID subscriptionId,
      String invoiceId,
      String paymentIntentId,
      UUID paymentInstrumentId,
      String amount,
      String currency,
      String tupleDigest,
      long expectedRowVersion,
      String state,
      java.time.Instant expiresAt,
      UUID confirmationRequestId,
      long authorizationVersion) {}

  public record Completion(String state, UUID requestId) {}

  private record OwnerReauthenticationSnapshot(
      UUID accountId, AccountHostingFirstInvoicePayment invoice, long rowVersion) {}
}
