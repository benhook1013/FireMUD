package net.firedevops.firemud.accountservice.service;

import com.stripe.exception.StripeException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient.TerminalUnpaidObservation;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ObservedSubscription;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ClaimResult;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.TransitionResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Production backend composition, deliberately unregistered. Only actual Stripe SDK reads occur,
 * strictly outside SQL transactions. No provider create/update/cancel, public billing
 * authorization, runtime protocol bundle, bean, route, scheduler, or deployment activation is
 * supplied here.
 */
public final class AccountHostingSubscriptionLifecycleService {
  private final AccountHostingSubscriptionLifecycleRepository repository;
  private final StripeHostingSubscriptionObserver observer;
  private final StripeHostingFirstInvoicePaymentClient firstInvoice;
  private final TransactionTemplate transaction;
  private final TransactionTemplate unpaidCancellationTransaction;

  public AccountHostingSubscriptionLifecycleService(
      AccountHostingSubscriptionLifecycleRepository repository,
      StripeHostingSubscriptionObserver observer,
      PlatformTransactionManager transactions) {
    this(repository, observer, transactions, null);
  }

  public AccountHostingSubscriptionLifecycleService(
      AccountHostingSubscriptionLifecycleRepository repository,
      StripeHostingSubscriptionObserver observer,
      PlatformTransactionManager transactions,
      StripeHostingFirstInvoicePaymentClient firstInvoice) {
    this.repository = Objects.requireNonNull(repository);
    this.observer = Objects.requireNonNull(observer);
    this.firstInvoice = firstInvoice;
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    unpaidCancellationTransaction = new TransactionTemplate(transactions);
    unpaidCancellationTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    unpaidCancellationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public ClaimResult claimProvisioning(
      UUID creationRequestId, long expectedRowVersion, UUID attemptId) {
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        transaction.execute(
            s -> repository.claim(creationRequestId, expectedRowVersion, attemptId)));
  }

  /**
   * Provider ID is a locator only. The retained exact binding and actual SDK response decide
   * acceptance.
   */
  public TransitionResult reconcile(
      UUID requestId,
      UUID creationRequestId,
      long expectedRowVersion,
      String providerSubscriptionId,
      UUID provisioningAttemptId) {
    requireNoAmbientTransaction();
    var prepared =
        Objects.requireNonNull(
            transaction.execute(
                s ->
                    repository.prepare(
                        requestId,
                        creationRequestId,
                        expectedRowVersion,
                        providerSubscriptionId,
                        provisioningAttemptId)));
    if (prepared.outcome() != Outcome.READY) {
      return prepared.result();
    }
    requireNoAmbientTransaction();
    var actualSdkResult = observer.observe(prepared.binding());
    return Objects.requireNonNull(
        transaction.execute(s -> repository.apply(prepared, actualSdkResult)));
  }

  /** Read-only Stripe reconciliation of an externally canceled first invoice. */
  public TransitionResult reconcileUnpaidFirstInvoiceCancellation(
      UUID requestId,
      UUID creationRequestId,
      long expectedRowVersion,
      String providerSubscriptionId) {
    requireNoAmbientTransaction();
    var captured =
        Objects.requireNonNull(
            transaction.execute(
                s ->
                    repository.captureUnpaidFirstInvoiceCancellation(
                        requestId, creationRequestId, expectedRowVersion, providerSubscriptionId)));
    if (captured.replayResult() != null) return captured.replayResult();
    var observed = observer.observe(captured.binding());
    var invoice = captured.invoiceRequiringTerminalRead();
    TerminalUnpaidObservation unpaid = null;
    if (invoice != null
        && firstInvoice != null
        && observed instanceof ObservedSubscription subscription
        && subscription.providerStatusIsCanceled()) {
      try {
        unpaid = firstInvoice.observeTerminalUnpaid(invoice);
      } catch (StripeException | IllegalStateException unproven) {
        return new TransitionResult(
            requestId, Outcome.RECONCILING, 0, "UNPAID_CANCELLATION_UNCONFIRMED");
      }
    }
    TerminalUnpaidObservation retainedObservation = unpaid;
    return Objects.requireNonNull(
        unpaidCancellationTransaction.execute(
            s ->
                repository.applyUnpaidFirstInvoiceCancellation(
                    captured, observed, retainedObservation)));
  }

  /**
   * Fresh SDK facts cannot override the same locked committed receipt; discrepancies require
   * reconciliation.
   */
  public AccountHostingSubscriptionCurrentReceipt readCurrent(UUID tenantId) {
    return readCurrentAndCapture(tenantId, Function.identity());
  }

  /**
   * Captures Account-owned evidence while the final current-receipt locks remain held. The capture
   * must perform no network calls or independently committed reads. This is an internal composition
   * seam, not caller authentication or a registered runtime endpoint.
   */
  public <T> T readCurrentAndCapture(
      UUID tenantId, Function<AccountHostingSubscriptionCurrentReceipt, T> capture) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(capture, "Current receipt capture is required");
    try {
      var fence =
          Objects.requireNonNull(transaction.execute(s -> repository.prepareCurrentRead(tenantId)));
      requireNoAmbientTransaction();
      var actualSdkResult = observer.observe(fence.binding());
      return Objects.requireNonNull(
          transaction.execute(
              s ->
                  Objects.requireNonNull(
                      capture.apply(repository.readCurrent(fence, actualSdkResult)))));
    } catch (RuntimeException unavailable) {
      throw new EntitlementUnavailableException(unavailable);
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Hosting SDK composition refuses an ambient SQL transaction or synchronization");
    }
  }

  public static final class EntitlementUnavailableException extends IllegalStateException {
    public EntitlementUnavailableException(Throwable cause) {
      super("ENTITLEMENT_UNAVAILABLE: consistent hosting authority requires reconciliation", cause);
    }
  }
}
