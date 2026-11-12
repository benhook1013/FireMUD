package net.firedevops.firemud.springcloudgateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Authenticated;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Request;
import net.firedevops.firemud.springcloudgateway.client.AccountCheckoutHttpClient;
import net.firedevops.firemud.springcloudgateway.client.AccountControlUiAuthenticationClient;
import net.firedevops.firemud.springcloudgateway.config.AccountCheckoutReceivingProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import net.firedevops.firemud.springcloudgateway.filter.HeaderTrustFilter;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AccountCheckoutGatewayControllerTest {
  private static final UUID REQUEST_ID = UUID.fromString("1ca7f2e5-1362-4ac8-9312-38bbf73bc33f");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("af64ad5f-e9ee-48a1-9b5d-6fb28845ff71");
  private static final UUID ACCOUNT_ID = UUID.fromString("56f945f8-b85e-45e1-8254-69569203fc67");
  private static final UUID SUBSCRIPTION_ID =
      UUID.fromString("927fb6a8-3975-4cba-8145-c2a8f4a9b0d1");
  private static final String SECRET = "do-not-reflect-primary-secret";
  private static final String TOKEN = "header.payload.signature";

  @Test
  void loginRequiresActualTlsAndReturnsNoStoreSuccessWithoutEchoingSecret() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var properties = properties();
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties);
    properties.setWorkloadNamespace("changed-after-controller-construction");
    when(authClient.login(any()))
        .thenReturn(
            new AccountControlUiAuthenticationGrpcCodec.Result(
                AccountControlUiAuthenticationGrpcCodec.Outcome.SUCCESS,
                new Authenticated(ACCOUNT_ID, TOKEN, 1_900_000_000L)));
    String body = loginJson();

    var response = controller.login(secureExchange("/api/account/auth/login", body)).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
    assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
        .contains("\"status\":\"SUCCESS\"")
        .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
        .contains("\"authToken\":\"" + TOKEN + "\"")
        .contains("\"expiresAt\":\"2030-03-17T17:46:40Z\"")
        .doesNotContain(SECRET);
    var requestCaptor = org.mockito.ArgumentCaptor.forClass(Request.class);
    verify(authClient).login(requestCaptor.capture());
    assertThat(requestCaptor.getValue().requestId()).isEqualTo(REQUEST_ID);
    assertThat(requestCaptor.getValue().callerContextId()).isEqualTo(CALLER_CONTEXT_ID);
    assertThat(requestCaptor.getValue().callerNamespace()).isEqualTo("checkout-test");
    assertThat(requestCaptor.getValue().secret()).isEqualTo(SECRET);
    assertThat(requestCaptor.getValue().sourceContext().canonicalClientAddress())
        .isEqualTo("192.0.2.10");
    assertThat(requestCaptor.getValue().toString()).doesNotContain(SECRET);
    verifyNoCheckoutCalls(checkoutClient);
  }

  @Test
  void canonicalSourceComesFromTrustFilterAndNeverFromUntrustedHeaders() {
    for (boolean trustedForwarder : new boolean[] {false, true}) {
      var authClient = mock(AccountControlUiAuthenticationClient.class);
      var checkoutClient = mock(AccountCheckoutHttpClient.class);
      var controller =
          new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
      when(authClient.login(any()))
          .thenReturn(
              new AccountControlUiAuthenticationGrpcCodec.Result(
                  AccountControlUiAuthenticationGrpcCodec.Outcome.DENIED, null));
      var headers = new GatewayHeaderTrustProperties();
      if (trustedForwarder)
        headers.getForwardedClientIp().setTrustedProxyCidrs(java.util.List.of("192.0.2.0/24"));
      var request =
          MockServerHttpRequest.post("https://localhost/api/account/auth/login")
              .sslInfo(mock(SslInfo.class))
              .remoteAddress(new java.net.InetSocketAddress("192.0.2.10", 443))
              .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
              .header("X-Client-IP", "203.0.113.99")
              .header("X-Forwarded-For", "198.51.100.20")
              .header("X-Proxy-Client-IP", "203.0.113.98")
              .body(loginJson());
      var response =
          controller.login(canonicalized(MockServerWebExchange.from(request), headers)).block();
      assertThat(response).isNotNull();
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      var captured = org.mockito.ArgumentCaptor.forClass(Request.class);
      verify(authClient).login(captured.capture());
      assertThat(captured.getValue().sourceContext().canonicalClientAddress())
          .isEqualTo(trustedForwarder ? "198.51.100.20" : "192.0.2.10");
    }
  }

  @Test
  void absentFilterProofOrUnresolvedPeerRefusesBeforeCredentialForwarding() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
    var request =
        MockServerHttpRequest.post("https://localhost/api/account/auth/login")
            .sslInfo(mock(SslInfo.class))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header("X-Client-IP", "192.0.2.10")
            .header("X-Forwarded-For", "192.0.2.10")
            .body(loginJson());
    for (ServerWebExchange exchange :
        java.util.List.of(
            MockServerWebExchange.from(request),
            canonicalized(
                MockServerWebExchange.from(request), new GatewayHeaderTrustProperties()))) {
      var response = controller.login(exchange).block();
      assertThat(response).isNotNull();
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
    verify(authClient, never()).login(any());
    verifyNoCheckoutCalls(checkoutClient);
  }

  @Test
  void rejectsForwardedProtoAsSubstituteForARealTlsSession() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
    var request =
        MockServerHttpRequest.post("/api/account/auth/login")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header("X-Forwarded-Proto", "https")
            .body(loginJson());

    var response = controller.login(MockServerWebExchange.from(request)).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    verify(authClient, never()).login(any());
    verifyNoCheckoutCalls(checkoutClient);
  }

  @Test
  void mapsRetainedRequestInputConflictsToSafeHttpConflict() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
    when(authClient.login(any()))
        .thenThrow(new AccountControlUiAuthenticationClient.AuthenticationConflictException());

    var response = controller.login(secureExchange("/api/account/auth/login", loginJson())).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
        .contains("\"status\":\"CONFLICT\"")
        .doesNotContain(SECRET);
    verifyNoCheckoutCalls(checkoutClient);
  }

  @Test
  void redactsCredentialAndTokenFromTransientLoginRecordDiagnostics() throws Exception {
    Class<?> dataType =
        Class.forName(AccountCheckoutGatewayController.class.getName() + "$LoginData");
    var dataConstructor = dataType.getDeclaredConstructor(String.class, String.class, String.class);
    dataConstructor.setAccessible(true);
    Object data = dataConstructor.newInstance(ACCOUNT_ID.toString(), TOKEN, "2030-03-17T17:46:40Z");

    Class<?> successType =
        Class.forName(AccountCheckoutGatewayController.class.getName() + "$LoginSuccess");
    var successConstructor = successType.getDeclaredConstructor(String.class, dataType);
    successConstructor.setAccessible(true);
    Object success = successConstructor.newInstance("SUCCESS", data);

    assertThat(data.toString()).doesNotContain(TOKEN);
    assertThat(success.toString()).doesNotContain(TOKEN).doesNotContain(SECRET);
  }

  @Test
  void rejectsDuplicateUnknownAndOversizedLoginJsonWithoutCallingAccount() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
    for (String body :
        new String[] {
          loginJson()
              .replace("\"secret\":", "\"secret\":\"first\",\"secret\":")
              .replace("\"do-not-reflect-primary-secret\"", "\"second\""),
          loginJson().replace("\"secret\":", "\"unexpected\":true,\"secret\":")
        }) {
      var response = controller.login(secureExchange("/api/account/auth/login", body)).block();
      assertThat(response).isNotNull();
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).doesNotContain(SECRET);
    }

    var oversized =
        controller
            .login(secureExchange("/api/account/auth/login", "x".repeat(8 * 1024 + 1)))
            .block();

    assertThat(oversized).isNotNull();
    assertThat(oversized.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    verify(authClient, never()).login(any());
    verifyNoCheckoutCalls(checkoutClient);
  }

  @Test
  void forwardsOnlyExactCheckoutRoutesAndSetsNoStoreOnProxyResponses() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
    byte[] payload = "{\"acceptCharge\":true}".getBytes(StandardCharsets.UTF_8);
    when(checkoutClient.forward(
            eq(HttpMethod.POST),
            eq("/billing/hosting/subscriptions/" + SUBSCRIPTION_ID + "/first-invoice/confirm"),
            any(),
            any()))
        .thenReturn(
            Mono.just(
                new AccountCheckoutHttpClient.ForwardedResponse(
                    HttpStatus.OK, MediaType.APPLICATION_JSON_VALUE, payload)));
    var request =
        MockServerHttpRequest.method(
                HttpMethod.POST,
                URI.create(
                    "https://localhost/api/account/billing/hosting/subscriptions/"
                        + SUBSCRIPTION_ID
                        + "/first-invoice/confirm"))
            .sslInfo(mock(SslInfo.class))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer caller-token")
            .body(new String(payload, StandardCharsets.UTF_8));

    var response =
        controller
            .confirmFirstInvoice(SUBSCRIPTION_ID.toString(), MockServerWebExchange.from(request))
            .block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(response.getBody()).isEqualTo(payload);
    verify(checkoutClient)
        .forward(
            eq(HttpMethod.POST),
            eq("/billing/hosting/subscriptions/" + SUBSCRIPTION_ID + "/first-invoice/confirm"),
            any(),
            any());
    verify(authClient, never()).login(any());
  }

  @Test
  void webhookForwardsRawBytesAndOnlyMapsItsDedicatedPostRoute() {
    var authClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller = new AccountCheckoutGatewayController(authClient, checkoutClient, properties());
    byte[] payload =
        "  { \n  \"id\" : \"evt_raw\" \t\n}  \n".getBytes(StandardCharsets.UTF_8);
    when(checkoutClient.forward(eq(HttpMethod.POST), eq("/billing/hosting/webhook"), any(), any()))
        .thenReturn(
            Mono.just(
                new AccountCheckoutHttpClient.ForwardedResponse(
                    HttpStatus.ACCEPTED, MediaType.APPLICATION_JSON_VALUE, new byte[0])));
    var request =
        MockServerHttpRequest.method(
                HttpMethod.POST,
                URI.create("https://localhost/api/account/billing/hosting/webhook"))
            .sslInfo(mock(SslInfo.class))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header("Stripe-Signature", "t=123,v1=signature")
            .body(Mono.just(new DefaultDataBufferFactory().wrap(payload)));

    var response = controller.receiveStripeWebhook(MockServerWebExchange.from(request)).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    var forwardedHeaders = org.mockito.ArgumentCaptor.forClass(HttpHeaders.class);
    var forwardedBody = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(checkoutClient)
        .forward(
            eq(HttpMethod.POST),
            eq("/billing/hosting/webhook"),
            forwardedHeaders.capture(),
            forwardedBody.capture());
    assertThat(forwardedBody.getValue()).containsExactly(payload);
    assertThat(forwardedHeaders.getValue().get("Stripe-Signature"))
        .containsExactly("t=123,v1=signature");
    verify(authClient, never()).login(any());
  }

  @Test
  void exposesOnlyTheExplicitMethodAndPathAnnotations() throws Exception {
    assertThat(
            AccountCheckoutGatewayController.class
                .getMethod("login", org.springframework.web.server.ServerWebExchange.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/auth/login");
    assertThat(
            AccountCheckoutGatewayController.class
                .getMethod(
                    "getFirstInvoice",
                    String.class,
                    org.springframework.web.server.ServerWebExchange.class)
                .getAnnotation(GetMapping.class)
                .value())
        .containsExactly("/billing/hosting/subscriptions/{subscriptionId}/first-invoice");
    assertThat(
            AccountCheckoutGatewayController.class
                .getMethod(
                    "receiveStripeWebhook", org.springframework.web.server.ServerWebExchange.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/billing/hosting/webhook");
  }

  private static AccountCheckoutReceivingProperties properties() {
    var properties = new AccountCheckoutReceivingProperties();
    properties.setEnabled(true);
    properties.setWorkloadNamespace("checkout-test");
    return properties;
  }

  private static String loginJson() {
    return "{\"requestId\":\""
        + REQUEST_ID
        + "\",\"callerContextId\":\""
        + CALLER_CONTEXT_ID
        + "\",\"email\":\"operator@example.test\",\"secret\":\""
        + SECRET
        + "\"}";
  }

  private static ServerWebExchange secureExchange(String path, String body) {
    var request =
        MockServerHttpRequest.method(HttpMethod.POST, URI.create("https://localhost" + path))
            .sslInfo(mock(SslInfo.class))
            .remoteAddress(new java.net.InetSocketAddress("192.0.2.10", 443))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body);
    return canonicalized(MockServerWebExchange.from(request), new GatewayHeaderTrustProperties());
  }

  private static ServerWebExchange canonicalized(
      ServerWebExchange exchange, GatewayHeaderTrustProperties properties) {
    var filter =
        new HeaderTrustFilter(
            properties,
            new TcpProxyTrustPolicy(
                new GatewayTcpProxyListenerProperties(),
                properties,
                8080,
                java.time.Clock.systemUTC(),
                java.util.Set.of("test")));
    var result = new java.util.concurrent.atomic.AtomicReference<ServerWebExchange>();
    filter
        .filter(
            exchange,
            canonical -> {
              result.set(canonical);
              return Mono.empty();
            })
        .block();
    return result.get();
  }

  private static void verifyNoCheckoutCalls(AccountCheckoutHttpClient checkoutClient) {
    verify(checkoutClient, never()).forward(any(), any(), any(), any());
  }
}
