package net.firedevops.firemud.accountservice.client;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;

/**
 * Reads one Stripe Customer and one explicitly selected PaymentMethod as bounded evidence only.
 *
 * <p>The internal metadata grammar is exactly four non-secret string entries: {@code
 * firemud_metadata_schema_version=1}, {@code firemud_operation_type=customer_provisioning}, {@code
 * firemud_operation_id=<canonical non-nil customer_provisioning_request_id>}, and {@code
 * firemud_account_id=<canonical non-nil Account UUID>}. Extra keys, alternate operation types,
 * subscription-operation IDs, noncanonical UUIDs, and missing values are rejected. The Customer ID
 * supplied to Stripe is only a lookup hint; the retrieved object and its metadata must match this
 * exact Account/customer operation. The selected PaymentMethod is separately retrieved and must
 * name that retrieved Customer in its own attachment field.
 *
 * <p>This observer performs only SDK retrievals. It does not search, create, attach, detach, or set
 * defaults; it does not inspect Customer defaults or persist/log provider payloads. Its immutable
 * positive result can only be constructed after both successful SDK reads pass validation. This is
 * provider-prerequisite evidence, not actor authorization, recent reauthentication, SetupIntent or
 * payment collection, subscription lifecycle, entitlement, or public-API authority. Expected Stripe
 * mode is fixed by the internal observer composition, not selected by a request.
 */
public final class StripeBillingPrerequisiteObserver {
  private static final String CUSTOMER_OBJECT = "customer";
  private static final String PAYMENT_METHOD_OBJECT = "payment_method";
  private static final String CARD_TYPE = "card";
  private static final String SCHEMA_VERSION_KEY = "firemud_metadata_schema_version";
  private static final String SCHEMA_VERSION = "1";
  private static final String OPERATION_TYPE_KEY = "firemud_operation_type";
  private static final String CUSTOMER_OPERATION_TYPE = "customer_provisioning";
  private static final String OPERATION_ID_KEY = "firemud_operation_id";
  private static final String ACCOUNT_ID_KEY = "firemud_account_id";
  private static final Set<String> CUSTOMER_METADATA_KEYS =
      Set.of(SCHEMA_VERSION_KEY, OPERATION_TYPE_KEY, OPERATION_ID_KEY, ACCOUNT_ID_KEY);
  private static final Set<String> SUPPORTED_CARD_BRANDS =
      Set.of(
          "amex",
          "cartes_bancaires",
          "diners",
          "discover",
          "eftpos_au",
          "jcb",
          "link",
          "mastercard",
          "unionpay",
          "unknown",
          "visa");

  private final com.stripe.StripeClient stripeClient;
  private final Clock clock;
  private final boolean expectedLivemode;

  public StripeBillingPrerequisiteObserver(
      com.stripe.StripeClient stripeClient, Clock clock, boolean expectedLivemode) {
    this.stripeClient = Objects.requireNonNull(stripeClient, "Stripe client is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.expectedLivemode = expectedLivemode;
  }

  /**
   * Retrieves the supplied ID hints using Stripe's typed SDK APIs. A Stripe exception (including a
   * not-found response) remains unresolved with its original cause; it never proves absence.
   */
  public Result observe(ExpectedBinding expected) {
    return observe(expected, RequestOptions.getDefault());
  }

  // The held hosting provisioner supplies its validated finite transport policy for both reads.
  Result observe(ExpectedBinding expected, RequestOptions requestOptions) {
    Objects.requireNonNull(expected, "expected binding is required");
    Objects.requireNonNull(requestOptions, "request options are required");
    Instant readStartedAt = clock.instant();
    Customer customer;
    try {
      customer = stripeClient.customers().retrieve(expected.providerCustomerId(), requestOptions);
    } catch (StripeException providerFailure) {
      return unresolved(expected, readStartedAt, clock.instant(), providerFailure);
    }

    Instant customerReadEndedAt = clock.instant();
    if (customerReadEndedAt.isBefore(readStartedAt)) {
      return invalid(
          expected, readStartedAt, customerReadEndedAt, InvalidReason.READ_INTERVAL_REVERSED);
    }

    CustomerBinding customerBinding;
    try {
      customerBinding = validateCustomer(customer, expected, expectedLivemode);
    } catch (InvalidEvidence invalid) {
      return invalid(expected, readStartedAt, customerReadEndedAt, invalid.reason);
    }

    PaymentMethod paymentMethod;
    try {
      paymentMethod =
          stripeClient
              .paymentMethods()
              .retrieve(expected.providerPaymentMethodId(), requestOptions);
    } catch (StripeException providerFailure) {
      return unresolved(expected, readStartedAt, clock.instant(), providerFailure);
    }

    Instant observedAt = clock.instant();
    if (observedAt.isBefore(customerReadEndedAt) || observedAt.isBefore(readStartedAt)) {
      return invalid(expected, readStartedAt, observedAt, InvalidReason.READ_INTERVAL_REVERSED);
    }
    try {
      return validatePaymentMethod(
          paymentMethod, customerBinding, expected, expectedLivemode, readStartedAt, observedAt);
    } catch (InvalidEvidence invalid) {
      return invalid(expected, readStartedAt, observedAt, invalid.reason);
    }
  }

  private static CustomerBinding validateCustomer(
      Customer customer, ExpectedBinding expected, boolean expectedLivemode) {
    if (customer == null) {
      throw new InvalidEvidence(InvalidReason.MISSING_CUSTOMER_OBJECT);
    }
    require(CUSTOMER_OBJECT.equals(customer.getObject()), InvalidReason.CUSTOMER_OBJECT_MISMATCH);
    require(
        expected.providerCustomerId().equals(customer.getId()), InvalidReason.CUSTOMER_ID_MISMATCH);
    require(!Boolean.TRUE.equals(customer.getDeleted()), InvalidReason.CUSTOMER_DELETED);

    Boolean livemode = customer.getLivemode();
    if (livemode == null) {
      throw new InvalidEvidence(InvalidReason.CUSTOMER_LIVEMODE_MISSING);
    }
    require(livemode == expectedLivemode, InvalidReason.CUSTOMER_LIVEMODE_MISMATCH);

    Map<String, String> metadata = customer.getMetadata();
    if (metadata == null) {
      throw new InvalidEvidence(InvalidReason.CUSTOMER_METADATA_MISSING);
    }
    require(
        metadata.keySet().equals(CUSTOMER_METADATA_KEYS),
        InvalidReason.CUSTOMER_METADATA_GRAMMAR_MISMATCH);
    require(
        SCHEMA_VERSION.equals(metadata.get(SCHEMA_VERSION_KEY)),
        InvalidReason.CUSTOMER_METADATA_SCHEMA_UNSUPPORTED);
    require(
        CUSTOMER_OPERATION_TYPE.equals(metadata.get(OPERATION_TYPE_KEY)),
        InvalidReason.CUSTOMER_OPERATION_TYPE_MISMATCH);

    UUID operationId = canonicalNonNilUuid(metadata.get(OPERATION_ID_KEY));
    UUID accountId = canonicalNonNilUuid(metadata.get(ACCOUNT_ID_KEY));
    if (operationId == null) {
      throw new InvalidEvidence(InvalidReason.CUSTOMER_OPERATION_ID_INVALID);
    }
    if (accountId == null) {
      throw new InvalidEvidence(InvalidReason.CUSTOMER_ACCOUNT_ID_INVALID);
    }
    require(
        operationId.equals(expected.customerProvisioningRequestId()),
        InvalidReason.CUSTOMER_OPERATION_ID_MISMATCH);
    require(accountId.equals(expected.accountUuid()), InvalidReason.CUSTOMER_ACCOUNT_ID_MISMATCH);

    return new CustomerBinding(customer.getId(), accountId, operationId, livemode);
  }

  private static ObservedBillingPrerequisites validatePaymentMethod(
      PaymentMethod paymentMethod,
      CustomerBinding customer,
      ExpectedBinding expected,
      boolean expectedLivemode,
      Instant readStartedAt,
      Instant observedAt) {
    if (paymentMethod == null) {
      throw new InvalidEvidence(InvalidReason.MISSING_PAYMENT_METHOD_OBJECT);
    }
    require(
        PAYMENT_METHOD_OBJECT.equals(paymentMethod.getObject()),
        InvalidReason.PAYMENT_METHOD_OBJECT_MISMATCH);
    require(
        expected.providerPaymentMethodId().equals(paymentMethod.getId()),
        InvalidReason.PAYMENT_METHOD_ID_MISMATCH);

    Boolean livemode = paymentMethod.getLivemode();
    if (livemode == null) {
      throw new InvalidEvidence(InvalidReason.PAYMENT_METHOD_LIVEMODE_MISSING);
    }
    require(livemode == expectedLivemode, InvalidReason.PAYMENT_METHOD_LIVEMODE_MISMATCH);
    require(
        customer.providerCustomerId().equals(paymentMethod.getCustomer()),
        InvalidReason.PAYMENT_METHOD_CUSTOMER_MISMATCH);
    require(
        CARD_TYPE.equals(paymentMethod.getType()), InvalidReason.UNSUPPORTED_PAYMENT_METHOD_TYPE);

    PaymentMethod.Card card = paymentMethod.getCard();
    if (card == null) {
      throw new InvalidEvidence(InvalidReason.CARD_DETAILS_MISSING);
    }
    String brand = card.getBrand();
    require(
        brand != null && SUPPORTED_CARD_BRANDS.contains(brand),
        InvalidReason.CARD_BRAND_UNSUPPORTED);
    String lastFour = card.getLast4();
    require(lastFour != null && lastFour.matches("[0-9]{4}"), InvalidReason.CARD_LAST_FOUR_INVALID);
    Long expiryMonth = card.getExpMonth();
    Long expiryYear = card.getExpYear();
    if (expiryMonth == null || expiryYear == null) {
      throw new InvalidEvidence(InvalidReason.CARD_EXPIRY_MISSING);
    }
    require(
        expiryMonth >= 1 && expiryMonth <= 12 && expiryYear >= 2000 && expiryYear <= 9999,
        InvalidReason.CARD_EXPIRY_INVALID);

    DisplayMetadata display =
        new DisplayMetadata(
            brand, lastFour, Math.toIntExact(expiryMonth), Math.toIntExact(expiryYear));
    String digest = evidenceDigest(customer, paymentMethod.getId(), display);
    return new ObservedBillingPrerequisites(
        customer.providerCustomerId(),
        paymentMethod.getId(),
        customer.accountUuid(),
        customer.customerProvisioningRequestId(),
        customer.livemode(),
        display,
        digest,
        readStartedAt,
        observedAt);
  }

  private static String evidenceDigest(
      CustomerBinding customer, String paymentMethodId, DisplayMetadata display) {
    String preimage =
        String.join(
            "\n",
            "firemud-stripe-billing-prerequisites-v1",
            customer.accountUuid().toString(),
            customer.customerProvisioningRequestId().toString(),
            customer.providerCustomerId(),
            paymentMethodId,
            Boolean.toString(customer.livemode()),
            display.brand(),
            display.lastFour(),
            display.expiryMonth().toString(),
            display.expiryYear().toString());
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(preimage.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static UUID canonicalNonNilUuid(String value) {
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

  private static boolean isNil(UUID value) {
    return value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0;
  }

  private static void require(boolean condition, InvalidReason reason) {
    if (!condition) {
      throw new InvalidEvidence(reason);
    }
  }

  private static InvalidObservation invalid(
      ExpectedBinding expected, Instant readStartedAt, Instant observedAt, InvalidReason reason) {
    return new InvalidObservation(
        expected.providerCustomerId(),
        expected.providerPaymentMethodId(),
        readStartedAt,
        observedAt,
        reason);
  }

  private static UnresolvedObservation unresolved(
      ExpectedBinding expected,
      Instant readStartedAt,
      Instant observedAt,
      StripeException providerFailure) {
    return new UnresolvedObservation(
        expected.providerCustomerId(),
        expected.providerPaymentMethodId(),
        readStartedAt,
        observedAt,
        providerFailure);
  }

  public sealed interface Result
      permits ObservedBillingPrerequisites, InvalidObservation, UnresolvedObservation {
    String providerCustomerId();

    String providerPaymentMethodId();

    Instant readStartedAt();

    Instant observedAt();
  }

  /** Complete result of the two successful reads; no caller-facing constructor can mint it. */
  public static final class ObservedBillingPrerequisites implements Result {
    private final String providerCustomerId;
    private final String providerPaymentMethodId;
    private final UUID accountUuid;
    private final UUID customerProvisioningRequestId;
    private final boolean livemode;
    private final DisplayMetadata display;
    private final String evidenceDigest;
    private final Instant readStartedAt;
    private final Instant observedAt;

    private ObservedBillingPrerequisites(
        String providerCustomerId,
        String providerPaymentMethodId,
        UUID accountUuid,
        UUID customerProvisioningRequestId,
        boolean livemode,
        DisplayMetadata display,
        String evidenceDigest,
        Instant readStartedAt,
        Instant observedAt) {
      this.providerCustomerId = providerCustomerId;
      this.providerPaymentMethodId = providerPaymentMethodId;
      this.accountUuid = accountUuid;
      this.customerProvisioningRequestId = customerProvisioningRequestId;
      this.livemode = livemode;
      this.display = display;
      this.evidenceDigest = evidenceDigest;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
    }

    @Override
    public String providerCustomerId() {
      return providerCustomerId;
    }

    @Override
    public String providerPaymentMethodId() {
      return providerPaymentMethodId;
    }

    public UUID accountUuid() {
      return accountUuid;
    }

    public UUID customerProvisioningRequestId() {
      return customerProvisioningRequestId;
    }

    public boolean livemode() {
      return livemode;
    }

    public DisplayMetadata display() {
      return display;
    }

    public String evidenceDigest() {
      return evidenceDigest;
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
      return "stripe.customers.retrieve+stripe.paymentMethods.retrieve";
    }
  }

  /** Successful retrieval with incomplete, unsupported, or contradictory provider data. */
  public static final class InvalidObservation implements Result {
    private final String providerCustomerId;
    private final String providerPaymentMethodId;
    private final Instant readStartedAt;
    private final Instant observedAt;
    private final InvalidReason reason;

    private InvalidObservation(
        String providerCustomerId,
        String providerPaymentMethodId,
        Instant readStartedAt,
        Instant observedAt,
        InvalidReason reason) {
      this.providerCustomerId = providerCustomerId;
      this.providerPaymentMethodId = providerPaymentMethodId;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
      this.reason = reason;
    }

    @Override
    public String providerCustomerId() {
      return providerCustomerId;
    }

    @Override
    public String providerPaymentMethodId() {
      return providerPaymentMethodId;
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

  /** A failed SDK read is unresolved, never an absent Customer or PaymentMethod. */
  public static final class UnresolvedObservation implements Result {
    private final String providerCustomerId;
    private final String providerPaymentMethodId;
    private final Instant readStartedAt;
    private final Instant observedAt;
    private final StripeException providerFailure;

    private UnresolvedObservation(
        String providerCustomerId,
        String providerPaymentMethodId,
        Instant readStartedAt,
        Instant observedAt,
        StripeException providerFailure) {
      this.providerCustomerId = providerCustomerId;
      this.providerPaymentMethodId = providerPaymentMethodId;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
      this.providerFailure = providerFailure;
    }

    @Override
    public String providerCustomerId() {
      return providerCustomerId;
    }

    @Override
    public String providerPaymentMethodId() {
      return providerPaymentMethodId;
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
            "Retain the original typed SDK exception for retry diagnostics; it never proves absence or authorizes a provider mutation.")
    public StripeException providerFailure() {
      return providerFailure;
    }

    public boolean retryable() {
      return true;
    }
  }

  public enum InvalidReason {
    MISSING_CUSTOMER_OBJECT,
    CUSTOMER_OBJECT_MISMATCH,
    CUSTOMER_ID_MISMATCH,
    CUSTOMER_DELETED,
    CUSTOMER_LIVEMODE_MISSING,
    CUSTOMER_LIVEMODE_MISMATCH,
    CUSTOMER_METADATA_MISSING,
    CUSTOMER_METADATA_GRAMMAR_MISMATCH,
    CUSTOMER_METADATA_SCHEMA_UNSUPPORTED,
    CUSTOMER_OPERATION_TYPE_MISMATCH,
    CUSTOMER_OPERATION_ID_INVALID,
    CUSTOMER_ACCOUNT_ID_INVALID,
    CUSTOMER_OPERATION_ID_MISMATCH,
    CUSTOMER_ACCOUNT_ID_MISMATCH,
    MISSING_PAYMENT_METHOD_OBJECT,
    PAYMENT_METHOD_OBJECT_MISMATCH,
    PAYMENT_METHOD_ID_MISMATCH,
    PAYMENT_METHOD_LIVEMODE_MISSING,
    PAYMENT_METHOD_LIVEMODE_MISMATCH,
    PAYMENT_METHOD_CUSTOMER_MISMATCH,
    UNSUPPORTED_PAYMENT_METHOD_TYPE,
    CARD_DETAILS_MISSING,
    CARD_BRAND_UNSUPPORTED,
    CARD_LAST_FOUR_INVALID,
    CARD_EXPIRY_MISSING,
    CARD_EXPIRY_INVALID,
    READ_INTERVAL_REVERSED
  }

  /** Internal expected selector/owner tuple; provider IDs here are hints, not authority. */
  public record ExpectedBinding(
      UUID accountUuid,
      UUID customerProvisioningRequestId,
      UUID providerIdempotencyId,
      String providerCustomerId,
      String providerPaymentMethodId) {
    public ExpectedBinding {
      requireNonNilUuid(accountUuid, "Account UUID");
      requireNonNilUuid(customerProvisioningRequestId, "customer provisioning request ID");
      requireNonNilUuid(providerIdempotencyId, "customer provider idempotency ID");
      if (customerProvisioningRequestId.equals(providerIdempotencyId)) {
        throw new IllegalArgumentException(
            "Customer operation and provider idempotency identities must differ");
      }
      if (providerCustomerId == null || !providerCustomerId.matches("cus_[A-Za-z0-9]+")) {
        throw new IllegalArgumentException("Stripe Customer lookup ID is invalid");
      }
      if (providerPaymentMethodId == null || !providerPaymentMethodId.matches("pm_[A-Za-z0-9]+")) {
        throw new IllegalArgumentException("Stripe PaymentMethod lookup ID is invalid");
      }
    }

    private static void requireNonNilUuid(UUID value, String label) {
      Objects.requireNonNull(value, label + " is required");
      if (isNil(value)) {
        throw new IllegalArgumentException(label + " must not be nil");
      }
    }
  }

  private record CustomerBinding(
      String providerCustomerId,
      UUID accountUuid,
      UUID customerProvisioningRequestId,
      boolean livemode) {}

  private static final class InvalidEvidence extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final InvalidReason reason;

    private InvalidEvidence(InvalidReason reason) {
      this.reason = reason;
    }
  }
}
