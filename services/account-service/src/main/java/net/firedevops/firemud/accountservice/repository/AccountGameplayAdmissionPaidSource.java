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
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit, unregistered paid-source composition for Account's original NEW lease owner.
 *
 * <p>Only the genuine hosting owner reads a current receipt. Every SQL phase constructs that owner
 * on the caller's exact enlisted DSLContext. The opaque preparation and observation retain their
 * originating composition; neither a public receipt record nor raw provider data is an input.
 * Provider observation runs only outside SQL. This component registers no route or provider write.
 */
public final class AccountGameplayAdmissionPaidSource {
  private static final JsonMapper BILLING_HINT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final String BILLING_SCHEMA_VERSION = "account-hosting-subscription-event/v1";
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

  /**
   * Unlocked routing hint from the exact current tenant source head. The paid lifecycle owner locks
   * its sources first, then the locked selector validates the complete current envelope.
   */
  boolean hasCurrentPaidSourceCandidate(
      DSLContext exactDsl, String expectedNamespace, UUID tenantId) {
    requireNamespace(expectedNamespace);
    Record source =
        exactDsl.fetchOne(
            "SELECT e.payload "
                + "FROM account_tenant_authority_source_records h "
                + "JOIN account_tenant_entitlement_outbox_events e "
                + "ON e.tenant_uuid = h.tenant_uuid "
                + "AND e.tenant_billing_sequence = h.tenant_billing_sequence "
                + "AND e.request_id = h.last_request_id "
                + "AND e.event_id = h.tenant_billing_event_id "
                + "AND e.event_digest = h.tenant_billing_event_digest "
                + "WHERE h.tenant_uuid = ?",
            tenantId);
    if (source == null) return false;
    byte[] payload = source.get("payload", byte[].class);
    if (payload == null || payload.length == 0 || payload.length > 32768) return false;
    try {
      JsonNode envelope = BILLING_HINT_JSON.readTree(payload);
      return envelope != null
          && BILLING_SCHEMA_VERSION.equals(envelope.path("schemaVersion").asText());
    } catch (RuntimeException unreadableHint) {
      return false;
    }
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
