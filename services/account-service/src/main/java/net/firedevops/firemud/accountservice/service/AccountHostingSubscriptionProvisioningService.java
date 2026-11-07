package net.firedevops.firemud.accountservice.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionProvisioner;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Disposition;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.TransitionResult;
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
  private final AccountHostingSubscriptionLifecycleService lifecycle;
  private final TransactionTemplate transaction;

  public AccountHostingSubscriptionProvisioningService(
      AccountHostingSubscriptionLifecycleRepository repository,
      StripeHostingSubscriptionProvisioner provider,
      AccountHostingSubscriptionLifecycleService lifecycle,
      PlatformTransactionManager transactions) {
    this.repository = Objects.requireNonNull(repository);
    this.provider = Objects.requireNonNull(provider);
    this.lifecycle = Objects.requireNonNull(lifecycle);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Result provision(ProvisioningClaim claim, UUID dispatchId, UUID lifecycleRequestId) {
    Objects.requireNonNull(claim, "Existing genuine owner provisioning claim is required");
    requireUuid(dispatchId);
    requireUuid(lifecycleRequestId);
    requireNoAmbientTransaction();
    var prepared =
        Objects.requireNonNull(
            transaction.execute(status -> repository.prepareProviderDispatch(claim, dispatchId)));
    var dispatch =
        Objects.requireNonNull(
            transaction.execute(status -> repository.readProviderDispatch(prepared)));
    requireNoAmbientTransaction();
    var located = provider.provision(dispatch);
    if (!(located instanceof StripeHostingSubscriptionProvisioner.Located actual)) {
      Disposition disposition =
          Objects.requireNonNull(
              transaction.execute(status -> repository.deferUnresolved(dispatch.claim())));
      return new Pending(disposition, located);
    }
    // Locator and actual observation select the existing owner operation, never an ad-hoc write.
    TransitionResult transition =
        lifecycle.reconcile(
            lifecycleRequestId,
            claim.creation().requestId(),
            claim.subscriptionRowVersion(),
            actual.providerSubscriptionId(),
            claim.attemptId());
    if (transition.outcome() != Outcome.COMMITTED) {
      return new Reconciling(transition);
    }
    AccountHostingSubscriptionCurrentReceipt current =
        lifecycle.readCurrent(claim.creation().tenantId());
    if (!current.receiptRequestId().equals(lifecycleRequestId)
        || current.current().rowVersion() != transition.committedRowVersion()
        || !current.current().subscriptionId().equals(claim.creation().subscriptionId())
        || !current.current().creationRequestId().equals(claim.creation().requestId())
        || !actual.providerSubscriptionId().equals(current.current().providerSubscriptionId())
        || !current.current().planQuote().equals(claim.creation().planQuote())) {
      throw new IllegalStateException("Independent committed hosting receipt readback mismatch");
    }
    return new Committed(transition, current);
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

  public sealed interface Result permits Pending, Reconciling, Committed {}

  public record Pending(
      Disposition disposition, StripeHostingSubscriptionProvisioner.Result providerResult)
      implements Result {}

  public record Reconciling(TransitionResult transition) implements Result {}

  /** Privately constructed after actual lifecycle commit and independent current readback. */
  public static final class Committed implements Result {
    private final TransitionResult transition;
    private final AccountHostingSubscriptionCurrentReceipt current;

    private Committed(
        TransitionResult transition, AccountHostingSubscriptionCurrentReceipt current) {
      this.transition = transition;
      this.current = current;
    }

    public TransitionResult transition() {
      return transition;
    }

    public AccountHostingSubscriptionCurrentReceipt current() {
      return current;
    }
  }
}
