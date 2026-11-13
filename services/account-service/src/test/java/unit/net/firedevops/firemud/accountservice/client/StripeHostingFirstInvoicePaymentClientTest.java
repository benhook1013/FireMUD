package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePayment;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.CommandClaim;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual SDK parsing and confirmation through controlled transport, not a live Stripe charge. */
class StripeHostingFirstInvoicePaymentClientTest {
  private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
  private static final UUID KEY = UUID.randomUUID();
  private static final AccountHostingFirstInvoicePayment INVOICE =
      new AccountHostingFirstInvoicePayment(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          1,
          "sub_exact",
          "cus_exact",
          "pm_exact",
          "in_exact",
          "pi_exact",
          2400,
          "usd",
          "sha256:" + "a".repeat(64),
          "price_exact",
          1,
          1,
          "sha256:" + "b".repeat(64),
          false,
          NOW.minusSeconds(1),
          NOW.plusSeconds(1000));

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void exactExistingIntentIsConfirmedOnlyAfterReadWithRetainedKeyAndSelectedMethod()
      throws Exception {
    var transport =
        new Transport(
            invoice("requires_confirmation"),
            intent("requires_confirmation"),
            intent("requires_action"));
    var response =
        client(transport).confirm(dispatch(), StripeHostingFirstInvoicePaymentClientTest::claim);
    assertThat(response.status()).isEqualTo("requires_action");
    assertThat(response.clientSecret()).isEqualTo("pi_exact_secret_controlled");
    assertThat(response.toString()).doesNotContain("secret_controlled");
    assertThat(transport.paths)
        .containsExactly(
            "/v1/invoices/in_exact",
            "/v1/payment_intents/pi_exact",
            "/v1/payment_intents/pi_exact/confirm");
    assertThat(transport.methods)
        .containsExactly(RequestMethod.GET, RequestMethod.GET, RequestMethod.POST);
    assertThat(transport.params.get(2))
        .containsEntry("payment_method", "pm_exact")
        .containsEntry("use_stripe_sdk", true);
    assertThat(transport.options.get(2).getIdempotencyKey()).isEqualTo(KEY.toString());
    assertThat(transport.options)
        .allSatisfy(options -> assertThat(options.getMaxNetworkRetries()).isZero());
  }

  @ParameterizedTest
  @ValueSource(strings = {"requires_action", "processing", "succeeded"})
  void recoveryOfAlreadyDispatchedIntentNeverConfirmsOrCreatesAnother(String status)
      throws Exception {
    var transport = new Transport(invoice(status), intent(status));
    var handoff =
        client(transport).confirm(dispatch(), StripeHostingFirstInvoicePaymentClientTest::claim);
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
    assertThat(handoff.status()).isEqualTo(status);
    assertThat(handoff.clientSecret())
        .isEqualTo("requires_action".equals(status) ? "pi_exact_secret_controlled" : null);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"pm_other", "cus_other", "eur", "manual", "in_other", "sub_other", "price_other"})
  void mismatchedProviderTupleCannotReachConfirmation(String replacement) {
    String response = invoice("requires_confirmation");
    String needle =
        switch (replacement) {
          case "pm_other" -> "pm_exact";
          case "cus_other" -> "cus_exact";
          case "eur" -> "usd";
          case "manual" -> "automatic";
          case "in_other" -> "in_exact";
          case "price_other" -> "price_exact";
          default -> "sub_exact";
        };
    var transport = new Transport(response.replace(needle, replacement));
    assertThatThrownBy(
            () ->
                client(transport)
                    .confirm(dispatch(), StripeHostingFirstInvoicePaymentClientTest::claim))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transport.methods).containsExactly(RequestMethod.GET);
  }

  @Test
  void lostResponseOrOwnerRetryCannotDispatchASecondConfirmation() throws Exception {
    var transport =
        new Transport(invoice("requires_confirmation"), intent("requires_confirmation"));
    var original = dispatch();
    when(original.takeInitialCommandPermission()).thenReturn(false);
    assertThat(
            client(transport)
                .confirm(original, StripeHostingFirstInvoicePaymentClientTest::claim)
                .status())
        .isEqualTo("AUTHORIZED_NOT_CONFIRMED");
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
  }

  @Test
  void lostConfirmationResponseRecoversExistingActionWithoutRepeatingProviderPost()
      throws Exception {
    var transport =
        new Transport(
            invoice("requires_confirmation"),
            intent("requires_confirmation"),
            "lost-response",
            invoice("requires_action"),
            intent("requires_action"));
    transport.loseConfirmationResponse = true;
    var original = dispatch();
    when(original.takeInitialCommandPermission()).thenReturn(true, false);
    var provider = client(transport);
    assertThatThrownBy(
            () -> provider.confirm(original, StripeHostingFirstInvoicePaymentClientTest::claim))
        .isInstanceOf(com.stripe.exception.ApiConnectionException.class);
    assertThat(
            provider.confirm(original, StripeHostingFirstInvoicePaymentClientTest::claim).status())
        .isEqualTo("requires_action");
    assertThat(transport.methods.stream().filter(RequestMethod.POST::equals).count()).isOne();
    assertThat(transport.paths)
        .allMatch(path -> !path.contains("checkout") && !path.endsWith("/payment_intents"));
  }

  @Test
  void changedSeparatelyRetrievedIntentCannotBeConfirmedAfterExactInvoiceRead() {
    var transport =
        new Transport(
            invoice("requires_confirmation"),
            intent("requires_confirmation").replace("\"amount\":2400", "\"amount\":2500"));
    assertThatThrownBy(
            () ->
                client(transport)
                    .confirm(dispatch(), StripeHostingFirstInvoicePaymentClientTest::claim))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
  }

  @Test
  void insufficientAuthorizationWindowAfterProviderReadsLeavesCommandUnclaimed() {
    var transport =
        new Transport(invoice("requires_confirmation"), intent("requires_confirmation"));
    var dispatch = dispatch();
    when(dispatch.validUntil()).thenReturn(NOW.plusSeconds(1));
    assertThatThrownBy(
            () ->
                client(transport)
                    .confirm(
                        dispatch,
                        () -> {
                          throw new AssertionError("Expired dispatch budget reached the claim");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
  }

  @Test
  void expiryAfterClaimBeforePostKeepsOriginalCommandHeldAcrossRetries() throws Exception {
    var transport =
        new Transport(
            invoice("requires_confirmation"),
            intent("requires_confirmation"),
            invoice("requires_confirmation"),
            intent("requires_confirmation"));
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(NOW);
    var provider = client(transport, clock);
    var original = dispatch();
    Instant expiredAt = original.validUntil().plusSeconds(1);
    var originalClaim = claim();
    var held = new AtomicBoolean();
    Supplier<CommandClaim> claimCommand =
        () -> {
          if (!held.compareAndSet(false, true)) return null;
          when(clock.instant()).thenReturn(expiredAt);
          return originalClaim;
        };

    assertThatThrownBy(() -> provider.confirm(original, claimCommand))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Exact existing first-invoice provider binding refused");
    verify(originalClaim).takePermission(original);
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);

    assertThatThrownBy(() -> provider.confirm(original, claimCommand))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Committed current billing-owner consent required");
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);

    // Fresh consent keeps the original invoice and provider key, and cannot reclaim its command.
    var renewed = dispatch();
    when(renewed.validUntil()).thenReturn(NOW.plusSeconds(240));
    var handoff = provider.confirm(renewed, claimCommand);
    assertThat(handoff.status()).isEqualTo("AUTHORIZED_NOT_CONFIRMED");
    assertThat(handoff.clientSecret()).isNull();
    assertThat(transport.methods)
        .containsExactly(
            RequestMethod.GET, RequestMethod.GET, RequestMethod.GET, RequestMethod.GET);
  }

  @Test
  void expiryAfterDispatchDoesNotPermitAnotherCommandOrClientSecretDisclosure() {
    var transport = new Transport();
    var original = dispatch();
    when(original.validUntil()).thenReturn(NOW);
    when(original.takeInitialCommandPermission()).thenReturn(false);
    assertThatThrownBy(
            () ->
                client(transport)
                    .confirm(original, StripeHostingFirstInvoicePaymentClientTest::claim))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transport.methods).isEmpty();
  }

  @Test
  void creditBalancePartialPaymentAndAmountChangesRefuseRatherThanFallback() {
    for (String response :
        List.of(
            invoice("succeeded").replace("\"amount_received\":2400", "\"amount_received\":2300"),
            invoice("requires_confirmation").replace("\"amount_due\":2400", "\"amount_due\":2300"),
            invoice("requires_confirmation").replace("\"has_more\":false", "\"has_more\":true"),
            invoice("requires_confirmation")
                .replace("\"starting_balance\":0", "\"starting_balance\":100"),
            invoice("requires_confirmation").replace("\"total\":2400", "\"total\":2500"),
            invoice("requires_confirmation").replace("\"quantity\":1", "\"quantity\":2"),
            invoice("requires_confirmation")
                .replace("\"amount_remaining\":2400", "\"amount_remaining\":2300"))) {
      var transport = new Transport(response);
      assertThatThrownBy(() -> client(transport).observe(INVOICE))
          .isInstanceOf(IllegalStateException.class);
      assertThat(transport.methods).containsExactly(RequestMethod.GET);
    }
  }

  @Test
  void forgedUncommittedDispatchAndAmbientSqlRefuseBeforeProviderIo() {
    var transport = new Transport();
    assertThatThrownBy(
            () ->
                client(transport)
                    .confirm(
                        mock(ConfirmationDispatch.class),
                        StripeHostingFirstInvoicePaymentClientTest::claim))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client(transport).observe(INVOICE))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transport.paths).isEmpty();
  }

  private static net.firedevops.firemud.accountservice.repository
          .AccountHostingFirstInvoicePaymentRepository.CommandClaim
      claim() {
    var claim =
        mock(
            net.firedevops.firemud.accountservice.repository
                .AccountHostingFirstInvoicePaymentRepository.CommandClaim.class);
    when(claim.takePermission(org.mockito.ArgumentMatchers.any())).thenReturn(true, false);
    return claim;
  }

  private static ConfirmationDispatch dispatch() {
    var dispatch = mock(ConfirmationDispatch.class);
    when(dispatch.invoice()).thenReturn(INVOICE);
    when(dispatch.independentlyRead()).thenReturn(true);
    when(dispatch.takeInitialCommandPermission()).thenReturn(true);
    when(dispatch.providerIdempotencyId()).thenReturn(KEY);
    when(dispatch.validUntil()).thenReturn(NOW.plusSeconds(120));
    return dispatch;
  }

  @Test
  void freshAuthorizationCanRecoverBeforeClaimButCannotResendAnAmbiguousClaim() throws Exception {
    var transport =
        new Transport(
            invoice("requires_confirmation"),
            intent("requires_confirmation"),
            intent("requires_action"),
            invoice("requires_confirmation"),
            intent("requires_confirmation"));
    var provider = client(transport);
    var claimed = new java.util.concurrent.atomic.AtomicBoolean();
    // A new authorization is still the same original invoice and provider idempotency identity.
    assertThat(
            provider
                .confirm(dispatch(), () -> claimed.compareAndSet(false, true) ? claim() : null)
                .status())
        .isEqualTo("requires_action");
    assertThat(
            provider
                .confirm(dispatch(), () -> claimed.compareAndSet(false, true) ? claim() : null)
                .status())
        .isEqualTo("AUTHORIZED_NOT_CONFIRMED");
    assertThat(transport.methods.stream().filter(RequestMethod.POST::equals).count()).isOne();
    assertThat(transport.options.get(2).getIdempotencyKey()).isEqualTo(KEY.toString());
  }

  @Test
  void failedOrUncertainDurableClaimNeverSendsProviderCommand() {
    var transport =
        new Transport(invoice("requires_confirmation"), intent("requires_confirmation"));
    assertThatThrownBy(
            () ->
                client(transport)
                    .confirm(
                        dispatch(),
                        () -> {
                          throw new IllegalStateException("claim commit unknown");
                        }))
        .hasMessageContaining("claim commit unknown");
    assertThat(transport.methods).containsExactly(RequestMethod.GET, RequestMethod.GET);
  }

  private static StripeHostingFirstInvoicePaymentClient client(Transport transport) {
    return client(transport, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static StripeHostingFirstInvoicePaymentClient client(Transport transport, Clock clock) {
    return new StripeHostingFirstInvoicePaymentClient(
        new com.stripe.StripeClient(transport),
        clock,
        Duration.ofMillis(250),
        Duration.ofSeconds(1));
  }

  private static String intent(String status) {
    return "{\"object\":\"payment_intent\",\"id\":\"pi_exact\",\"customer\":\"cus_exact\",\"payment_method\":\"pm_exact\","
        + "\"amount\":2400,\"amount_received\":"
        + ("succeeded".equals(status) ? 2400 : 0)
        + ",\"currency\":\"usd\",\"livemode\":false,"
        + "\"confirmation_method\":\"automatic\",\"client_secret\":\"pi_exact_secret_controlled\",\"status\":\""
        + status
        + "\"}";
  }

  private static String invoice(String status) {
    boolean paid = "succeeded".equals(status);
    return "{\"object\":\"invoice\",\"id\":\"in_exact\",\"customer\":\"cus_exact\",\"livemode\":false,\"billing_reason\":\"subscription_create\","
        + "\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{\"subscription\":\"sub_exact\"}},\"total\":2400,\"starting_balance\":0,\"amount_due\":2400,\"amount_paid\":"
        + (paid ? 2400 : 0)
        + ",\"amount_remaining\":"
        + (paid ? 0 : 2400)
        + ",\"currency\":\"usd\",\"status\":\""
        + (paid ? "paid" : "open")
        + "\",\"lines\":{\"has_more\":false,\"data\":[{\"object\":\"line_item\",\"amount\":2400,\"currency\":\"usd\",\"quantity\":1,\"pricing\":{\"type\":\"price_details\",\"price_details\":{\"price\":\"price_exact\"}}}]},\"payments\":{\"object\":\"list\",\"has_more\":false,\"data\":[{\"object\":\"invoice_payment\",\"id\":\"inpay_exact\","
        + "\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":"
        + intent(status)
        + "}}]}}";
  }

  private static final class Transport implements StripeResponseGetter {
    private final List<String> responses;
    private boolean loseConfirmationResponse;
    private final List<String> paths = new ArrayList<>();
    private final List<RequestMethod> methods = new ArrayList<>();
    private final List<Map<String, Object>> params = new ArrayList<>();
    private final List<RequestOptions> options = new ArrayList<>();

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
        throws com.stripe.exception.StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      int index = paths.size();
      paths.add(path);
      methods.add(method);
      params.add(parameters == null ? Map.of() : Map.copyOf(parameters));
      options.add(requestOptions);
      if (loseConfirmationResponse && method == RequestMethod.POST) {
        loseConfirmationResponse = false;
        throw new com.stripe.exception.ApiConnectionException(
            "Controlled lost confirmation response");
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
      throw new AssertionError("Streaming is unsupported");
    }
  }
}
