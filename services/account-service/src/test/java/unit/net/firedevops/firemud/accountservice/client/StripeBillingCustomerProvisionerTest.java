package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.State;
import org.junit.jupiter.api.Test;

class StripeBillingCustomerProvisionerTest {
  private static final UUID ACCOUNT = UUID.fromString("f6be6e31-3f4e-4085-8127-08c9589d8dd6");
  private static final UUID OPERATION = UUID.fromString("98d6dfdf-42b6-4d82-b4d7-cea38ec98fae");
  private static final UUID KEY = UUID.fromString("ddcfef48-9eec-4853-9e07-7ec0d3bd697f");
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  @Test
  void firstDispatchUsesPersistedKeyExactMetadataAndIndependentSdkReadback() {
    Transport transport = new Transport(emptySearch(), customer(), customer());
    var result = adapter(transport).reconcile(intent(), true);
    var confirmed = (StripeBillingCustomerProvisioner.Confirmed) result;
    assertThat(confirmed.providerCustomerId()).isEqualTo("cus_exact");
    assertThat(confirmed.accountUuid()).isEqualTo(ACCOUNT);
    assertThat(confirmed.customerProvisioningRequestId()).isEqualTo(OPERATION);
    assertThat(confirmed.providerIdempotencyId()).isEqualTo(KEY);
    assertThat(confirmed.evidenceDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(transport.paths)
        .containsExactly("/v1/customers/search", "/v1/customers", "/v1/customers/cus_exact");
    assertThat(transport.methods)
        .containsExactly(RequestMethod.GET, RequestMethod.POST, RequestMethod.GET);
    assertThat(transport.options.get(1).getIdempotencyKey()).isEqualTo(KEY.toString());
    assertThat(transport.options.get(1).getMaxNetworkRetries()).isZero();
    assertThat(transport.parameters.get(1)).containsOnlyKeys("metadata");
    assertThat(transport.parameters.get(1).get("metadata"))
        .isEqualTo(
            Map.of(
                "firemud_metadata_schema_version",
                "1",
                "firemud_operation_type",
                "customer_provisioning",
                "firemud_operation_id",
                OPERATION.toString(),
                "firemud_account_id",
                ACCOUNT.toString()));
    assertThat(StripeBillingCustomerProvisioner.Confirmed.class.getConstructors()).isEmpty();
  }

  @Test
  void existingClaimNeverCreatesFromEmptySearchAndErrorNeverProvesAbsence() {
    Transport empty = new Transport(emptySearch());
    assertThat(adapter(empty).reconcile(intent(), false))
        .isInstanceOf(StripeBillingCustomerProvisioner.Unresolved.class);
    assertThat(empty.paths).containsExactly("/v1/customers/search");
    Transport error = new Transport(emptySearch());
    error.failureAt = 0;
    error.failure = mock(StripeException.class);
    var result =
        (StripeBillingCustomerProvisioner.Unresolved) adapter(error).reconcile(intent(), true);
    assertThat(result.providerFailure()).isSameAs(error.failure);
    assertThat(error.paths).hasSize(1);
  }

  @Test
  void lostCreateResponseReconcilesSameOperationWithoutAnotherCreate() {
    Transport lost = new Transport(emptySearch(), customer());
    lost.failureAt = 1;
    lost.failure = mock(StripeException.class);
    assertThat(adapter(lost).reconcile(intent(), true))
        .isInstanceOf(StripeBillingCustomerProvisioner.Unresolved.class);
    Transport retry = new Transport(search(customer()), customer());
    assertThat(adapter(retry).reconcile(intent(), false))
        .isInstanceOf(StripeBillingCustomerProvisioner.Confirmed.class);
    assertThat(retry.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
    assertThat(retry.parameters.getFirst().get("query").toString())
        .contains(OPERATION.toString(), ACCOUNT.toString(), "customer_provisioning");
  }

  @Test
  void createResponseWithoutIndependentReadbackCannotProduceConfirmation() {
    Transport transport = new Transport(emptySearch(), customer(), customer());
    transport.failureAt = 2;
    transport.failure = mock(StripeException.class);
    var result = adapter(transport).reconcile(intent(), true);
    assertThat(result).isInstanceOf(StripeBillingCustomerProvisioner.Unresolved.class);
    assertThat(((StripeBillingCustomerProvisioner.Unresolved) result).providerFailure())
        .isSameAs(transport.failure);
    assertThat(transport.methods)
        .containsExactly(RequestMethod.GET, RequestMethod.POST, RequestMethod.GET);
  }

  @Test
  void duplicatesIncompleteSearchExtraMetadataAndWrongReadbackFailClosed() {
    for (String search :
        List.of(
            search(customer() + "," + customer()),
            search(customer()).replace("\"has_more\":false", "\"has_more\":true"),
            search(customer().replace("\"firemud_account_id\"", "\"unexpected\"")))) {
      Transport transport = new Transport(search);
      assertThat(adapter(transport).reconcile(intent(), true))
          .isInstanceOf(StripeBillingCustomerProvisioner.Invalid.class);
      assertThat(transport.methods).containsExactly(RequestMethod.GET);
    }
    for (String invalid :
        List.of(
            customer().replace("cus_exact", "cus_wrong"),
            customer().replace("\"livemode\":false", "\"livemode\":true"),
            customer().replace("\"deleted\":false", "\"deleted\":true"),
            customer().replace("\"firemud_account_id\"", "\"unexpected\""))) {
      Transport transport = new Transport(search(customer()), invalid);
      assertThat(adapter(transport).reconcile(intent(), false))
          .isInstanceOf(StripeBillingCustomerProvisioner.Invalid.class);
      assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
    }
  }

  @Test
  void persistedProviderIdIsRetrievedDirectlyAndNotReplaced() {
    var confirmedIntent =
        new AccountBillingCustomerRecord(
            ACCOUNT,
            OPERATION,
            KEY,
            2,
            State.CONFIRMED,
            "cus_exact",
            UUID.randomUUID(),
            Observation.CONFIRMED,
            "sha256:" + "a".repeat(64),
            NOW,
            NOW);
    Transport transport = new Transport(customer());
    assertThat(adapter(transport).reconcile(confirmedIntent, false))
        .isInstanceOf(StripeBillingCustomerProvisioner.Confirmed.class);
    assertThat(transport.paths).containsExactly("/v1/customers/cus_exact");
  }

  @Test
  void ambientTransactionRefusesBeforeAnySdkCall() {
    Transport transport = new Transport();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> adapter(transport).reconcile(intent(), true))
          .isInstanceOf(IllegalStateException.class);
      assertThat(transport.paths).isEmpty();
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(false);
    }
  }

  private static AccountBillingCustomerRecord intent() {
    return new AccountBillingCustomerRecord(
        ACCOUNT,
        OPERATION,
        KEY,
        1,
        State.UNCONFIRMED,
        null,
        OPERATION,
        Observation.NONE,
        null,
        NOW,
        NOW);
  }

  private static StripeBillingCustomerProvisioner adapter(Transport transport) {
    return new StripeBillingCustomerProvisioner(
        new com.stripe.StripeClient(transport), Clock.fixed(NOW, ZoneOffset.UTC), false);
  }

  private static String emptySearch() {
    return search("");
  }

  private static String search(String customers) {
    return "{\"object\":\"search_result\",\"has_more\":false,\"data\":[" + customers + "]}";
  }

  private static String customer() {
    return "{\"object\":\"customer\",\"id\":\"cus_exact\",\"deleted\":false,\"livemode\":false,\"metadata\":{"
        + "\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"customer_provisioning\","
        + "\"firemud_operation_id\":\""
        + OPERATION
        + "\",\"firemud_account_id\":\""
        + ACCOUNT
        + "\"}}";
  }

  private static final class Transport implements StripeResponseGetter {
    private final List<String> responses;
    private final List<String> paths = new ArrayList<>();
    private final List<RequestMethod> methods = new ArrayList<>();
    private final List<Map<String, Object>> parameters = new ArrayList<>();
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
        Map<String, Object> params,
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
      parameters.add(params == null ? Map.of() : Map.copyOf(params));
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
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode) {
      throw new AssertionError("Customer provisioning must not stream");
    }
  }
}
