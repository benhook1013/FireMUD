package net.firedevops.firemud.accountservice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.HttpClient;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import com.stripe.net.StripeResponseGetter;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StripeHostingSubscriptionObserverTest {
  private static final String PROVIDER_SUBSCRIPTION_ID = "sub_expected";
  private static final String PROVIDER_CUSTOMER_ID = "cus_expected";
  private static final String PROVIDER_PAYMENT_METHOD_ID = "pm_expected";
  private static final String PROVIDER_PRICE_ID = "price_expected";
  private static final String OPERATION_ID = "4e2af076-4a10-4aaf-93a0-e5df29271310";
  private static final String SUBSCRIPTION_ID = "0c9ee07d-0e6d-4f66-9a87-54110acbc611";
  private static final Instant READ_STARTED_AT = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant OBSERVED_AT = Instant.parse("2026-11-01T00:00:01Z");
  private static final String VALID_RESPONSE = validResponse();

  @Test
  void retrievesAndParsesExactSdkSubscriptionIntoImmutableProviderEvidence() {
    ControlledStripeTransport transport = new ControlledStripeTransport(VALID_RESPONSE);

    StripeHostingSubscriptionObserver.ObservedSubscription result =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.ObservedSubscription.class,
            observer(transport).observe(expected()));

    assertEquals(PROVIDER_SUBSCRIPTION_ID, result.providerSubscriptionId());
    assertEquals(PROVIDER_CUSTOMER_ID, result.providerCustomerId());
    assertEquals(PROVIDER_PAYMENT_METHOD_ID, result.providerPaymentMethodId());
    assertEquals("si_expected", result.providerSubscriptionItemId());
    assertEquals(PROVIDER_PRICE_ID, result.providerPriceId());
    assertEquals(1L, result.providerQuantity());
    assertEquals(Instant.ofEpochSecond(1_778_000_000), result.currentPeriodStart().orElseThrow());
    assertEquals(Instant.ofEpochSecond(1_780_592_000), result.currentPeriodEnd().orElseThrow());
    assertEquals("active", result.providerStatus());
    assertFalse(result.providerStatusIsCanceled());
    assertFalse(result.cancelAtPeriodEnd());
    assertEquals(false, result.livemode());
    assertEquals("stripe.subscriptions.retrieve", result.source());
    assertEquals(READ_STARTED_AT, result.readStartedAt());
    assertEquals(OBSERVED_AT, result.observedAt());
    assertEquals(
        StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
        result.correlation().operationType());
    assertEquals(UUID.fromString(OPERATION_ID), result.correlation().operationId());
    assertEquals(UUID.fromString(SUBSCRIPTION_ID), result.correlation().subscriptionId());
    assertEquals(OPERATION_ID, result.providerMetadata().get("firemud_operation_id"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.providerMetadata().put("unexpected", "mutation"));
    assertEquals(250, transport.requestOptions.getConnectTimeout());
    assertEquals(1000, transport.requestOptions.getReadTimeout());
    assertEquals(0, transport.requestOptions.getMaxNetworkRetries());
    assertEquals(1, transport.callCount());
    assertEquals(RequestMethod.GET, transport.method());
    assertEquals("/v1/subscriptions/" + PROVIDER_SUBSCRIPTION_ID, transport.path());
  }

  @Test
  void sdkOverridesClientDefaultsAndDoesNotRetryTransportTimeout() {
    var calls = new java.util.ArrayList<StripeRequest>();
    var failure =
        new ApiConnectionException("read timed out", new java.net.SocketTimeoutException());
    var http =
        new HttpClient() {
          @Override
          public StripeResponse request(StripeRequest request) throws StripeException {
            calls.add(request);
            throw failure;
          }
        };
    var client =
        com.stripe.StripeClient.builder()
            .setApiKey("sk_test_observation_fixture")
            .setConnectTimeout(30_000)
            .setReadTimeout(80_000)
            .setMaxNetworkRetries(3)
            .setHttpClient(http)
            .build();
    var observer =
        new StripeHostingSubscriptionObserver(
            client,
            Clock.fixed(READ_STARTED_AT, ZoneOffset.UTC),
            Duration.ofMillis(250),
            Duration.ofSeconds(1));

    var result =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.UnresolvedObservation.class,
            observer.observe(expected()));

    assertSame(failure, result.providerFailure());
    assertEquals(1, calls.size());
    assertEquals(250, calls.getFirst().options().getConnectTimeout());
    assertEquals(1000, calls.getFirst().options().getReadTimeout());
    assertEquals(0, calls.getFirst().options().getMaxNetworkRetries());
  }

  @Test
  void requiresExplicitFiniteWholeMillisecondTimeouts() {
    var client = new com.stripe.StripeClient(new ControlledStripeTransport(VALID_RESPONSE));
    var clock = Clock.fixed(READ_STARTED_AT, ZoneOffset.UTC);
    for (Duration invalid :
        java.util.List.of(
            Duration.ZERO,
            Duration.ofMillis(-1),
            Duration.ofNanos(1),
            Duration.ofNanos(1_000_001),
            Duration.ofMillis((long) Integer.MAX_VALUE + 1),
            Duration.ofSeconds(Long.MAX_VALUE))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new StripeHostingSubscriptionObserver(client, clock, invalid, Duration.ofSeconds(1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new StripeHostingSubscriptionObserver(client, clock, Duration.ofSeconds(1), invalid));
    }
    assertThrows(
        NullPointerException.class,
        () -> new StripeHostingSubscriptionObserver(client, clock, null, Duration.ofSeconds(1)));
    assertThrows(
        NullPointerException.class,
        () -> new StripeHostingSubscriptionObserver(client, clock, Duration.ofSeconds(1), null));
  }

  @Test
  void rejectsMismatchedProviderSubscriptionCustomerInstrumentAndPriceBindings() {
    assertInvalid(
        VALID_RESPONSE.replace(PROVIDER_SUBSCRIPTION_ID, "sub_other"),
        StripeHostingSubscriptionObserver.InvalidReason.SUBSCRIPTION_ID_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace(PROVIDER_CUSTOMER_ID, "cus_other"),
        StripeHostingSubscriptionObserver.InvalidReason.CUSTOMER_ID_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace(PROVIDER_PAYMENT_METHOD_ID, "pm_other"),
        StripeHostingSubscriptionObserver.InvalidReason.PAYMENT_METHOD_ID_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace(PROVIDER_PRICE_ID, "price_other"),
        StripeHostingSubscriptionObserver.InvalidReason.PRICE_ID_MISMATCH);
  }

  @Test
  void requiresExactPositiveServerExpectedSubscriptionItemQuantity() {
    assertInvalid(
        VALID_RESPONSE.replace("\"quantity\":1,", ""),
        StripeHostingSubscriptionObserver.InvalidReason.ITEM_QUANTITY_MISSING);
    assertInvalid(
        VALID_RESPONSE.replace("\"quantity\":1", "\"quantity\":0"),
        StripeHostingSubscriptionObserver.InvalidReason.ITEM_QUANTITY_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace("\"quantity\":1", "\"quantity\":2"),
        StripeHostingSubscriptionObserver.InvalidReason.ITEM_QUANTITY_MISMATCH);
    assertThrows(IllegalArgumentException.class, () -> expected(PROVIDER_SUBSCRIPTION_ID, 0));
  }

  @Test
  void rejectsWrongProviderObjectsAndItemSubscriptionIdentity() {
    assertInvalid(
        VALID_RESPONSE.replace("\"object\":\"subscription\"", "\"object\":\"customer\""),
        StripeHostingSubscriptionObserver.InvalidReason.SUBSCRIPTION_OBJECT_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace("\"object\":\"subscription_item\"", "\"object\":\"price\""),
        StripeHostingSubscriptionObserver.InvalidReason.ITEM_OBJECT_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace(
            "\"subscription\":\"sub_expected\"", "\"subscription\":\"sub_other\""),
        StripeHostingSubscriptionObserver.InvalidReason.ITEM_SUBSCRIPTION_MISMATCH);
  }

  @Test
  void rejectsMissingMismatchedAndNoncanonicalTypedCorrelation() {
    assertInvalid(
        VALID_RESPONSE.replace("\"firemud_operation_id\":\"" + OPERATION_ID + "\",", ""),
        StripeHostingSubscriptionObserver.InvalidReason.METADATA_UUID_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace(OPERATION_ID, "6f66ef0d-a8ec-46eb-901e-e795e21d3d0a"),
        StripeHostingSubscriptionObserver.InvalidReason.OPERATION_ID_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace(SUBSCRIPTION_ID, "00000000-0000-0000-0000-000000000000"),
        StripeHostingSubscriptionObserver.InvalidReason.METADATA_UUID_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace("firemud_operation_type", "firemud_untyped_operation"),
        StripeHostingSubscriptionObserver.InvalidReason.OPERATION_TYPE_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace(
            "\"firemud_metadata_schema_version\":\"1\"",
            "\"firemud_metadata_schema_version\":\"2\""),
        StripeHostingSubscriptionObserver.InvalidReason.METADATA_SCHEMA_UNSUPPORTED);
  }

  @Test
  void rejectsIncompleteOrMultipleSubscriptionItems() {
    assertInvalid(
        VALID_RESPONSE.replace("\"has_more\":false", "\"has_more\":true"),
        StripeHostingSubscriptionObserver.InvalidReason.ITEMS_INCOMPLETE);
    assertInvalid(
        replaceItemData(VALID_RESPONSE, ""),
        StripeHostingSubscriptionObserver.InvalidReason.ITEM_CARDINALITY_UNSUPPORTED);
    String secondItem =
        "{\"id\":\"si_second\",\"object\":\"subscription_item\","
            + "\"subscription\":\"sub_expected\",\"quantity\":1,"
            + "\"current_period_start\":1778000000,"
            + "\"current_period_end\":1780592000,\"price\":{\"id\":\"price_expected\","
            + "\"object\":\"price\"}}";
    String twoItems = replaceItemData(VALID_RESPONSE, singleItemData() + "," + secondItem);
    assertInvalid(
        twoItems, StripeHostingSubscriptionObserver.InvalidReason.ITEM_CARDINALITY_UNSUPPORTED);
  }

  @Test
  void neverFallsBackToCustomerOrLegacyDefaultPaymentInstrument() {
    String noExplicitMethod =
        VALID_RESPONSE.replace(
            "\"default_payment_method\":\"pm_expected\"",
            "\"default_payment_method\":null,\"default_source\":\"src_legacy\"");

    assertInvalid(
        noExplicitMethod,
        StripeHostingSubscriptionObserver.InvalidReason.EXPLICIT_PAYMENT_METHOD_MISSING);
  }

  @Test
  void rejectsUnexpectedModeUnsupportedStatusAndMalformedPeriodEvidence() {
    assertInvalid(
        VALID_RESPONSE.replace("\"livemode\":false", "\"livemode\":true"),
        StripeHostingSubscriptionObserver.InvalidReason.LIVEMODE_MISMATCH);
    assertInvalid(
        VALID_RESPONSE.replace("\"status\":\"active\"", "\"status\":\"future_state\""),
        StripeHostingSubscriptionObserver.InvalidReason.UNSUPPORTED_PROVIDER_STATUS);
    assertInvalid(
        VALID_RESPONSE.replace("\"current_period_start\":1778000000", "\"current_period_start\":0"),
        StripeHostingSubscriptionObserver.InvalidReason.PERIOD_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace(
            "\"current_period_end\":1780592000", "\"current_period_end\":1778000000"),
        StripeHostingSubscriptionObserver.InvalidReason.PERIOD_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace("\"current_period_start\":1778000000,", ""),
        StripeHostingSubscriptionObserver.InvalidReason.PERIOD_MISSING);
  }

  @Test
  void retainsCanceledTerminalEvidenceWithoutRequiringCurrentPeriodBoundaries() {
    String canceled =
        VALID_RESPONSE
            .replace("\"status\":\"active\"", "\"status\":\"canceled\"")
            .replace("\"current_period_start\":1778000000", "\"current_period_start\":null")
            .replace("\"current_period_end\":1780592000", "\"current_period_end\":null")
            .replace("\"ended_at\":null", "\"ended_at\":1780592000")
            .replace("\"canceled_at\":null", "\"canceled_at\":1780592000");

    StripeHostingSubscriptionObserver.ObservedSubscription result =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.ObservedSubscription.class,
            observer(new ControlledStripeTransport(canceled)).observe(expected()));

    assertEquals("canceled", result.providerStatus());
    assertTrue(result.providerStatusIsCanceled());
    assertTrue(result.currentPeriodStart().isEmpty());
    assertTrue(result.currentPeriodEnd().isEmpty());
    assertEquals(Instant.ofEpochSecond(1_780_592_000), result.endedAt().orElseThrow());
    assertEquals(Instant.ofEpochSecond(1_780_592_000), result.canceledAt().orElseThrow());
  }

  @Test
  void retainsIncompleteExpiredAsDistinctRawTerminalEvidenceWithoutPeriodBoundaries() {
    String expired =
        VALID_RESPONSE
            .replace("\"status\":\"active\"", "\"status\":\"incomplete_expired\"")
            .replace("\"current_period_start\":1778000000", "\"current_period_start\":null")
            .replace("\"current_period_end\":1780592000", "\"current_period_end\":null");

    StripeHostingSubscriptionObserver.ObservedSubscription result =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.ObservedSubscription.class,
            observer(new ControlledStripeTransport(expired)).observe(expected()));

    assertEquals("incomplete_expired", result.providerStatus());
    assertFalse(result.providerStatusIsCanceled());
    assertTrue(result.providerStatusIsIncompleteExpired());
    assertTrue(result.currentPeriodStart().isEmpty());
    assertTrue(result.currentPeriodEnd().isEmpty());
  }

  @Test
  void rejectsContradictoryCanceledPeriodBoundariesWhenBothArePresent() {
    String reversed =
        VALID_RESPONSE
            .replace("\"status\":\"active\"", "\"status\":\"canceled\"")
            .replace(
                "\"current_period_start\":1778000000", "\"current_period_start\":__PERIOD_START__")
            .replace("\"current_period_end\":1780592000", "\"current_period_end\":1778000000")
            .replace("__PERIOD_START__", "1780592000");
    String equal =
        VALID_RESPONSE
            .replace("\"status\":\"active\"", "\"status\":\"canceled\"")
            .replace("\"current_period_end\":1780592000", "\"current_period_end\":1778000000");

    assertInvalid(reversed, StripeHostingSubscriptionObserver.InvalidReason.PERIOD_INVALID);
    assertInvalid(equal, StripeHostingSubscriptionObserver.InvalidReason.PERIOD_INVALID);
  }

  @Test
  void rejectsReversedLocalReadIntervalAsInvalidEvidence() {
    StripeHostingSubscriptionObserver.InvalidObservation result =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.InvalidObservation.class,
            observer(
                    new ControlledStripeTransport(VALID_RESPONSE),
                    tickingClock(OBSERVED_AT, READ_STARTED_AT))
                .observe(expected()));

    assertEquals(
        StripeHostingSubscriptionObserver.InvalidReason.READ_INTERVAL_REVERSED, result.reason());
    assertEquals(OBSERVED_AT, result.readStartedAt());
    assertEquals(READ_STARTED_AT, result.observedAt());
  }

  @Test
  void rejectsMalformedCancellationTimestampAndMissingCancelMetadata() {
    String badTimestamp = VALID_RESPONSE.replace("\"ended_at\":null", "\"ended_at\":-1");
    assertInvalid(
        badTimestamp, StripeHostingSubscriptionObserver.InvalidReason.CANCEL_TIMESTAMP_INVALID);
    assertInvalid(
        VALID_RESPONSE.replace("\"cancel_at_period_end\":false,", ""),
        StripeHostingSubscriptionObserver.InvalidReason.CANCEL_METADATA_MISSING);
  }

  @Test
  void timeoutAndRateLimitRemainUnresolvedWithoutObserverRetry() {
    for (StripeException failure :
        java.util.List.of(
            new ApiConnectionException("read timed out", new java.net.SocketTimeoutException()),
            new RateLimitException("rate limited", null, "req_test", "rate_limit", 429, null))) {
      var transport = new ControlledStripeTransport(failure);
      var result =
          assertInstanceOf(
              StripeHostingSubscriptionObserver.UnresolvedObservation.class,
              observer(transport).observe(expected()));
      assertSame(failure, result.providerFailure());
      assertTrue(result.retryable());
      assertEquals(1, transport.callCount());
      assertEquals(250, transport.requestOptions.getConnectTimeout());
      assertEquals(1000, transport.requestOptions.getReadTimeout());
      assertEquals(0, transport.requestOptions.getMaxNetworkRetries());
    }
  }

  @Test
  void unknownProviderIdAndProviderReadFailureRemainUnresolvedWithTypedCause() {
    StripeException unknownIdFailure = mock(StripeException.class);
    ControlledStripeTransport transport = new ControlledStripeTransport(unknownIdFailure);

    StripeHostingSubscriptionObserver.UnresolvedObservation result =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.UnresolvedObservation.class,
            observer(transport).observe(expected("sub_unknown")));

    assertEquals("sub_unknown", result.providerSubscriptionId());
    assertTrue(result.retryable());
    assertSame(unknownIdFailure, result.providerFailure());
    assertEquals(250, transport.requestOptions.getConnectTimeout());
    assertEquals(1000, transport.requestOptions.getReadTimeout());
    assertEquals(0, transport.requestOptions.getMaxNetworkRetries());
    assertEquals(1, transport.callCount());
  }

  @Test
  void rejectsNilExpectedInternalIdentity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StripeHostingSubscriptionObserver.ExpectedBinding(
                PROVIDER_SUBSCRIPTION_ID,
                new UUID(0, 0),
                PROVIDER_CUSTOMER_ID,
                PROVIDER_PAYMENT_METHOD_ID,
                PROVIDER_PRICE_ID,
                1,
                StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
                UUID.fromString(OPERATION_ID),
                false));
  }

  private static void assertInvalid(
      String response, StripeHostingSubscriptionObserver.InvalidReason expectedReason) {
    StripeHostingSubscriptionObserver.InvalidObservation invalid =
        assertInstanceOf(
            StripeHostingSubscriptionObserver.InvalidObservation.class,
            observer(new ControlledStripeTransport(response)).observe(expected()));
    assertEquals(expectedReason, invalid.reason());
  }

  private static StripeHostingSubscriptionObserver observer(ControlledStripeTransport transport) {
    return observer(transport, tickingClock());
  }

  private static Clock tickingClock() {
    return tickingClock(READ_STARTED_AT, OBSERVED_AT);
  }

  private static Clock tickingClock(Instant firstInstant, Instant secondInstant) {
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
        return calls++ == 0 ? firstInstant : secondInstant;
      }
    };
  }

  private static StripeHostingSubscriptionObserver observer(
      ControlledStripeTransport transport, Clock clock) {
    return new StripeHostingSubscriptionObserver(
        new com.stripe.StripeClient(transport),
        clock,
        Duration.ofMillis(250),
        Duration.ofSeconds(1));
  }

  private static StripeHostingSubscriptionObserver.ExpectedBinding expected() {
    return expected(PROVIDER_SUBSCRIPTION_ID);
  }

  private static StripeHostingSubscriptionObserver.ExpectedBinding expected(
      String providerSubscriptionId) {
    return expected(providerSubscriptionId, 1);
  }

  private static StripeHostingSubscriptionObserver.ExpectedBinding expected(
      String providerSubscriptionId, long expectedQuantity) {
    return new StripeHostingSubscriptionObserver.ExpectedBinding(
        providerSubscriptionId,
        UUID.fromString(SUBSCRIPTION_ID),
        PROVIDER_CUSTOMER_ID,
        PROVIDER_PAYMENT_METHOD_ID,
        PROVIDER_PRICE_ID,
        expectedQuantity,
        StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
        UUID.fromString(OPERATION_ID),
        false);
  }

  private static String validResponse() {
    return """
        {"id":"sub_expected","object":"subscription","customer":"cus_expected",
         "default_payment_method":"pm_expected","default_source":"src_ignored",
         "livemode":false,"status":"active","cancel_at_period_end":false,"cancel_at":null,
         "canceled_at":null,"ended_at":null,
         "metadata":{"firemud_metadata_schema_version":"1",
          "firemud_operation_type":"subscription_provisioning",
          "firemud_operation_id":"4e2af076-4a10-4aaf-93a0-e5df29271310",
          "firemud_subscription_id":"0c9ee07d-0e6d-4f66-9a87-54110acbc611",
          "retained_marker":"evidence"},
         "items":{"object":"list","data":[{"id":"si_expected","object":"subscription_item",
          "subscription":"sub_expected","quantity":1,"current_period_start":1778000000,
          "current_period_end":1780592000,"price":{"id":"price_expected","object":"price"}}],
          "has_more":false}}
        """;
  }

  private static String singleItemData() {
    return "{\"id\":\"si_expected\",\"object\":\"subscription_item\","
        + "\"subscription\":\"sub_expected\",\"quantity\":1,"
        + "\"current_period_start\":1778000000,"
        + "\"current_period_end\":1780592000,\"price\":{\"id\":\"price_expected\","
        + "\"object\":\"price\"}}";
  }

  private static String replaceItemData(String response, String itemData) {
    String dataMarker = "\"data\":[";
    int dataStart = response.indexOf(dataMarker);
    int dataEnd = response.indexOf("],", dataStart);
    if (dataStart < 0 || dataEnd < 0) {
      throw new IllegalStateException("test response has no subscription item data list");
    }
    return response.substring(0, dataStart) + dataMarker + itemData + response.substring(dataEnd);
  }

  private static final class ControlledStripeTransport implements StripeResponseGetter {
    private final String responseJson;
    private final StripeException failure;
    private int callCount;
    private RequestMethod method;
    private String path;
    private RequestOptions requestOptions;

    private ControlledStripeTransport(String responseJson) {
      this.responseJson = responseJson;
      this.failure = null;
    }

    private ControlledStripeTransport(StripeException failure) {
      this.responseJson = null;
      this.failure = failure;
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
      requestOptions = options;
      method = requestMethod;
      path = requestPath;
      if (failure != null) {
        throw failure;
      }
      return (T) ApiResource.GSON.fromJson(responseJson, type);
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
      throw new AssertionError("subscription retrieve must not use a streaming request");
    }

    private int callCount() {
      return callCount;
    }

    private RequestMethod method() {
      return method;
    }

    private String path() {
      return path;
    }
  }
}
