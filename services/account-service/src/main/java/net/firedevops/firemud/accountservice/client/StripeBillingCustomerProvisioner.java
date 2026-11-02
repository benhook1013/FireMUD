package net.firedevops.firemud.accountservice.client;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.StripeSearchResult;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerSearchParams;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed customer-only SDK adapter. Its inputs are lookup/dispatch instructions, never
 * caller authorization. Only the durable Account owner may supply initialDispatch=true after it
 * commits a newly inserted first-dispatch claim. Existing claims reconcile only: an empty Stripe
 * search is not authoritative absence and never permits another create, even with the same key. No
 * routes, beans, payment instruments, subscriptions, defaults or entitlements are installed.
 */
public final class StripeBillingCustomerProvisioner {
  private final com.stripe.StripeClient stripe;
  private final Clock clock;
  private final boolean expectedLivemode;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected SDK/repository collaborators remain private; copying would break owner composition.")
  public StripeBillingCustomerProvisioner(
      com.stripe.StripeClient stripe, Clock clock, boolean expectedLivemode) {
    this.stripe = Objects.requireNonNull(stripe);
    this.clock = Objects.requireNonNull(clock);
    this.expectedLivemode = expectedLivemode;
  }

  public Result reconcile(AccountBillingCustomerRecord intent, boolean initialDispatch) {
    Objects.requireNonNull(intent);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Customer provider IO cannot span an ambient SQL transaction");
    }
    // Unknown previous observations are never a pristine first dispatch, even if mis-composed.
    if (initialDispatch
        && (intent.state() != AccountBillingCustomerRecord.State.UNCONFIRMED
            || intent.rowVersion() != 1)) {
      throw new IllegalArgumentException("Only pristine durable intent permits initial dispatch");
    }
    Instant started = clock.instant();
    Map<String, String> metadata = metadata(intent);
    try {
      String candidateId = intent.providerCustomerId();
      if (candidateId == null) {
        // Search is bounded and exact, but eventually consistent; zero results prove no absence.
        String query =
            "metadata['firemud_metadata_schema_version']:'1' AND "
                + "metadata['firemud_operation_type']:'customer_provisioning' AND "
                + "metadata['firemud_operation_id']:'"
                + intent.customerProvisioningRequestId()
                + "' AND metadata['firemud_account_id']:'"
                + intent.accountUuid()
                + "'";
        StripeSearchResult<Customer> matches =
            stripe
                .customers()
                .search(CustomerSearchParams.builder().setQuery(query).setLimit(2L).build());
        if (matches == null
            || matches.getData() == null
            || !Boolean.FALSE.equals(matches.getHasMore())) {
          return new Invalid("Incomplete customer search");
        }
        if (matches.getData().size() > 1) {
          return new Invalid("Multiple customers for one durable operation");
        }
        if (matches.getData().isEmpty()) {
          if (!initialDispatch) {
            return new Unresolved(null);
          }
          Customer created =
              stripe
                  .customers()
                  .create(
                      CustomerCreateParams.builder().putAllMetadata(metadata).build(),
                      RequestOptions.builder()
                          .setIdempotencyKey(intent.providerIdempotencyId().toString())
                          .setMaxNetworkRetries(0)
                          .build());
          if (!valid(created, metadata)) {
            return new Invalid("Create response does not match durable customer operation");
          }
          candidateId = created.getId();
        } else {
          Customer candidate = matches.getData().getFirst();
          if (!valid(candidate, metadata)) {
            return new Invalid("Search response does not match durable customer operation");
          }
          candidateId = candidate.getId();
        }
      }
      // A create/search response is only a hint. A separate SDK read must confirm the binding.
      Customer observed = stripe.customers().retrieve(candidateId);
      Instant ended = clock.instant();
      if (!valid(observed, metadata)
          || !candidateId.equals(observed.getId())
          || ended.isBefore(started)) {
        return new Invalid("Independent customer readback does not match durable operation");
      }
      return new Confirmed(intent, candidateId, started, ended, expectedLivemode);
    } catch (StripeException failure) {
      // Includes timeout, not-found, search failure and lost create response; never absence.
      return new Unresolved(failure);
    }
  }

  private boolean valid(Customer customer, Map<String, String> metadata) {
    return customer != null
        && "customer".equals(customer.getObject())
        && !Boolean.TRUE.equals(customer.getDeleted())
        && Boolean.valueOf(expectedLivemode).equals(customer.getLivemode())
        && customer.getId() != null
        && customer.getId().matches("cus_[A-Za-z0-9]+")
        && customer.getId().length() <= 255
        && metadata.equals(customer.getMetadata());
  }

  private static Map<String, String> metadata(AccountBillingCustomerRecord intent) {
    return Map.of(
        "firemud_metadata_schema_version",
        "1",
        "firemud_operation_type",
        "customer_provisioning",
        "firemud_operation_id",
        intent.customerProvisioningRequestId().toString(),
        "firemud_account_id",
        intent.accountUuid().toString());
  }

  public sealed interface Result permits Confirmed, Invalid, Unresolved {}

  public record Invalid(String reason) implements Result {}

  /**
   * Missing resources and provider failures remain nonterminal; diagnostic causes grant no
   * authority.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The SDK exception is an internal diagnostic cause, never provider authority or a public response.")
  public record Unresolved(StripeException providerFailure) implements Result {}

  /** Genuine independent SDK readback evidence, with no public construction path. */
  public static final class Confirmed implements Result {
    private final UUID accountUuid;
    private final UUID operationId;
    private final UUID providerIdempotencyId;
    private final String providerCustomerId;
    private final String evidenceDigest;
    private final Instant readStartedAt;
    private final Instant observedAt;

    private Confirmed(
        AccountBillingCustomerRecord intent,
        String customerId,
        Instant started,
        Instant ended,
        boolean livemode) {
      accountUuid = intent.accountUuid();
      operationId = intent.customerProvisioningRequestId();
      providerIdempotencyId = intent.providerIdempotencyId();
      providerCustomerId = customerId;
      readStartedAt = started;
      observedAt = ended;
      String evidence =
          "stripe-customer-readback-v1\n"
              + accountUuid
              + "\n"
              + operationId
              + "\n"
              + providerIdempotencyId
              + "\n"
              + customerId
              + "\n"
              + livemode
              + "\n"
              + started
              + "\n"
              + ended;
      try {
        evidenceDigest =
            "sha256:"
                + HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(evidence.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 unavailable", impossible);
      }
    }

    public UUID accountUuid() {
      return accountUuid;
    }

    public UUID customerProvisioningRequestId() {
      return operationId;
    }

    public UUID providerIdempotencyId() {
      return providerIdempotencyId;
    }

    public String providerCustomerId() {
      return providerCustomerId;
    }

    public String evidenceDigest() {
      return evidenceDigest;
    }

    public Instant readStartedAt() {
      return readStartedAt;
    }

    public Instant observedAt() {
      return observedAt;
    }
  }
}
