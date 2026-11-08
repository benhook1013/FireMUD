package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanQuote;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreationRecord;
import org.junit.jupiter.api.Test;

/** Real SDK transport proof with explicitly controlled private owner-dispatch inputs. */
class StripeHostingSubscriptionProvisionerTest {
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID CUSTOMER_OPERATION = UUID.randomUUID();
  private static final UUID CUSTOMER_KEY = UUID.randomUUID();
  private static final UUID OPERATION = UUID.randomUUID();
  private static final UUID SUBSCRIPTION = UUID.randomUUID();
  private static final AccountSubscriptionCreationRecord CREATION =
      new AccountSubscriptionCreationRecord(
          UUID.randomUUID(),
          "sha256:" + "a".repeat(64),
          SUBSCRIPTION,
          UUID.randomUUID(),
          new OwnerFence(ACCOUNT, 1, UUID.randomUUID()),
          "hosting",
          new AccountHostingPlanQuote("hosting", "price_exact", 2, 10, 1, 100),
          UUID.randomUUID(),
          1,
          CUSTOMER_OPERATION,
          "cus_exact",
          OPERATION,
          UUID.randomUUID(),
          "pending",
          1,
          NOW.minusSeconds(1));

  @Test
  void initialCreateUsesExactDurableBindingsAndKeyThenIndependentlyReadsProvider() {
    Transport transport = transport(search(""), subscription(), subscription());
    var located =
        (StripeHostingSubscriptionProvisioner.Located) adapter(transport).provision(dispatch(true));
    assertThat(located.providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(located.observation().providerQuantity()).isEqualTo(2);
    assertThat(transport.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions",
            "/v1/subscriptions/sub_exact");
    assertThat(transport.methods)
        .containsExactly(
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.GET);
    assertThat(transport.options)
        .extracting(RequestOptions::getIdempotencyKey)
        .containsExactly(null, null, null, OPERATION.toString(), null);
    assertThat(transport.options)
        .allSatisfy(
            options -> {
              assertThat(options.getConnectTimeout()).isEqualTo(250);
              assertThat(options.getReadTimeout()).isEqualTo(1000);
              assertThat(options.getMaxNetworkRetries()).isZero();
            });
    assertThat(transport.params.get(3))
        .containsOnlyKeys(
            "customer",
            "default_payment_method",
            "items",
            "metadata",
            "collection_method",
            "payment_behavior");
    assertThat(transport.params.get(3))
        .containsEntry("customer", "cus_exact")
        .containsEntry("default_payment_method", "pm_exact")
        .containsEntry("collection_method", "charge_automatically")
        .containsEntry("payment_behavior", "default_incomplete");
    assertThat(StripeHostingSubscriptionProvisioner.Located.class.getConstructors()).isEmpty();
  }

  @Test
  void unknownOrExpiredIdempotencyOutcomeNeverAuthorizesAnotherCreate() {
    Transport missing = transport(search(""), subscriptionList());
    assertThat(adapter(missing).provision(dispatch(false)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending("PROVIDER_LOOKUP_UNRESOLVED", null));
    assertThat(missing.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions");
    assertThat(missing.methods)
        .containsExactly(
            RequestMethod.GET, RequestMethod.GET, RequestMethod.GET, RequestMethod.GET);
    assertThat(missing.params.get(3))
        .containsEntry("customer", "cus_exact")
        .containsEntry("status", "all")
        .containsEntry("limit", 100L);
    Transport lost = transport(search(""), subscription());
    lost.failureAt = 3;
    lost.failure = mock(StripeException.class);
    assertThat(adapter(lost).provision(dispatch(true)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "PROVIDER_OUTCOME_UNKNOWN", lost.failure));
    assertThat(lost.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions");
    assertThat(lost.methods)
        .containsExactly(
            RequestMethod.GET, RequestMethod.GET, RequestMethod.GET, RequestMethod.POST);
    Transport recovered = transport(search(subscription()), subscription());
    assertThat(adapter(recovered).provision(dispatch(false)))
        .isInstanceOf(StripeHostingSubscriptionProvisioner.Located.class);
    assertThat(recovered.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions/sub_exact");
    assertThat(recovered.methods)
        .containsExactly(
            RequestMethod.GET, RequestMethod.GET, RequestMethod.GET, RequestMethod.GET);
  }

  @Test
  void emptySearchUsesBoundedCustomerListOnlyForNonInitialLookup() {
    Transport recovered = transport(search(""), subscriptionList(subscription()), subscription());
    var located =
        (StripeHostingSubscriptionProvisioner.Located)
            adapter(recovered).provision(dispatch(false));
    assertThat(located.providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(recovered.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions",
            "/v1/subscriptions/sub_exact");
    assertThat(recovered.methods)
        .containsExactly(
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.GET);
    assertThat(recovered.params.get(3))
        .containsEntry("customer", "cus_exact")
        .containsEntry("status", "all")
        .containsEntry("limit", 100L);

    Transport incomplete = transport(search(""), subscriptionList(true, subscription()));
    assertThat(adapter(incomplete).provision(dispatch(false)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null));
    assertThat(incomplete.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions");

    Transport ambiguous =
        transport(search(""), subscriptionList(subscription() + "," + subscription()));
    assertThat(adapter(ambiguous).provision(dispatch(false)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null));
    assertThat(ambiguous.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions");

    Transport wrongCustomer =
        transport(search(""), subscriptionList(subscription().replace("cus_exact", "cus_other")));
    assertThat(adapter(wrongCustomer).provision(dispatch(false)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "INCOMPLETE_OR_AMBIGUOUS_PROVIDER_LIST", null));
    Transport wrongMetadata =
        transport(
            search(""),
            subscriptionList(
                subscription().replace(OPERATION.toString(), UUID.randomUUID().toString())));
    assertThat(adapter(wrongMetadata).provision(dispatch(false)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending("PROVIDER_LOOKUP_UNRESOLVED", null));
  }

  @Test
  void searchFailureDuplicatesAndIndependentBindingMismatchRemainNonterminal() {
    Transport failed = transport(search(""));
    failed.failureAt = 2;
    failed.failure = mock(StripeException.class);
    assertThat(adapter(failed).provision(dispatch(true)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "PROVIDER_OUTCOME_UNKNOWN", failed.failure));
    assertThat(failed.paths)
        .containsExactly(
            "/v1/customers/cus_exact", "/v1/payment_methods/pm_exact", "/v1/subscriptions/search");
    assertThat(failed.methods)
        .containsExactly(RequestMethod.GET, RequestMethod.GET, RequestMethod.GET);
    Transport duplicate = transport(search(subscription() + "," + subscription()));
    assertThat(adapter(duplicate).provision(dispatch(true)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "INCOMPLETE_OR_AMBIGUOUS_PROVIDER_SEARCH", null));
    assertThat(duplicate.paths)
        .containsExactly(
            "/v1/customers/cus_exact", "/v1/payment_methods/pm_exact", "/v1/subscriptions/search");
    assertThat(duplicate.methods)
        .containsExactly(RequestMethod.GET, RequestMethod.GET, RequestMethod.GET);
    Transport mismatch =
        transport(search(subscription()), subscription().replace("pm_exact", "pm_wrong"));
    assertThat(adapter(mismatch).provision(dispatch(false)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "INDEPENDENT_PROVIDER_READBACK_UNRESOLVED", null));
    assertThat(mismatch.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions/sub_exact");
    assertThat(mismatch.methods)
        .containsExactly(
            RequestMethod.GET, RequestMethod.GET, RequestMethod.GET, RequestMethod.GET);
  }

  @Test
  void incompleteFirstInvoiceRemainsNonAuthorizingWithoutAnyPaymentConfirmationCall() {
    String incomplete =
        subscription().replace("\"status\":\"active\"", "\"status\":\"incomplete\"");
    Transport transport = transport(search(""), incomplete, incomplete);
    var located =
        (StripeHostingSubscriptionProvisioner.Located) adapter(transport).provision(dispatch(true));
    assertThat(located.observation().providerStatus()).isEqualTo("incomplete");
    var policy =
        new AccountHostingSubscriptionLifecyclePolicy(
            Duration.ofSeconds(30),
            new RecoveryTiming(Duration.ofMinutes(1), Duration.ofMinutes(1)));
    var decision = policy.evaluate(located.observation(), NOW, null);
    assertThat(decision.resolved()).isFalse();
    assertThat(decision.reason())
        .isEqualTo(AccountHostingSubscriptionLifecyclePolicy.Reason.UNMAPPED_PROVIDER_STATUS);
    assertThat(transport.params.get(3)).containsEntry("payment_behavior", "default_incomplete");
    assertThat(transport.paths)
        .containsExactly(
            "/v1/customers/cus_exact",
            "/v1/payment_methods/pm_exact",
            "/v1/subscriptions/search",
            "/v1/subscriptions",
            "/v1/subscriptions/sub_exact");
    assertThat(transport.methods)
        .containsExactly(
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.GET);
  }

  @Test
  void uncommittedDispatchAndExpiredLeaseRefuseBeforeSdkIo() {
    Transport transport = new Transport();
    ProviderDispatch uncommitted = dispatch(true);
    when(uncommitted.readyForProviderIo()).thenReturn(false);
    assertThatThrownBy(() -> adapter(transport).provision(uncommitted))
        .hasMessageContaining("independent");
    var expired =
        new StripeHostingSubscriptionProvisioner(
            new com.stripe.StripeClient(transport),
            Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC),
            false,
            Duration.ofMillis(250),
            Duration.ofSeconds(1));
    assertThat(expired.provision(dispatch(true)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending("DISPATCH_LEASE_EXPIRED", null));
    assertThat(transport.paths).isEmpty();
    assertThat(transport.methods).isEmpty();
  }

  @Test
  void mismatchedProviderInstrumentAndAmbientTransactionCannotReachCreate() {
    Transport detached = transport(search(""));
    // The controlled PaymentMethod now names another Customer: no search/create follows.
    var responses = new ArrayList<>(detached.responses);
    responses.set(1, responses.get(1).replace("cus_exact", "cus_other"));
    Transport mismatch = new Transport(responses.toArray(String[]::new));
    assertThat(adapter(mismatch).provision(dispatch(true)))
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending(
                "PROVIDER_PREREQUISITES_UNRESOLVED", null));
    assertThat(mismatch.paths)
        .containsExactly("/v1/customers/cus_exact", "/v1/payment_methods/pm_exact");
    assertThat(mismatch.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
    Transport ambient = new Transport();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> adapter(ambient).provision(dispatch(true)))
          .hasMessageContaining("ambient SQL");
      assertThat(ambient.paths).isEmpty();
      assertThat(ambient.methods).isEmpty();
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(false);
    }
  }

  private static ProviderDispatch dispatch(boolean initial) {
    var claim = mock(ProvisioningClaim.class);
    when(claim.creation()).thenReturn(CREATION);
    when(claim.claimedAt()).thenReturn(NOW.minusSeconds(1));
    when(claim.expiresAt()).thenReturn(NOW.plusSeconds(10));
    var dispatch = mock(ProviderDispatch.class);
    when(dispatch.claim()).thenReturn(claim);
    when(dispatch.providerPaymentMethodId()).thenReturn("pm_exact");
    when(dispatch.customerProviderIdempotencyId()).thenReturn(CUSTOMER_KEY);
    when(dispatch.readyForProviderIo()).thenReturn(true);
    when(dispatch.initialCallAllowed()).thenReturn(initial);
    when(dispatch.expectedBinding("sub_exact"))
        .thenReturn(
            new StripeHostingSubscriptionObserver.ExpectedBinding(
                "sub_exact",
                SUBSCRIPTION,
                "cus_exact",
                "pm_exact",
                "price_exact",
                2,
                StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
                OPERATION,
                false));
    return dispatch;
  }

  private static StripeHostingSubscriptionProvisioner adapter(Transport transport) {
    return new StripeHostingSubscriptionProvisioner(
        new com.stripe.StripeClient(transport),
        Clock.fixed(NOW, ZoneOffset.UTC),
        false,
        Duration.ofMillis(250),
        Duration.ofSeconds(1));
  }

  private static Transport transport(String... responses) {
    var all = new ArrayList<String>();
    all.add(
        "{\"object\":\"customer\",\"id\":\"cus_exact\",\"livemode\":false,\"deleted\":false,\"metadata\":{"
            + "\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"customer_provisioning\","
            + "\"firemud_operation_id\":\""
            + CUSTOMER_OPERATION
            + "\",\"firemud_account_id\":\""
            + ACCOUNT
            + "\"}}");
    all.add(
        "{\"object\":\"payment_method\",\"id\":\"pm_exact\",\"customer\":\"cus_exact\",\"livemode\":false,"
            + "\"type\":\"card\",\"card\":{\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}}");
    all.addAll(List.of(responses));
    return new Transport(all.toArray(String[]::new));
  }

  private static String search(String objects) {
    return "{\"object\":\"search_result\",\"has_more\":false,\"data\":[" + objects + "]}";
  }

  private static String subscriptionList(String... objects) {
    return subscriptionList(false, objects);
  }

  private static String subscriptionList(boolean hasMore, String... objects) {
    return "{\"object\":\"list\",\"has_more\":"
        + hasMore
        + ",\"data\":["
        + String.join(",", objects)
        + "]}";
  }

  private static String subscription() {
    return "{\"object\":\"subscription\",\"id\":\"sub_exact\",\"customer\":\"cus_exact\",\"default_payment_method\":\"pm_exact\","
        + "\"livemode\":false,\"status\":\"active\",\"cancel_at_period_end\":false,\"metadata\":{"
        + "\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"subscription_provisioning\","
        + "\"firemud_operation_id\":\""
        + OPERATION
        + "\",\"firemud_subscription_id\":\""
        + SUBSCRIPTION
        + "\"},"
        + "\"items\":{\"has_more\":false,\"data\":[{\"id\":\"si_exact\",\"object\":\"subscription_item\",\"subscription\":\"sub_exact\","
        + "\"quantity\":2,\"current_period_start\":1793577500,\"current_period_end\":1793578500,\"price\":{\"id\":\"price_exact\",\"object\":\"price\"}}]}}";
  }

  private static final class Transport implements StripeResponseGetter {
    private final List<String> responses;
    private final List<String> paths = new ArrayList<>();
    private final List<RequestMethod> methods = new ArrayList<>();
    private final List<Map<String, Object>> params = new ArrayList<>();
    private final List<RequestOptions> options = new ArrayList<>();
    private int failureAt = -1;
    private StripeException failure;

    private Transport(String... responses) {
      this.responses = List.of(responses);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress base,
        RequestMethod method,
        String path,
        Map<String, Object> parameters,
        Type type,
        RequestOptions requestOptions,
        ApiMode mode)
        throws StripeException {
      assertThat(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive())
          .isFalse();
      int index = paths.size();
      paths.add(path);
      methods.add(method);
      params.add(parameters == null ? Map.of() : Map.copyOf(parameters));
      options.add(requestOptions);
      if (index == failureAt) {
        throw failure;
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(responses.get(index), type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress base,
        RequestMethod method,
        String path,
        Map<String, Object> parameters,
        RequestOptions options,
        ApiMode mode) {
      throw new AssertionError("No streaming");
    }
  }
}
