package net.firedevops.firemud.accountservice.client;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeSearchResult;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionSearchParams;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Held initial hosting-subscription SDK adapter. Only a private, independently read durable owner
 * dispatch supplies its explicit customer, instrument, quote and initial-call permission. Existing
 * or pre-migration unknown dispatches reconcile only; no empty search authorizes recreation. No
 * actor authorization, update/cancel command, runtime registration or entitlement is supplied.
 */
public final class StripeHostingSubscriptionProvisioner {
  private static final long MAX_PROVIDER_SUBSCRIPTIONS_PER_CUSTOMER = 100L;

  private final com.stripe.StripeClient stripe;
  private final Clock clock;
  private final RequestOptions requestOptions;
  private final StripeHostingSubscriptionObserver observer;
  private final StripeBillingPrerequisiteObserver prerequisites;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected SDK/repository collaborators remain private; copying would break owner composition.")
  public StripeHostingSubscriptionProvisioner(
      com.stripe.StripeClient stripe,
      Clock clock,
      boolean expectedLivemode,
      Duration connectTimeout,
      Duration readTimeout) {
    this.stripe = Objects.requireNonNull(stripe);
    this.clock = Objects.requireNonNull(clock);
    observer = new StripeHostingSubscriptionObserver(stripe, clock, connectTimeout, readTimeout);
    // The observer validates positive whole-millisecond SDK limits before this conversion.
    requestOptions =
        RequestOptions.builder()
            .setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()))
            .setReadTimeout(Math.toIntExact(readTimeout.toMillis()))
            .setMaxNetworkRetries(0)
            .build();
    prerequisites = new StripeBillingPrerequisiteObserver(stripe, clock, expectedLivemode);
  }

  public Result provision(ProviderDispatch dispatch) {
    Objects.requireNonNull(dispatch);
    requireNoTransaction();
    if (!dispatch.readyForProviderIo()) {
      throw new IllegalStateException(
          "Committed independent provider-dispatch readback is required");
    }
    if (!live(dispatch)) {
      return new Pending("DISPATCH_LEASE_EXPIRED", null);
    }
    var creation = dispatch.claim().creation();
    // Local reservations do not prove that the provider's instrument is still attached.
    var prerequisite =
        prerequisites.observe(
            new StripeBillingPrerequisiteObserver.ExpectedBinding(
                creation.owner().accountUuid(),
                creation.customerProvisioningRequestId(),
                dispatch.customerProviderIdempotencyId(),
                creation.providerCustomerId(),
                dispatch.providerPaymentMethodId()),
            requestOptions);
    if (!(prerequisite instanceof StripeBillingPrerequisiteObserver.ObservedBillingPrerequisites)) {
      return new Pending("PROVIDER_PREREQUISITES_UNRESOLVED", null);
    }
    if (!live(dispatch)) {
      return new Pending("DISPATCH_LEASE_EXPIRED", null);
    }
    Map<String, String> metadata =
        Map.of(
            "firemud_metadata_schema_version",
            "1",
            "firemud_operation_type",
            "subscription_provisioning",
            "firemud_operation_id",
            creation.subscriptionProvisioningRequestId().toString(),
            "firemud_subscription_id",
            creation.subscriptionId().toString());
    try {
      String query =
          "metadata['firemud_metadata_schema_version']:'1' AND "
              + "metadata['firemud_operation_type']:'subscription_provisioning' AND "
              + "metadata['firemud_operation_id']:'"
              + creation.subscriptionProvisioningRequestId()
              + "' AND metadata['firemud_subscription_id']:'"
              + creation.subscriptionId()
              + "'";
      StripeSearchResult<Subscription> matches =
          stripe
              .subscriptions()
              .search(
                  SubscriptionSearchParams.builder().setQuery(query).setLimit(2L).build(),
                  requestOptions);
      if (matches == null
          || matches.getData() == null
          || !Boolean.FALSE.equals(matches.getHasMore())
          || matches.getData().size() > 1) {
        return new Pending("INCOMPLETE_OR_AMBIGUOUS_PROVIDER_SEARCH", null);
      }
      Subscription candidate;
      if (matches.getData().isEmpty()) {
        if (!dispatch.initialCallAllowed()) {
          if (!live(dispatch)) {
            return new Pending("DISPATCH_LEASE_EXPIRED", null);
          }
          var listed =
              stripe
                  .subscriptions()
                  .list(
                      SubscriptionListParams.builder()
                          .setCustomer(creation.providerCustomerId())
                          .setStatus(SubscriptionListParams.Status.ALL)
                          .setLimit(MAX_PROVIDER_SUBSCRIPTIONS_PER_CUSTOMER)
                          .build(),
                      requestOptions);
          if (listed == null
              || listed.getData() == null
              || !Boolean.FALSE.equals(listed.getHasMore())
              || listed.getData().size() > MAX_PROVIDER_SUBSCRIPTIONS_PER_CUSTOMER) {
            return new Pending("INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null);
          }
          candidate = null;
          for (Subscription listedCandidate : listed.getData()) {
            if (listedCandidate == null) {
              return new Pending("INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null);
            }
            if (metadata.equals(listedCandidate.getMetadata())) {
              if (!creation.providerCustomerId().equals(listedCandidate.getCustomer())) {
                return new Pending("INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null);
              }
              if (candidate != null) {
                return new Pending("INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null);
              }
              candidate = listedCandidate;
            }
          }
          if (candidate == null) {
            return new Pending("PROVIDER_LOOKUP_UNRESOLVED", null);
          }
        } else {
          if (!live(dispatch)) {
            return new Pending("DISPATCH_LEASE_EXPIRED", null);
          }
          candidate =
              stripe
                  .subscriptions()
                  .create(
                      SubscriptionCreateParams.builder()
                          .setCustomer(creation.providerCustomerId())
                          .setDefaultPaymentMethod(dispatch.providerPaymentMethodId())
                          .setCollectionMethod(
                              SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
                          .setPaymentBehavior(
                              SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                          .addItem(
                              SubscriptionCreateParams.Item.builder()
                                  .setPrice(creation.planQuote().providerPriceId())
                                  .setQuantity(creation.planQuote().providerQuantity())
                                  .build())
                          .putAllMetadata(metadata)
                          .build(),
                      requestOptions
                          .toBuilderFullCopy()
                          .setIdempotencyKey(
                              creation.subscriptionProvisioningRequestId().toString())
                          .build());
        }
      } else {
        candidate = matches.getData().getFirst();
      }
      // Candidate status is never authority. Exact identity and metadata only select readback.
      if (candidate == null
          || !"subscription".equals(candidate.getObject())
          || candidate.getId() == null
          || !candidate.getId().matches("sub_[A-Za-z0-9]{1,251}")
          || !metadata.equals(candidate.getMetadata())) {
        return new Pending("PROVIDER_CANDIDATE_BINDING_INVALID", null);
      }
      if (!live(dispatch)) {
        return new Pending("DISPATCH_LEASE_EXPIRED", null);
      }
      var observed = observer.observe(dispatch.expectedBinding(candidate.getId()));
      if (!(observed instanceof StripeHostingSubscriptionObserver.ObservedSubscription actual)) {
        return new Pending("INDEPENDENT_PROVIDER_READBACK_UNRESOLVED", null);
      }
      return new Located(actual);
    } catch (StripeException failure) {
      return new Pending("PROVIDER_OUTCOME_UNKNOWN", failure);
    }
  }

  private boolean live(ProviderDispatch dispatch) {
    var now = clock.instant();
    return !now.isBefore(dispatch.claim().claimedAt())
        && now.isBefore(dispatch.claim().expiresAt());
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Hosting provider IO refuses an ambient SQL transaction");
    }
  }

  public sealed interface Result permits Pending, Located {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The SDK exception is an internal diagnostic cause, never provider authority or a public response.")
  public record Pending(String reason, StripeException providerFailure) implements Result {}

  /** Locator plus genuine private observer evidence; lifecycle commit independently reads again. */
  public static final class Located implements Result {
    private final StripeHostingSubscriptionObserver.ObservedSubscription observation;

    private Located(StripeHostingSubscriptionObserver.ObservedSubscription observation) {
      this.observation = observation;
    }

    public StripeHostingSubscriptionObserver.ObservedSubscription observation() {
      return observation;
    }

    public String providerSubscriptionId() {
      return observation.providerSubscriptionId();
    }
  }
}
