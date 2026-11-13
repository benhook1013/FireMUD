package net.firedevops.firemud.accountservice.client;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.net.RequestOptions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads one exact Stripe hosting subscription and returns immutable provider evidence only.
 *
 * <p>This manually composed observer does not authorize a caller, locate a local subscription,
 * mutate Stripe, or apply an Account lifecycle transition. Its metadata contract is internal and
 * versioned: {@code firemud_metadata_schema_version=1}, {@code firemud_operation_type}, {@code
 * firemud_operation_id}, and {@code firemud_subscription_id}. Operation types are exactly {@code
 * subscription_provisioning} and {@code subscription_update}. Provider IDs and this metadata are
 * retained evidence, not substitutes for Account's durable operation and provider history mappings.
 */
public final class StripeHostingSubscriptionObserver {
  private static final String METADATA_SCHEMA_VERSION_KEY = "firemud_metadata_schema_version";
  private static final String METADATA_SCHEMA_VERSION = "1";
  private static final String METADATA_OPERATION_TYPE_KEY = "firemud_operation_type";
  private static final String METADATA_OPERATION_ID_KEY = "firemud_operation_id";
  private static final String METADATA_SUBSCRIPTION_ID_KEY = "firemud_subscription_id";

  private static final String SUBSCRIPTION_OBJECT = "subscription";
  private static final String SUBSCRIPTION_ITEM_OBJECT = "subscription_item";
  private static final String PRICE_OBJECT = "price";

  private static final List<String> SUPPORTED_PROVIDER_STATUSES =
      List.of(
          "active",
          "canceled",
          "incomplete",
          "incomplete_expired",
          "past_due",
          "paused",
          "trialing",
          "unpaid");

  private final com.stripe.StripeClient stripeClient;
  private final Clock clock;
  private final RequestOptions requestOptions;

  /**
   * Explicit transport limits are required; composition must keep them below the applicable
   * lifecycle policy's maximum observation age. Socket timeouts are not a wall-clock deadline: the
   * owner still rejects evidence whose read-start freshness bound has elapsed.
   */
  public StripeHostingSubscriptionObserver(
      com.stripe.StripeClient stripeClient,
      Clock clock,
      Duration connectTimeout,
      Duration readTimeout) {
    this.stripeClient = Objects.requireNonNull(stripeClient, "stripe client is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.requestOptions =
        RequestOptions.builder()
            .setConnectTimeout(timeoutMillis(connectTimeout))
            .setReadTimeout(timeoutMillis(readTimeout))
            .setMaxNetworkRetries(0)
            .build();
  }

  /**
   * Retrieves and validates the provider object named by the internal expected-binding tuple.
   *
   * <p>A provider read failure, including a provider ID that cannot be resolved, is returned as an
   * unresolved retryable result with its original typed Stripe exception. It is never represented
   * as provider absence. A successfully read but incomplete, unsupported, or contradictory object
   * is returned as a bounded invalid result.
   */
  public Result observe(ExpectedBinding expected) {
    Objects.requireNonNull(expected, "expected binding is required");
    Instant readStartedAt = clock.instant();
    Subscription subscription;
    try {
      subscription =
          stripeClient.subscriptions().retrieve(expected.providerSubscriptionId(), requestOptions);
    } catch (StripeException providerFailure) {
      return new UnresolvedObservation(
          expected.providerSubscriptionId(), readStartedAt, clock.instant(), providerFailure);
    }

    Instant observedAt = clock.instant();
    if (observedAt.isBefore(readStartedAt)) {
      return new InvalidObservation(
          expected.providerSubscriptionId(),
          readStartedAt,
          observedAt,
          InvalidReason.READ_INTERVAL_REVERSED);
    }
    try {
      return validate(subscription, expected, readStartedAt, observedAt);
    } catch (InvalidEvidence invalid) {
      return new InvalidObservation(
          expected.providerSubscriptionId(), readStartedAt, observedAt, invalid.reason);
    }
  }

  private static int timeoutMillis(Duration timeout) {
    Objects.requireNonNull(timeout, "explicit Stripe observation timeout is required");
    // Zero means unbounded to the SDK. Reject sub-millisecond and overflowing values rather
    // than truncating them into a different transport policy.
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0
        || timeout.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException(
          "Stripe observation timeouts must be positive whole milliseconds within the SDK range");
    }
    return (int) timeout.toMillis();
  }

  private static ObservedSubscription validate(
      Subscription subscription,
      ExpectedBinding expected,
      Instant readStartedAt,
      Instant observedAt) {
    require(subscription != null, InvalidReason.MISSING_SUBSCRIPTION_OBJECT);
    require(
        SUBSCRIPTION_OBJECT.equals(subscription.getObject()),
        InvalidReason.SUBSCRIPTION_OBJECT_MISMATCH);
    require(
        expected.providerSubscriptionId().equals(subscription.getId()),
        InvalidReason.SUBSCRIPTION_ID_MISMATCH);

    String customerId = subscription.getCustomer();
    require(expected.providerCustomerId().equals(customerId), InvalidReason.CUSTOMER_ID_MISMATCH);

    // Only the explicit subscription default_payment_method is evidence of the selected
    // instrument. Do not fall back to default_source, customer invoice settings, or a customer
    // default when Stripe omits this field.
    String paymentMethodId = subscription.getDefaultPaymentMethod();
    require(
        paymentMethodId != null && !paymentMethodId.isBlank(),
        InvalidReason.EXPLICIT_PAYMENT_METHOD_MISSING);
    require(
        expected.providerPaymentMethodId().equals(paymentMethodId),
        InvalidReason.PAYMENT_METHOD_ID_MISMATCH);

    Boolean livemode = subscription.getLivemode();
    require(livemode != null, InvalidReason.LIVEMODE_MISSING);
    require(livemode == expected.livemode(), InvalidReason.LIVEMODE_MISMATCH);

    String status = subscription.getStatus();
    require(
        status != null && SUPPORTED_PROVIDER_STATUSES.contains(status),
        InvalidReason.UNSUPPORTED_PROVIDER_STATUS);

    SubscriptionCorrelation correlation = parseCorrelation(subscription.getMetadata(), expected);
    SubscriptionItem item = requireSingleHostingItem(subscription, expected);
    Price price = item.getPrice();
    require(price != null, InvalidReason.PRICE_MISSING);
    require(PRICE_OBJECT.equals(price.getObject()), InvalidReason.PRICE_OBJECT_MISMATCH);
    require(expected.providerPriceId().equals(price.getId()), InvalidReason.PRICE_ID_MISMATCH);
    Long quantity = item.getQuantity();
    require(quantity != null, InvalidReason.ITEM_QUANTITY_MISSING);
    require(quantity > 0, InvalidReason.ITEM_QUANTITY_INVALID);
    require(quantity == expected.expectedQuantity(), InvalidReason.ITEM_QUANTITY_MISMATCH);

    boolean terminal = "canceled".equals(status) || "incomplete_expired".equals(status);
    Instant periodStart =
        providerInstant(item.getCurrentPeriodStart(), InvalidReason.PERIOD_INVALID);
    Instant periodEnd = providerInstant(item.getCurrentPeriodEnd(), InvalidReason.PERIOD_INVALID);
    if (!terminal) {
      require(periodStart != null && periodEnd != null, InvalidReason.PERIOD_MISSING);
    }
    if (periodStart != null && periodEnd != null) {
      require(periodEnd.isAfter(periodStart), InvalidReason.PERIOD_INVALID);
    }

    Boolean cancelAtPeriodEnd = subscription.getCancelAtPeriodEnd();
    require(cancelAtPeriodEnd != null, InvalidReason.CANCEL_METADATA_MISSING);
    Instant cancelAt =
        providerInstant(subscription.getCancelAt(), InvalidReason.CANCEL_TIMESTAMP_INVALID);
    Instant canceledAt =
        providerInstant(subscription.getCanceledAt(), InvalidReason.CANCEL_TIMESTAMP_INVALID);
    Instant endedAt =
        providerInstant(subscription.getEndedAt(), InvalidReason.CANCEL_TIMESTAMP_INVALID);

    Map<String, String> metadata = immutableMetadata(subscription.getMetadata());
    return new ObservedSubscription(
        subscription.getId(),
        customerId,
        paymentMethodId,
        item.getId(),
        price.getId(),
        quantity,
        periodStart,
        periodEnd,
        cancelAtPeriodEnd,
        cancelAt,
        canceledAt,
        endedAt,
        status,
        correlation,
        metadata,
        livemode,
        readStartedAt,
        observedAt);
  }

  private static SubscriptionItem requireSingleHostingItem(
      Subscription subscription, ExpectedBinding expected) {
    SubscriptionItemCollection items = subscription.getItems();
    require(items != null, InvalidReason.ITEMS_MISSING);
    require(Boolean.FALSE.equals(items.getHasMore()), InvalidReason.ITEMS_INCOMPLETE);
    List<SubscriptionItem> data = items.getData();
    if (data == null || data.size() != 1) {
      throw new InvalidEvidence(InvalidReason.ITEM_CARDINALITY_UNSUPPORTED);
    }
    SubscriptionItem item = data.get(0);
    require(item != null, InvalidReason.ITEM_MISSING);
    require(SUBSCRIPTION_ITEM_OBJECT.equals(item.getObject()), InvalidReason.ITEM_OBJECT_MISMATCH);
    require(item.getId() != null && !item.getId().isBlank(), InvalidReason.ITEM_ID_MISSING);
    require(
        expected.providerSubscriptionId().equals(item.getSubscription()),
        InvalidReason.ITEM_SUBSCRIPTION_MISMATCH);
    return item;
  }

  private static SubscriptionCorrelation parseCorrelation(
      Map<String, String> metadata, ExpectedBinding expected) {
    require(metadata != null, InvalidReason.METADATA_MISSING);
    require(
        METADATA_SCHEMA_VERSION.equals(metadata.get(METADATA_SCHEMA_VERSION_KEY)),
        InvalidReason.METADATA_SCHEMA_UNSUPPORTED);
    OperationType operationType =
        OperationType.fromProviderValue(metadata.get(METADATA_OPERATION_TYPE_KEY));
    require(operationType != null, InvalidReason.OPERATION_TYPE_INVALID);
    require(operationType == expected.operationType(), InvalidReason.OPERATION_TYPE_MISMATCH);

    UUID operationId = parseCanonicalNonNilUuid(metadata.get(METADATA_OPERATION_ID_KEY));
    UUID subscriptionId = parseCanonicalNonNilUuid(metadata.get(METADATA_SUBSCRIPTION_ID_KEY));
    if (operationId == null || subscriptionId == null) {
      throw new InvalidEvidence(InvalidReason.METADATA_UUID_INVALID);
    }
    require(operationId.equals(expected.operationId()), InvalidReason.OPERATION_ID_MISMATCH);
    require(
        subscriptionId.equals(expected.subscriptionId()),
        InvalidReason.SUBSCRIPTION_METADATA_ID_MISMATCH);
    return new SubscriptionCorrelation(operationType, operationId, subscriptionId);
  }

  private static Map<String, String> immutableMetadata(Map<String, String> metadata) {
    require(metadata != null, InvalidReason.METADATA_MISSING);
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      require(
          entry.getKey() != null && entry.getValue() != null, InvalidReason.METADATA_VALUE_INVALID);
    }
    return Map.copyOf(metadata);
  }

  private static UUID parseCanonicalNonNilUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value) && !isNil(parsed) ? parsed : null;
    } catch (IllegalArgumentException invalidUuid) {
      return null;
    }
  }

  private static Instant providerInstant(Long epochSeconds, InvalidReason invalidReason) {
    if (epochSeconds == null) {
      return null;
    }
    require(epochSeconds > 0, invalidReason);
    try {
      return Instant.ofEpochSecond(epochSeconds);
    } catch (DateTimeException | ArithmeticException invalidTimestamp) {
      throw new InvalidEvidence(invalidReason);
    }
  }

  private static void require(boolean condition, InvalidReason reason) {
    if (!condition) {
      throw new InvalidEvidence(reason);
    }
  }

  private static boolean isNil(UUID value) {
    return value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0;
  }

  public sealed interface Result
      permits ObservedSubscription, InvalidObservation, UnresolvedObservation {
    String providerSubscriptionId();

    Instant readStartedAt();

    Instant observedAt();
  }

  /** A complete exact-binding provider read; it remains evidence and grants no local authority. */
  public static final class ObservedSubscription implements Result {
    private final String providerSubscriptionId;
    private final String providerCustomerId;
    private final String providerPaymentMethodId;
    private final String providerSubscriptionItemId;
    private final String providerPriceId;
    private final long providerQuantity;
    private final Instant currentPeriodStart;
    private final Instant currentPeriodEnd;
    private final boolean cancelAtPeriodEnd;
    private final Instant cancelAt;
    private final Instant canceledAt;
    private final Instant endedAt;
    private final String providerStatus;
    private final SubscriptionCorrelation correlation;
    private final Map<String, String> providerMetadata;
    private final boolean livemode;
    private final Instant readStartedAt;
    private final Instant observedAt;

    private ObservedSubscription(
        String providerSubscriptionId,
        String providerCustomerId,
        String providerPaymentMethodId,
        String providerSubscriptionItemId,
        String providerPriceId,
        long providerQuantity,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant cancelAt,
        Instant canceledAt,
        Instant endedAt,
        String providerStatus,
        SubscriptionCorrelation correlation,
        Map<String, String> providerMetadata,
        boolean livemode,
        Instant readStartedAt,
        Instant observedAt) {
      this.providerSubscriptionId = providerSubscriptionId;
      this.providerCustomerId = providerCustomerId;
      this.providerPaymentMethodId = providerPaymentMethodId;
      this.providerSubscriptionItemId = providerSubscriptionItemId;
      this.providerPriceId = providerPriceId;
      this.providerQuantity = providerQuantity;
      this.currentPeriodStart = currentPeriodStart;
      this.currentPeriodEnd = currentPeriodEnd;
      this.cancelAtPeriodEnd = cancelAtPeriodEnd;
      this.cancelAt = cancelAt;
      this.canceledAt = canceledAt;
      this.endedAt = endedAt;
      this.providerStatus = providerStatus;
      this.correlation = correlation;
      this.providerMetadata = Map.copyOf(providerMetadata);
      this.livemode = livemode;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
    }

    @Override
    public String providerSubscriptionId() {
      return providerSubscriptionId;
    }

    public String providerCustomerId() {
      return providerCustomerId;
    }

    public String providerPaymentMethodId() {
      return providerPaymentMethodId;
    }

    public String providerSubscriptionItemId() {
      return providerSubscriptionItemId;
    }

    public String providerPriceId() {
      return providerPriceId;
    }

    public long providerQuantity() {
      return providerQuantity;
    }

    public Optional<Instant> currentPeriodStart() {
      return Optional.ofNullable(currentPeriodStart);
    }

    public Optional<Instant> currentPeriodEnd() {
      return Optional.ofNullable(currentPeriodEnd);
    }

    public boolean cancelAtPeriodEnd() {
      return cancelAtPeriodEnd;
    }

    public Optional<Instant> cancelAt() {
      return Optional.ofNullable(cancelAt);
    }

    public Optional<Instant> canceledAt() {
      return Optional.ofNullable(canceledAt);
    }

    public Optional<Instant> endedAt() {
      return Optional.ofNullable(endedAt);
    }

    /** The provider's raw lifecycle value; no FireMUD lifecycle transition is inferred. */
    public String providerStatus() {
      return providerStatus;
    }

    /** True only as a description of the raw provider value, not an Account terminal commit. */
    public boolean providerStatusIsCanceled() {
      return "canceled".equals(providerStatus);
    }

    /** True only for Stripe's distinct incomplete first-invoice expiry status. */
    public boolean providerStatusIsIncompleteExpired() {
      return "incomplete_expired".equals(providerStatus);
    }

    public SubscriptionCorrelation correlation() {
      return correlation;
    }

    public Map<String, String> providerMetadata() {
      return providerMetadata;
    }

    public boolean livemode() {
      return livemode;
    }

    @Override
    public Instant readStartedAt() {
      return readStartedAt;
    }

    @Override
    public Instant observedAt() {
      return observedAt;
    }

    public String source() {
      return "stripe.subscriptions.retrieve";
    }
  }

  /** A read completed, but its provider data did not satisfy the bounded hosting contract. */
  public static final class InvalidObservation implements Result {
    private final String providerSubscriptionId;
    private final Instant readStartedAt;
    private final Instant observedAt;
    private final InvalidReason reason;

    private InvalidObservation(
        String providerSubscriptionId,
        Instant readStartedAt,
        Instant observedAt,
        InvalidReason reason) {
      this.providerSubscriptionId = providerSubscriptionId;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
      this.reason = reason;
    }

    @Override
    public String providerSubscriptionId() {
      return providerSubscriptionId;
    }

    @Override
    public Instant readStartedAt() {
      return readStartedAt;
    }

    @Override
    public Instant observedAt() {
      return observedAt;
    }

    public InvalidReason reason() {
      return reason;
    }
  }

  /** A provider read did not establish current state and must remain retryable/unresolved. */
  public static final class UnresolvedObservation implements Result {
    private final String providerSubscriptionId;
    private final Instant readStartedAt;
    private final Instant observedAt;
    private final StripeException providerFailure;

    private UnresolvedObservation(
        String providerSubscriptionId,
        Instant readStartedAt,
        Instant observedAt,
        StripeException providerFailure) {
      this.providerSubscriptionId = providerSubscriptionId;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
      this.providerFailure = providerFailure;
    }

    @Override
    public String providerSubscriptionId() {
      return providerSubscriptionId;
    }

    @Override
    public Instant readStartedAt() {
      return readStartedAt;
    }

    @Override
    public Instant observedAt() {
      return observedAt;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Preserve the original typed provider failure for diagnostics; this result remains unresolved and cannot authorize a transition.")
    public StripeException providerFailure() {
      return providerFailure;
    }

    public boolean retryable() {
      return true;
    }
  }

  public enum InvalidReason {
    MISSING_SUBSCRIPTION_OBJECT,
    SUBSCRIPTION_OBJECT_MISMATCH,
    SUBSCRIPTION_ID_MISMATCH,
    CUSTOMER_ID_MISMATCH,
    EXPLICIT_PAYMENT_METHOD_MISSING,
    PAYMENT_METHOD_ID_MISMATCH,
    LIVEMODE_MISSING,
    LIVEMODE_MISMATCH,
    UNSUPPORTED_PROVIDER_STATUS,
    ITEMS_MISSING,
    ITEMS_INCOMPLETE,
    ITEM_CARDINALITY_UNSUPPORTED,
    ITEM_MISSING,
    ITEM_OBJECT_MISMATCH,
    ITEM_ID_MISSING,
    ITEM_SUBSCRIPTION_MISMATCH,
    PRICE_MISSING,
    PRICE_OBJECT_MISMATCH,
    PRICE_ID_MISMATCH,
    ITEM_QUANTITY_MISSING,
    ITEM_QUANTITY_INVALID,
    ITEM_QUANTITY_MISMATCH,
    PERIOD_MISSING,
    PERIOD_INVALID,
    READ_INTERVAL_REVERSED,
    CANCEL_METADATA_MISSING,
    CANCEL_TIMESTAMP_INVALID,
    METADATA_MISSING,
    METADATA_SCHEMA_UNSUPPORTED,
    METADATA_VALUE_INVALID,
    OPERATION_TYPE_INVALID,
    OPERATION_TYPE_MISMATCH,
    METADATA_UUID_INVALID,
    OPERATION_ID_MISMATCH,
    SUBSCRIPTION_METADATA_ID_MISMATCH
  }

  public enum OperationType {
    SUBSCRIPTION_PROVISIONING("subscription_provisioning"),
    SUBSCRIPTION_UPDATE("subscription_update");

    private final String providerValue;

    OperationType(String providerValue) {
      this.providerValue = providerValue;
    }

    public String providerValue() {
      return providerValue;
    }

    private static OperationType fromProviderValue(String value) {
      for (OperationType type : values()) {
        if (type.providerValue.equals(value)) {
          return type;
        }
      }
      return null;
    }
  }

  /** Typed operation metadata retained as provider evidence, separate from provider IDs. */
  public static final class SubscriptionCorrelation {
    private final OperationType operationType;
    private final UUID operationId;
    private final UUID subscriptionId;

    private SubscriptionCorrelation(
        OperationType operationType, UUID operationId, UUID subscriptionId) {
      this.operationType = operationType;
      this.operationId = operationId;
      this.subscriptionId = subscriptionId;
    }

    public OperationType operationType() {
      return operationType;
    }

    public UUID operationId() {
      return operationId;
    }

    public UUID subscriptionId() {
      return subscriptionId;
    }
  }

  /** Expected binding supplied by internal composition; it is data, not authorization. */
  public record ExpectedBinding(
      String providerSubscriptionId,
      UUID subscriptionId,
      String providerCustomerId,
      String providerPaymentMethodId,
      String providerPriceId,
      long expectedQuantity,
      OperationType operationType,
      UUID operationId,
      boolean livemode) {
    public ExpectedBinding {
      requireText(providerSubscriptionId, "provider subscription ID");
      requireNonNilUuid(subscriptionId, "internal subscription ID");
      requireText(providerCustomerId, "provider customer ID");
      requireText(providerPaymentMethodId, "provider payment-method ID");
      requireText(providerPriceId, "provider hosting price ID");
      if (expectedQuantity <= 0) {
        throw new IllegalArgumentException("expected subscription-item quantity must be positive");
      }
      Objects.requireNonNull(operationType, "operation type is required");
      requireNonNilUuid(operationId, "operation ID");
    }

    private static void requireText(String value, String label) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(label + " must not be blank");
      }
    }

    private static void requireNonNilUuid(UUID value, String label) {
      Objects.requireNonNull(value, label + " is required");
      if (isNil(value)) {
        throw new IllegalArgumentException(label + " must not be nil");
      }
    }
  }

  private static final class InvalidEvidence extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final InvalidReason reason;

    private InvalidEvidence(InvalidReason reason) {
      this.reason = reason;
    }
  }
}
