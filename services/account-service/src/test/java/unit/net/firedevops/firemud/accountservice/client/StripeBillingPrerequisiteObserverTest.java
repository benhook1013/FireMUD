package net.firedevops.firemud.accountservice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StripeBillingPrerequisiteObserverTest {
  private static final String CUSTOMER_ID = "cus_expected";
  private static final String PAYMENT_METHOD_ID = "pm_expected";
  private static final UUID ACCOUNT_ID = UUID.fromString("79015b62-73ad-4dc0-a349-cc5b1a288a19");
  private static final UUID CUSTOMER_OPERATION_ID =
      UUID.fromString("4e2af076-4a10-4aaf-93a0-e5df29271310");
  private static final UUID CUSTOMER_IDEMPOTENCY_ID =
      UUID.fromString("0c9ee07d-0e6d-4f66-9a87-54110acbc611");
  private static final Instant READ_STARTED_AT = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant CUSTOMER_READ_ENDED_AT = Instant.parse("2026-11-01T00:00:01Z");
  private static final Instant OBSERVED_AT = Instant.parse("2026-11-01T00:00:02Z");

  @Test
  void retrievesBothExactObjectsThroughSdkAndReturnsOnlyBoundedImmutableEvidence() {
    ControlledStripeTransport transport =
        new ControlledStripeTransport(customerResponse(), paymentMethodResponse());

    StripeBillingPrerequisiteObserver.ObservedBillingPrerequisites result =
        assertInstanceOf(
            StripeBillingPrerequisiteObserver.ObservedBillingPrerequisites.class,
            observer(transport, fixedClock()).observe(expected()));

    assertEquals(CUSTOMER_ID, result.providerCustomerId());
    assertEquals(PAYMENT_METHOD_ID, result.providerPaymentMethodId());
    assertEquals(ACCOUNT_ID, result.accountUuid());
    assertEquals(CUSTOMER_OPERATION_ID, result.customerProvisioningRequestId());
    assertFalse(result.livemode());
    assertEquals("visa", result.display().brand());
    assertEquals("4242", result.display().lastFour());
    assertEquals(12, result.display().expiryMonth());
    assertEquals(2030, result.display().expiryYear());
    assertTrue(result.evidenceDigest().matches("sha256:[0-9a-f]{64}"));
    assertEquals(READ_STARTED_AT, result.readStartedAt());
    assertEquals(OBSERVED_AT, result.observedAt());
    assertEquals("stripe.customers.retrieve+stripe.paymentMethods.retrieve", result.source());
    assertEquals(2, transport.callCount());
    assertEquals(
        List.of("/v1/customers/" + CUSTOMER_ID, "/v1/payment_methods/" + PAYMENT_METHOD_ID),
        transport.paths());
    assertEquals(List.of(RequestMethod.GET, RequestMethod.GET), transport.methods());
    assertTrue(transport.parameters().stream().allMatch(Map::isEmpty));
    assertThrows(
        NoSuchMethodException.class,
        () ->
            StripeBillingPrerequisiteObserver.ObservedBillingPrerequisites.class.getConstructor(
                String.class,
                String.class,
                UUID.class,
                UUID.class,
                boolean.class,
                net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord
                    .DisplayMetadata.class,
                String.class,
                Instant.class,
                Instant.class));
  }

  @Test
  void rejectsDeletedMismatchedModeAndWrongCustomerObjectsOrIDs() {
    assertInvalid(
        customerResponse().replace("\"deleted\":false,", "\"deleted\":true,"),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_DELETED);
    assertInvalid(
        customerResponse().replace("\"livemode\":false", "\"livemode\":true"),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_LIVEMODE_MISMATCH);
    assertInvalid(
        customerResponse().replace("\"livemode\":false,", ""),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_LIVEMODE_MISSING);
    assertInvalid(
        customerResponse().replace(CUSTOMER_ID, "cus_other"),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_ID_MISMATCH);
    assertInvalid(
        customerResponse().replace("\"object\":\"customer\"", "\"object\":\"account\""),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_OBJECT_MISMATCH);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"object\":\"payment_method\"", "\"object\":\"card\""),
        StripeBillingPrerequisiteObserver.InvalidReason.PAYMENT_METHOD_OBJECT_MISMATCH);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace(PAYMENT_METHOD_ID, "pm_other"),
        StripeBillingPrerequisiteObserver.InvalidReason.PAYMENT_METHOD_ID_MISMATCH);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"livemode\":false", "\"livemode\":true"),
        StripeBillingPrerequisiteObserver.InvalidReason.PAYMENT_METHOD_LIVEMODE_MISMATCH);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"livemode\":false,", ""),
        StripeBillingPrerequisiteObserver.InvalidReason.PAYMENT_METHOD_LIVEMODE_MISSING);
  }

  @Test
  void requiresExactVersionedMetadataAndStrictCanonicalAccountAndCustomerOperationUUIDs() {
    assertInvalid(
        customerResponseWithoutMetadata(),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_METADATA_MISSING);
    assertInvalid(
        customerResponseWithoutAccountMetadata(),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_METADATA_GRAMMAR_MISMATCH);
    assertInvalid(
        customerResponse()
            .replace(
                "\"firemud_metadata_schema_version\":\"1\"",
                "\"firemud_metadata_schema_version\":\"2\""),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_METADATA_SCHEMA_UNSUPPORTED);
    assertInvalid(
        customerResponse()
            .replace(
                "\"firemud_operation_type\":\"customer_provisioning\"",
                "\"firemud_operation_type\":\"subscription_provisioning\""),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_OPERATION_TYPE_MISMATCH);
    assertInvalid(
        customerResponse()
            .replace(CUSTOMER_OPERATION_ID.toString(), CUSTOMER_IDEMPOTENCY_ID.toString()),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_OPERATION_ID_MISMATCH);
    assertInvalid(
        customerResponse().replace(ACCOUNT_ID.toString(), "00000000-0000-0000-0000-000000000000"),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_ACCOUNT_ID_INVALID);
    assertInvalid(
        customerResponse().replace(ACCOUNT_ID.toString(), "79015b62-73ad-4dc0-a349-cc5b1a288a1"),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_ACCOUNT_ID_INVALID);
    assertInvalid(
        customerResponse()
            .replace(
                "\"firemud_account_id\":\"" + ACCOUNT_ID + "\"",
                "\"firemud_account_id\":\"" + ACCOUNT_ID + "\",\"unexpected\":\"value\""),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_METADATA_GRAMMAR_MISMATCH);
    assertInvalid(
        customerResponse().replace(ACCOUNT_ID.toString(), CUSTOMER_IDEMPOTENCY_ID.toString()),
        paymentMethodResponse(),
        StripeBillingPrerequisiteObserver.InvalidReason.CUSTOMER_ACCOUNT_ID_MISMATCH);
  }

  @Test
  void rejectsUnattachedOrWrongCustomerMethodsAndNeverUsesMutableCustomerDefaults() {
    String customerWithDefaults =
        customerResponse()
            .replace(
                "\"livemode\":false,",
                "\"livemode\":false,\"default_source\":\"src_ignored\","
                    + "\"invoice_settings\":{\"default_payment_method\":\"pm_expected\"},");
    assertInvalid(
        customerWithDefaults,
        paymentMethodResponse().replace("\"customer\":\"cus_expected\"", "\"customer\":null"),
        StripeBillingPrerequisiteObserver.InvalidReason.PAYMENT_METHOD_CUSTOMER_MISMATCH);
    assertInvalid(
        customerWithDefaults,
        paymentMethodResponse().replace(CUSTOMER_ID, "cus_another"),
        StripeBillingPrerequisiteObserver.InvalidReason.PAYMENT_METHOD_CUSTOMER_MISMATCH);
  }

  @Test
  void acceptsOnlySupportedCardDisplayFactsWithoutReadingCallerCardData() {
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"type\":\"card\"", "\"type\":\"sepa_debit\""),
        StripeBillingPrerequisiteObserver.InvalidReason.UNSUPPORTED_PAYMENT_METHOD_TYPE);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"brand\":\"visa\"", "\"brand\":null"),
        StripeBillingPrerequisiteObserver.InvalidReason.CARD_BRAND_UNSUPPORTED);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"brand\":\"visa\"", "\"brand\":\"not-approved\""),
        StripeBillingPrerequisiteObserver.InvalidReason.CARD_BRAND_UNSUPPORTED);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"last4\":\"4242\"", "\"last4\":\"12x4\""),
        StripeBillingPrerequisiteObserver.InvalidReason.CARD_LAST_FOUR_INVALID);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"exp_month\":12", "\"exp_month\":13"),
        StripeBillingPrerequisiteObserver.InvalidReason.CARD_EXPIRY_INVALID);
    assertInvalid(
        customerResponse(),
        paymentMethodResponse().replace("\"exp_year\":2030", "\"exp_year\":null"),
        StripeBillingPrerequisiteObserver.InvalidReason.CARD_EXPIRY_MISSING);
  }

  @Test
  void providerErrorsRemainUnresolvedWithOriginalExceptionAndNeverProveAbsence() {
    StripeException providerFailure = mock(StripeException.class);
    ControlledStripeTransport transport =
        ControlledStripeTransport.failOn(
            "/v1/customers/" + CUSTOMER_ID, providerFailure, paymentMethodResponse());

    StripeBillingPrerequisiteObserver.UnresolvedObservation unresolved =
        assertInstanceOf(
            StripeBillingPrerequisiteObserver.UnresolvedObservation.class,
            observer(transport, fixedClock()).observe(expected()));

    assertSame(providerFailure, unresolved.providerFailure());
    assertTrue(unresolved.retryable());
    assertEquals(1, transport.callCount());
    assertEquals(READ_STARTED_AT, unresolved.readStartedAt());
    assertEquals(CUSTOMER_READ_ENDED_AT, unresolved.observedAt());

    StripeException methodFailure = mock(StripeException.class);
    ControlledStripeTransport secondFailure =
        ControlledStripeTransport.failOn(
            "/v1/payment_methods/" + PAYMENT_METHOD_ID, methodFailure, paymentMethodResponse());
    StripeBillingPrerequisiteObserver.UnresolvedObservation methodUnresolved =
        assertInstanceOf(
            StripeBillingPrerequisiteObserver.UnresolvedObservation.class,
            observer(secondFailure, fixedClock()).observe(expected()));
    assertSame(methodFailure, methodUnresolved.providerFailure());
    assertEquals(2, secondFailure.callCount());
  }

  @Test
  void reversedReadClockRejectsEvidenceAndStopsBeforePaymentMethodRead() {
    ControlledStripeTransport transport =
        new ControlledStripeTransport(customerResponse(), paymentMethodResponse());
    Clock reversed = sequenceClock(OBSERVED_AT, READ_STARTED_AT);

    StripeBillingPrerequisiteObserver.InvalidObservation invalid =
        assertInstanceOf(
            StripeBillingPrerequisiteObserver.InvalidObservation.class,
            observer(transport, reversed).observe(expected()));

    assertEquals(
        StripeBillingPrerequisiteObserver.InvalidReason.READ_INTERVAL_REVERSED, invalid.reason());
    assertEquals(OBSERVED_AT, invalid.readStartedAt());
    assertEquals(READ_STARTED_AT, invalid.observedAt());
    assertEquals(1, transport.callCount());
  }

  @Test
  void rejectsNilOrReusedCustomerProvisioningIdentitiesBeforeSdkCalls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StripeBillingPrerequisiteObserver.ExpectedBinding(
                new UUID(0, 0),
                CUSTOMER_OPERATION_ID,
                CUSTOMER_IDEMPOTENCY_ID,
                CUSTOMER_ID,
                PAYMENT_METHOD_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StripeBillingPrerequisiteObserver.ExpectedBinding(
                ACCOUNT_ID,
                CUSTOMER_OPERATION_ID,
                CUSTOMER_OPERATION_ID,
                CUSTOMER_ID,
                PAYMENT_METHOD_ID));
  }

  private static void assertInvalid(
      String customer,
      String paymentMethod,
      StripeBillingPrerequisiteObserver.InvalidReason expectedReason) {
    ControlledStripeTransport transport = new ControlledStripeTransport(customer, paymentMethod);
    StripeBillingPrerequisiteObserver.InvalidObservation invalid =
        assertInstanceOf(
            StripeBillingPrerequisiteObserver.InvalidObservation.class,
            observer(transport, fixedClock()).observe(expected()));
    assertEquals(expectedReason, invalid.reason());
  }

  private static StripeBillingPrerequisiteObserver observer(
      ControlledStripeTransport transport, Clock clock) {
    return new StripeBillingPrerequisiteObserver(
        new com.stripe.StripeClient(transport), clock, false);
  }

  private static StripeBillingPrerequisiteObserver.ExpectedBinding expected() {
    return new StripeBillingPrerequisiteObserver.ExpectedBinding(
        ACCOUNT_ID, CUSTOMER_OPERATION_ID, CUSTOMER_IDEMPOTENCY_ID, CUSTOMER_ID, PAYMENT_METHOD_ID);
  }

  private static Clock fixedClock() {
    return sequenceClock(READ_STARTED_AT, CUSTOMER_READ_ENDED_AT, OBSERVED_AT);
  }

  private static Clock sequenceClock(Instant... instants) {
    return new Clock() {
      private int calls;

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return instants[Math.min(calls++, instants.length - 1)];
      }
    };
  }

  private static String customerResponse() {
    return """
        {"id":"cus_expected","object":"customer","deleted":false,"livemode":false,
         "metadata":{"firemud_metadata_schema_version":"1",
          "firemud_operation_type":"customer_provisioning",
          "firemud_operation_id":"4e2af076-4a10-4aaf-93a0-e5df29271310",
          "firemud_account_id":"79015b62-73ad-4dc0-a349-cc5b1a288a19"}}
        """;
  }

  private static String customerResponseWithoutMetadata() {
    return """
        {"id":"cus_expected","object":"customer","deleted":false,
         "livemode":false,"metadata":null}
        """;
  }

  private static String customerResponseWithoutAccountMetadata() {
    JsonObject customer = JsonParser.parseString(customerResponse()).getAsJsonObject();
    customer.getAsJsonObject("metadata").remove("firemud_account_id");
    return customer.toString();
  }

  private static String paymentMethodResponse() {
    return """
        {"id":"pm_expected","object":"payment_method","type":"card","livemode":false,
         "customer":"cus_expected","card":{"brand":"visa","last4":"4242",
          "exp_month":12,"exp_year":2030}}
        """;
  }

  private static final class ControlledStripeTransport implements StripeResponseGetter {
    private final Map<String, String> responses = new HashMap<>();
    private final String failurePath;
    private final StripeException failure;
    private int callCount;
    private final java.util.ArrayList<String> paths = new java.util.ArrayList<>();
    private final java.util.ArrayList<RequestMethod> methods = new java.util.ArrayList<>();
    private final java.util.ArrayList<Map<String, Object>> parameters = new java.util.ArrayList<>();

    private ControlledStripeTransport(String customerResponse, String paymentMethodResponse) {
      this(customerResponse, paymentMethodResponse, null, null);
    }

    private ControlledStripeTransport(
        String customerResponse,
        String paymentMethodResponse,
        String failurePath,
        StripeException failure) {
      responses.put("/v1/customers/" + CUSTOMER_ID, customerResponse);
      responses.put("/v1/payment_methods/" + PAYMENT_METHOD_ID, paymentMethodResponse);
      this.failurePath = failurePath;
      this.failure = failure;
    }

    private static ControlledStripeTransport failOn(
        String failurePath, StripeException failure, String paymentMethodResponse) {
      return new ControlledStripeTransport(
          customerResponse(), paymentMethodResponse, failurePath, failure);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress baseAddress,
        RequestMethod requestMethod,
        String requestPath,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode apiMode)
        throws StripeException {
      callCount++;
      paths.add(requestPath);
      methods.add(requestMethod);
      parameters.add(params == null ? Map.of() : Map.copyOf(params));
      if (requestPath.equals(failurePath)) {
        throw failure;
      }
      String response = responses.get(requestPath);
      if (response == null) {
        throw new AssertionError("Unexpected Stripe SDK path: " + requestPath);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress baseAddress,
        RequestMethod requestMethod,
        String requestPath,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode apiMode)
        throws StripeException {
      throw new AssertionError("Customer and PaymentMethod retrieval must not stream");
    }

    private int callCount() {
      return callCount;
    }

    private List<String> paths() {
      return List.copyOf(paths);
    }

    private List<RequestMethod> methods() {
      return List.copyOf(methods);
    }

    private List<Map<String, Object>> parameters() {
      return List.copyOf(parameters);
    }
  }
}
