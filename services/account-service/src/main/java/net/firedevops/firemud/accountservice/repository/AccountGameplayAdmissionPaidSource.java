package net.firedevops.firemud.accountservice.repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReadFence;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit, unregistered paid-source composition for Account's original NEW lease owner.
 *
 * <p>Only the genuine hosting owner reads a current receipt. Every SQL phase constructs that owner
 * on the caller's exact enlisted DSLContext. The opaque preparation and observation retain their
 * originating composition; neither a public receipt record nor raw provider data is an input.
 * Provider observation runs only outside SQL. This component registers no route or provider write.
 */
public final class AccountGameplayAdmissionPaidSource {
  private final AccountHostingPlanCatalog plans;
  private final Clock clock;
  private final ReconciliationLimits limits;
  private final AccountHostingSubscriptionLifecyclePolicy policy;
  private final StripeHostingSubscriptionObserver observer;
  private final String namespace;
  private final boolean livemode;

  public AccountGameplayAdmissionPaidSource(
      AccountHostingPlanCatalog plans,
      Clock clock,
      ReconciliationLimits limits,
      AccountHostingSubscriptionLifecyclePolicy policy,
      StripeHostingSubscriptionObserver observer,
      String namespace,
      boolean livemode) {
    this.plans = Objects.requireNonNull(plans);
    this.clock = Objects.requireNonNull(clock);
    this.limits = Objects.requireNonNull(limits);
    this.policy = Objects.requireNonNull(policy);
    this.observer = Objects.requireNonNull(observer);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
    this.livemode = livemode;
  }

  Preparation prepare(DSLContext exactDsl, String expectedNamespace, UUID tenantId) {
    requireNamespace(expectedNamespace);
    return new Preparation(this, reader(exactDsl).prepareCurrentRead(tenantId));
  }

  Observation observe(Preparation preparation) {
    if (preparation == null
        || preparation.owner != this
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Paid NEW observation requires no ambient Account SQL");
    }
    return new Observation(preparation, observer.observe(preparation.fence.binding()));
  }

  AccountHostingSubscriptionCurrentReceipt readCurrent(
      DSLContext exactDsl, String expectedNamespace, Observation observation) {
    requireNamespace(expectedNamespace);
    if (observation == null || observation.preparation.owner != this) {
      throw new IllegalStateException("Original paid NEW source observation required");
    }
    var receipt = reader(exactDsl).readCurrent(observation.preparation.fence, observation.result);
    Instant now = clock.instant();
    if (receipt.evaluatedAt() == null
        || receipt.validUntil() == null
        || now.isBefore(receipt.evaluatedAt())
        || !now.isBefore(receipt.validUntil())) {
      throw new IllegalStateException("Current paid NEW source has expired");
    }
    return receipt;
  }

  private AccountHostingSubscriptionLifecycleRepository reader(DSLContext exactDsl) {
    return new AccountHostingSubscriptionLifecycleRepository(
        exactDsl,
        new AccountSubscriptionIntentRepository(exactDsl, plans),
        clock,
        limits,
        policy,
        namespace,
        livemode);
  }

  private void requireNamespace(String expectedNamespace) {
    if (!namespace.equals(expectedNamespace)) {
      throw new IllegalStateException("Exact Account paid-source namespace required");
    }
  }

  static final class Preparation {
    private final AccountGameplayAdmissionPaidSource owner;
    private final ReadFence fence;

    private Preparation(AccountGameplayAdmissionPaidSource owner, ReadFence fence) {
      this.owner = owner;
      this.fence = Objects.requireNonNull(fence);
    }
  }

  static final class Observation {
    private final Preparation preparation;
    private final StripeHostingSubscriptionObserver.Result result;

    private Observation(Preparation preparation, StripeHostingSubscriptionObserver.Result result) {
      this.preparation = preparation;
      this.result = Objects.requireNonNull(result);
    }
  }
}
