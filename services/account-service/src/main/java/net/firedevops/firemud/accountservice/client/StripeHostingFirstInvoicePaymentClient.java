package net.firedevops.firemud.accountservice.client;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.PaymentIntentConfirmParams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePayment;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.CommandClaim;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Existing invoice/intent only. Never creates a PaymentIntent, Checkout Session or replacement. */
public final class StripeHostingFirstInvoicePaymentClient {
  private final StripeClient stripe;
  private final Clock clock;
  private final RequestOptions options;
  private final Duration exchangeBudget;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Private injected SDK transport remains owner-composed; copying it would discard the configured provider transport.")
  public StripeHostingFirstInvoicePaymentClient(
      StripeClient stripe, Clock clock, Duration connect, Duration read) {
    this.stripe = Objects.requireNonNull(stripe);
    this.clock = Objects.requireNonNull(clock);
    this.exchangeBudget = read;
    options =
        RequestOptions.builder()
            .setConnectTimeout(timeout(connect))
            .setReadTimeout(timeout(read))
            .setMaxNetworkRetries(0)
            .build();
  }

  public Observation capture(ProviderDispatch dispatch, String providerId) throws StripeException {
    outsideSql();
    if (!dispatch.readyForProviderIo())
      throw new IllegalStateException("Independent provider-dispatch receipt required");
    Instant started = clock.instant();
    Subscription subscription = stripe.subscriptions().retrieve(providerId, options);
    if (subscription == null) throw refused();
    var binding = dispatch.expectedBinding(providerId);
    require(
        "subscription".equals(subscription.getObject())
            && providerId.equals(subscription.getId())
            && binding.providerCustomerId().equals(subscription.getCustomer())
            && binding.providerPaymentMethodId().equals(subscription.getDefaultPaymentMethod())
            && Boolean.valueOf(binding.livemode()).equals(subscription.getLivemode())
            && "incomplete".equals(subscription.getStatus())
            && subscription.getCreated() != null
            && subscription.getMetadata() != null
            && binding
                .operationId()
                .toString()
                .equals(subscription.getMetadata().get("firemud_operation_id"))
            && binding
                .subscriptionId()
                .toString()
                .equals(subscription.getMetadata().get("firemud_subscription_id")));
    String invoiceId = subscription.getLatestInvoice();
    require(invoiceId != null && invoiceId.matches("in_[A-Za-z0-9]+"));
    var result =
        readInvoice(
            invoiceId,
            providerId,
            binding.providerCustomerId(),
            binding.providerPaymentMethodId(),
            binding.livemode(),
            dispatch.claim().creation().planQuote().providerPriceId(),
            dispatch.claim().creation().planQuote().providerQuantity(),
            started,
            Instant.ofEpochSecond(subscription.getCreated()).plus(Duration.ofHours(23)));
    require(!"succeeded".equals(result.status()));
    return result;
  }

  public Observation observe(AccountHostingFirstInvoicePayment expected) throws StripeException {
    outsideSql();
    var result =
        readInvoice(
            expected.invoiceId(),
            expected.providerSubscriptionId(),
            expected.providerCustomerId(),
            expected.providerPaymentMethodId(),
            expected.livemode(),
            expected.providerPriceId(),
            expected.providerQuantity(),
            clock.instant(),
            expected.expiresAt());
    require(
        expected.paymentIntentId().equals(result.paymentIntentId())
            && expected.amount() == result.amount()
            && expected.currency().equals(result.currency()));
    return result;
  }

  /**
   * Only the original durable authorization may issue one confirm; recovery observes the same
   * intent.
   */
  public Handoff confirm(
      ConfirmationDispatch dispatch, java.util.function.Supplier<CommandClaim> claimCommand)
      throws StripeException {
    outsideSql();
    if (!dispatch.independentlyRead() || !clock.instant().isBefore(dispatch.validUntil())) {
      throw new IllegalStateException("Committed current billing-owner consent required");
    }
    Observation observation = observe(dispatch.invoice());
    PaymentIntent intent = stripe.paymentIntents().retrieve(observation.paymentIntentId(), options);
    validateIntent(
        intent,
        dispatch.invoice().providerCustomerId(),
        dispatch.invoice().providerPaymentMethodId(),
        dispatch.invoice().livemode());
    require(
        dispatch.invoice().paymentIntentId().equals(intent.getId())
            && Long.valueOf(dispatch.invoice().amount()).equals(intent.getAmount())
            && dispatch.invoice().currency().equals(intent.getCurrency()));
    if ("requires_confirmation".equals(intent.getStatus())
        || "requires_payment_method".equals(intent.getStatus())) {
      if (!dispatch.takeInitialCommandPermission())
        return new Handoff("AUTHORIZED_NOT_CONFIRMED", null);
      require(clock.instant().plus(exchangeBudget).isBefore(dispatch.validUntil()));
      CommandClaim claim = claimCommand.get();
      outsideSql();
      if (claim == null || !claim.takePermission(dispatch))
        return new Handoff("AUTHORIZED_NOT_CONFIRMED", null);
      Instant beforePost = clock.instant();
      if (!beforePost.isBefore(dispatch.validUntil())) {
        throw new ExpiredBeforePost(dispatch.requestId(), beforePost);
      }
      intent =
          stripe
              .paymentIntents()
              .confirm(
                  intent.getId(),
                  PaymentIntentConfirmParams.builder()
                      .setPaymentMethod(dispatch.invoice().providerPaymentMethodId())
                      .setUseStripeSdk(true)
                      .build(),
                  options
                      .toBuilderFullCopy()
                      .setIdempotencyKey(dispatch.providerIdempotencyId().toString())
                      .build());
    }
    validateIntent(
        intent,
        dispatch.invoice().providerCustomerId(),
        dispatch.invoice().providerPaymentMethodId(),
        dispatch.invoice().livemode());
    require(
        dispatch.invoice().paymentIntentId().equals(intent.getId())
            && Long.valueOf(dispatch.invoice().amount()).equals(intent.getAmount())
            && dispatch.invoice().currency().equals(intent.getCurrency()));
    require(clock.instant().isBefore(dispatch.validUntil()));
    String secret = "requires_action".equals(intent.getStatus()) ? intent.getClientSecret() : null;
    require(
        !"requires_action".equals(intent.getStatus())
            || (secret != null && secret.startsWith(intent.getId() + "_secret_")));
    return new Handoff(intent.getStatus(), secret);
  }

  private Observation readInvoice(
      String invoiceId,
      String subscriptionId,
      String customerId,
      String methodId,
      boolean livemode,
      String priceId,
      long quantity,
      Instant started,
      Instant expiry)
      throws StripeException {
    Invoice invoice =
        stripe
            .invoices()
            .retrieve(
                invoiceId,
                InvoiceRetrieveParams.builder()
                    .addExpand("payments.data.payment.payment_intent")
                    .build(),
                options);
    if (invoice == null) throw refused();
    require(
        "invoice".equals(invoice.getObject())
            && invoiceId.equals(invoice.getId())
            && customerId.equals(invoice.getCustomer())
            && Boolean.valueOf(livemode).equals(invoice.getLivemode())
            && "subscription_create".equals(invoice.getBillingReason())
            && invoice.getParent() != null
            && invoice.getParent().getSubscriptionDetails() != null
            && subscriptionId.equals(invoice.getParent().getSubscriptionDetails().getSubscription())
            && invoice.getPayments() != null
            && Boolean.FALSE.equals(invoice.getPayments().getHasMore())
            && invoice.getPayments().getData() != null
            && invoice.getPayments().getData().size() == 1);
    require(
        invoice.getLines() != null
            && Boolean.FALSE.equals(invoice.getLines().getHasMore())
            && invoice.getLines().getData() != null
            && invoice.getLines().getData().size() == 1);
    var line = invoice.getLines().getData().getFirst();
    require(
        line != null
            && "line_item".equals(line.getObject())
            && Long.valueOf(quantity).equals(line.getQuantity())
            && (line.getQuantityDecimal() == null
                || java.math.BigDecimal.valueOf(quantity).compareTo(line.getQuantityDecimal()) == 0)
            && line.getPricing() != null
            && "price_details".equals(line.getPricing().getType())
            && line.getPricing().getPriceDetails() != null
            && priceId.equals(line.getPricing().getPriceDetails().getPrice())
            && invoice.getAmountDue() != null
            && invoice.getAmountDue().equals(line.getAmount())
            && invoice.getCurrency() != null
            && invoice.getCurrency().equals(line.getCurrency()));
    var payment = invoice.getPayments().getData().getFirst();
    if (payment == null) throw refused();
    require(
        payment.getPayment() != null && "payment_intent".equals(payment.getPayment().getType()));
    PaymentIntent intent = payment.getPayment().getPaymentIntentObject();
    if (intent == null) throw refused();
    require(payment.getPayment().getPaymentIntent().equals(intent.getId()));
    validateIntent(intent, customerId, methodId, livemode);
    require(
        invoice.getAmountDue() != null
            && invoice.getAmountDue() > 0
            && invoice.getAmountDue().equals(invoice.getTotal())
            && Long.valueOf(0).equals(invoice.getStartingBalance())
            && invoice.getAmountDue().equals(intent.getAmount())
            && invoice.getCurrency().equals(intent.getCurrency()));
    if ("succeeded".equals(intent.getStatus())) {
      require(
          "paid".equals(invoice.getStatus())
              && invoice.getAmountDue().equals(invoice.getAmountPaid())
              && Long.valueOf(0).equals(invoice.getAmountRemaining())
              && invoice.getAmountDue().equals(intent.getAmountReceived()));
    } else {
      require(
          "open".equals(invoice.getStatus())
              && Long.valueOf(0).equals(invoice.getAmountPaid())
              && invoice.getAmountDue().equals(invoice.getAmountRemaining()));
    }
    Instant observed = clock.instant();
    require(
        !observed.isBefore(started)
            && Duration.between(started, observed).compareTo(Duration.ofSeconds(20)) < 0);
    return new Observation(
        subscriptionId,
        customerId,
        methodId,
        livemode,
        priceId,
        quantity,
        invoiceId,
        intent.getId(),
        intent.getAmount(),
        intent.getCurrency(),
        intent.getStatus(),
        started,
        observed,
        expiry);
  }

  private static void validateIntent(
      PaymentIntent intent, String customer, String method, boolean live) {
    require(
        intent != null
            && "payment_intent".equals(intent.getObject())
            && intent.getId() != null
            && customer.equals(intent.getCustomer())
            && method.equals(intent.getPaymentMethod())
            && Boolean.valueOf(live).equals(intent.getLivemode())
            && "automatic".equals(intent.getConfirmationMethod())
            && java.util.Set.of(
                    "requires_confirmation",
                    "requires_payment_method",
                    "requires_action",
                    "processing",
                    "succeeded")
                .contains(intent.getStatus()));
  }

  private static int timeout(Duration value) {
    if (value == null
        || value.isNegative()
        || value.isZero()
        || value.toMillis() > 10000
        || value.getNano() % 1000000 != 0) {
      throw new IllegalArgumentException("Bounded whole-millisecond provider timeout required");
    }
    return Math.toIntExact(value.toMillis());
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("First-invoice provider IO refuses ambient SQL transaction");
    }
  }

  private static void require(boolean valid) {
    if (!valid) throw refused();
  }

  private static IllegalStateException refused() {
    return new IllegalStateException("Exact existing first-invoice provider binding refused");
  }

  /** The winning committed claim was consumed locally, before entry to the Stripe confirm call. */
  public static final class ExpiredBeforePost extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final UUID requestId;
    private final Instant observedAt;

    private ExpiredBeforePost(UUID requestId, Instant observedAt) {
      super("Exact existing first-invoice provider binding refused");
      this.requestId = requestId;
      this.observedAt = observedAt;
    }

    public UUID requestId() {
      return requestId;
    }

    public Instant observedAt() {
      return observedAt;
    }
  }

  /** Contains no client secret, and is not payment or entitlement authority. */
  public static final class Observation {
    private final String invoiceId, paymentIntentId, currency, status;
    private final String providerSubscriptionId,
        providerCustomerId,
        providerPaymentMethodId,
        providerPriceId;
    private final long providerQuantity;
    private final boolean livemode;
    private final long amount;
    private final Instant readStartedAt, observedAt, expiresAt;

    private Observation(
        String providerSubscriptionId,
        String providerCustomerId,
        String providerPaymentMethodId,
        boolean livemode,
        String providerPriceId,
        long providerQuantity,
        String invoiceId,
        String paymentIntentId,
        long amount,
        String currency,
        String status,
        Instant readStartedAt,
        Instant observedAt,
        Instant expiresAt) {
      this.providerSubscriptionId = providerSubscriptionId;
      this.providerCustomerId = providerCustomerId;
      this.providerPaymentMethodId = providerPaymentMethodId;
      this.livemode = livemode;
      this.providerPriceId = providerPriceId;
      this.providerQuantity = providerQuantity;
      this.invoiceId = invoiceId;
      this.paymentIntentId = paymentIntentId;
      this.amount = amount;
      this.currency = currency;
      this.status = status;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
      this.expiresAt = expiresAt;
    }

    public String providerSubscriptionId() {
      return providerSubscriptionId;
    }

    public String providerCustomerId() {
      return providerCustomerId;
    }

    public String providerPaymentMethodId() {
      return providerPaymentMethodId;
    }

    public String providerPriceId() {
      return providerPriceId;
    }

    public long providerQuantity() {
      return providerQuantity;
    }

    public boolean livemode() {
      return livemode;
    }

    public String invoiceId() {
      return invoiceId;
    }

    public String paymentIntentId() {
      return paymentIntentId;
    }

    public long amount() {
      return amount;
    }

    public String currency() {
      return currency;
    }

    public String status() {
      return status;
    }

    public Instant readStartedAt() {
      return readStartedAt;
    }

    public Instant observedAt() {
      return observedAt;
    }

    public Instant expiresAt() {
      return expiresAt;
    }
  }

  /** The secret is released only to the authenticated HTTPS owner and never persisted or logged. */
  public static final class Handoff {
    private final String status;
    private final String clientSecret;

    private Handoff(String status, String secret) {
      this.status = status;
      this.clientSecret = secret;
    }

    public String status() {
      return status;
    }

    public String clientSecret() {
      return clientSecret;
    }

    @Override
    public String toString() {
      return "HostingFirstInvoiceHandoff[redacted]";
    }
  }
}
