package net.firedevops.firemud.accountservice.service;

import java.util.Objects;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Held owner composition for an existing genuine private provisioning claim. This does not admit
 * CreateSubscription callers or register any bean/route/worker. The authenticated create producer,
 * production custody and deployment remain held. Dispatch commits and independent owner readback
 * precede provider IO; provider output cannot directly write status or confer entitlement. Existing
 * lifecycle reconciliation performs its own SDK read, fenced commit, then independent current read.
 */
public final class AccountHostingSubscriptionProvisioningService {
  private final AccountHostingSubscriptionLifecycleRepository repository;
  private final StripeHostingSubscriptionProvisioner provider;
  private final StripeHostingFirstInvoicePaymentClient firstInvoiceProvider;
  private final AccountHostingFirstInvoicePaymentRepository firstInvoices;
  private final TransactionTemplate transaction;

  public AccountHostingSubscriptionProvisioningService(
      AccountHostingSubscriptionLifecycleRepository repository,
      StripeHostingSubscriptionProvisioner provider,
      StripeHostingFirstInvoicePaymentClient firstInvoiceProvider,
      AccountHostingFirstInvoicePaymentRepository firstInvoices,
      PlatformTransactionManager transactions) {
    this.repository = Objects.requireNonNull(repository);
    this.provider = Objects.requireNonNull(provider);
    this.firstInvoiceProvider = Objects.requireNonNull(firstInvoiceProvider);
    this.firstInvoices = Objects.requireNonNull(firstInvoices);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Result provision(ProvisioningClaim claim, UUID dispatchId) {
    Objects.requireNonNull(claim, "Existing genuine owner provisioning claim is required");
    requireUuid(dispatchId);
    requireNoAmbientTransaction();
    // retainAwaitingPayment may commit before its independent invoice readback or caller response
    // is lost. Recover that exact retained outcome before attempting to claim or contact Stripe.
    var replay = transaction.execute(status -> firstInvoices.findAwaitingPayment(claim));
    if (replay != null) return new AwaitingPayment(replay);
    ProviderDispatch dispatch;
    try {
      var prepared =
          Objects.requireNonNull(
              transaction.execute(status -> repository.prepareProviderDispatch(claim, dispatchId)));
      dispatch =
          Objects.requireNonNull(
              transaction.execute(status -> repository.readProviderDispatch(prepared)));
    } catch (FenceRejectedException refused) {
      // Retention may have committed after the first read but before dispatch was fenced.
      replay = transaction.execute(status -> firstInvoices.findAwaitingPayment(claim));
      if (replay != null) return new AwaitingPayment(replay);
      // Dispatch remains strictly fenced. A new local transaction may only settle the same retained
      // work; stale/current-owner/reservation mismatches still reject there before any write.
      return deferUnresolved(
          claim, new StripeHostingSubscriptionProvisioner.Pending("DISPATCH_FENCE_REJECTED", null));
    }
    requireNoAmbientTransaction();
    var located = provider.provision(dispatch);
    if (!(located instanceof StripeHostingSubscriptionProvisioner.Located actual)) {
      return deferUnresolved(dispatch.claim(), located);
    }
    // DEFAULT_INCOMPLETE only locates an existing invoice. Provisioning never confirms or grants.
    StripeHostingFirstInvoicePaymentClient.Observation observed;
    try {
      observed = firstInvoiceProvider.capture(dispatch, actual.providerSubscriptionId());
    } catch (StripeHostingFirstInvoicePaymentClient.ProviderEvidenceRefused unresolved) {
      return deferFirstInvoice(dispatch, null);
    } catch (com.stripe.exception.StripeException unresolved) {
      return deferFirstInvoice(dispatch, unresolved);
    }

    try {
      var retained =
          Objects.requireNonNull(
              transaction.execute(status ->
                  firstInvoices.retainAwaitingPayment(
                      dispatch, actual.providerSubscriptionId(), observed)));
      var readback =
          transaction.execute(status -> firstInvoices.findAwaitingPayment(dispatch.claim()));
      if (!retained.equals(readback))
        throw new IllegalStateException("Independent awaiting-payment readback mismatch");
      return new AwaitingPayment(readback);
    } catch (FenceRejectedException refused) {
      return recoverRetainedInvoice(dispatch.claim(), refused);
    }
  }

  private Result deferFirstInvoice(
      ProviderDispatch dispatch, com.stripe.exception.StripeException providerFailure) {
    return deferUnresolved(
        dispatch.claim(),
        new StripeHostingSubscriptionProvisioner.Pending(
            "FIRST_INVOICE_UNRESOLVED", providerFailure));
  }

  private Result deferUnresolved(
      ProvisioningClaim claim, StripeHostingSubscriptionProvisioner.Result providerResult) {
    try {
      var disposition =
          Objects.requireNonNull(transaction.execute(status -> repository.deferUnresolved(claim)));
      return new Pending(disposition, providerResult);
    } catch (FenceRejectedException refused) {
      return recoverRetainedInvoice(claim, refused);
    }
  }

  private AwaitingPayment recoverRetainedInvoice(
      ProvisioningClaim claim, FenceRejectedException refused) {
    // A sibling may have retained the invoice after our previous read. Read the exact claim in a
    // fresh transaction after the rejected transaction has ended.
    AccountHostingFirstInvoicePayment winner;
    try {
      winner = transaction.execute(status -> firstInvoices.findAwaitingPayment(claim));
    } catch (FenceRejectedException mismatch) {
      if (refused != mismatch) refused.addSuppressed(mismatch);
      throw refused;
    }
    if (winner == null) throw refused;
    return new AwaitingPayment(winner);
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil hosting operation identity is required");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Hosting provisioner refuses an ambient SQL transaction");
    }
  }

  public sealed interface Result permits Pending, AwaitingPayment {}

  public record Pending(
      Disposition disposition, StripeHostingSubscriptionProvisioner.Result providerResult)
      implements Result {}

  /** Provider linkage is non-authorizing and still reserves the exact selected instrument. */
  public record AwaitingPayment(AccountHostingFirstInvoicePayment invoice) implements Result {}
}
