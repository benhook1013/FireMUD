package net.firedevops.firemud.springcloudgateway.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.ssl.SslContextBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.firedevops.firemud.springcloudgateway.config.AccountCheckoutReceivingProperties;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Real loopback HTTPS proofs with test-only PKI, not a live Account or Stripe endpoint. */
class AccountCheckoutHttpClientTlsTest {
  private static final String ACCOUNT_WORKLOAD =
      "spiffe://firemud/ns/checkout-test/sa/account-service";
  private static final String SUBSCRIPTION_ID = "d0deddf3-d96d-456d-b85e-f025fa2e6ee3";
  private static final String SUBSCRIPTION_PATH =
      "/billing/hosting/subscriptions/" + SUBSCRIPTION_ID + "/first-invoice";
  private static final String WEBHOOK_PATH = "/billing/hosting/webhook";
  private static final byte[] JSON_RESPONSE =
      "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);

  @TempDir static Path temporaryDirectory;
  private static LoopbackMtlsTestPki.Pki pki;
  private static LoopbackMtlsTestPki.Identity serverIdentity;
  private DisposableServer server;
  private AccountCheckoutHttpClient client;

  @BeforeAll
  static void createPki() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            temporaryDirectory,
            "Checkout HTTPS test CA",
            new LoopbackMtlsTestPki.IdentitySpec("account-server", ACCOUNT_WORKLOAD, true));
    serverIdentity = pki.identity("account-server");
  }

  @AfterEach
  void stopTransport() {
    if (client != null) client = null;
    if (server != null) {
      server.disposeNow(Duration.ofSeconds(2));
      server = null;
    }
  }

  @Test
  void forwardsOnlyNecessaryCheckoutHeadersAndUsesVerifiedHttps() throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests = startServer(200, JSON_RESPONSE, captured);
    client = newClient(pki.caCertificate(), server.port());
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer caller-jwt");
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Firemud-Account-Id", "spoofed-account");
    headers.set("Cookie", "session=private");

    var response =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, headers, new byte[0])
            .block(Duration.ofSeconds(8));

    assertThat(response).isNotNull();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.body()).isEqualTo(JSON_RESPONSE);
    assertThat(requests).hasValue(1);
    assertThat(captured.get().method()).isEqualTo("GET");
    assertThat(captured.get().path()).isEqualTo(SUBSCRIPTION_PATH);
    assertThat(captured.get().authorization()).isEqualTo("Bearer caller-jwt");
    assertThat(captured.get().forwardedProto()).isNull();
    assertThat(captured.get().accountIdentity()).isNull();
    assertThat(captured.get().cookie()).isNull();
  }

  @Test
  void refusesMissingOrInvalidOwnerBearerAsUnauthorizedWithoutContactingAccount() throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests = startServer(200, JSON_RESPONSE, captured);
    client = newClient(pki.caCertificate(), server.port());
    for (String authorization :
        new String[] {
          null,
          "",
          "Basic caller",
          "Bearer ",
          "Bearer " + "x".repeat(16_400),
          "Bearer caller\r\ninjected"
        }) {
      HttpHeaders headers = new HttpHeaders();
      if (authorization != null) headers.set(HttpHeaders.AUTHORIZATION, authorization);
      var response =
          client
              .forward(HttpMethod.GET, SUBSCRIPTION_PATH, headers, new byte[0])
              .block(Duration.ofSeconds(8));
      assertThat(response).isNotNull();
      assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.body()).isEmpty();
    }
    HttpHeaders duplicateAuthorization = authorizationHeaders();
    duplicateAuthorization.add(HttpHeaders.AUTHORIZATION, "Bearer second-caller");
    var duplicateResponse =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, duplicateAuthorization, new byte[0])
            .block(Duration.ofSeconds(8));
    assertThat(duplicateResponse).isNotNull();
    assertThat(duplicateResponse.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(duplicateResponse.body()).isEmpty();
    assertThat(requests).hasValue(0);
    assertThat(captured.get()).isNull();
  }

  @Test
  void keepsMalformedBodyAndWebhookSignatureAsBadRequestWithoutContactingAccount()
      throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests = startServer(200, JSON_RESPONSE, captured);
    client = newClient(pki.caCertificate(), server.port());
    var bodyResponse =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, authorizationHeaders(), new byte[] {1})
            .block(Duration.ofSeconds(8));
    assertThat(bodyResponse).isNotNull();
    assertThat(bodyResponse.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(bodyResponse.body()).isEmpty();

    HttpHeaders webhookHeaders = new HttpHeaders();
    webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
    byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
    var missingSignature =
        client
            .forward(HttpMethod.POST, WEBHOOK_PATH, webhookHeaders, payload)
            .block(Duration.ofSeconds(8));
    assertThat(missingSignature).isNotNull();
    assertThat(missingSignature.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(missingSignature.body()).isEmpty();
    webhookHeaders.set("Stripe-Signature", "signature\r\ninjected");
    var malformedSignature =
        client
            .forward(HttpMethod.POST, WEBHOOK_PATH, webhookHeaders, payload)
            .block(Duration.ofSeconds(8));
    assertThat(malformedSignature).isNotNull();
    assertThat(malformedSignature.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(malformedSignature.body()).isEmpty();
    assertThat(requests).hasValue(0);
    assertThat(captured.get()).isNull();
  }

  @Test
  void preservesWebhookBytesAndForwardsOnlyContentTypeAndStripeSignature() throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests = startServer(202, JSON_RESPONSE, captured);
    client = newClient(pki.caCertificate(), server.port());
    byte[] rawPayload = "{\n  \"id\": \"evt_123\"\n}\n".getBytes(StandardCharsets.UTF_8);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Stripe-Signature", "t=123,v1=signature");
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer must-not-pass");
    headers.set("X-Forwarded-For", "203.0.113.9");

    var response =
        client
            .forward(HttpMethod.POST, WEBHOOK_PATH, headers, rawPayload)
            .block(Duration.ofSeconds(8));

    assertThat(response).isNotNull();
    assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(requests).hasValue(1);
    assertThat(captured.get().body()).isEqualTo(rawPayload);
    assertThat(captured.get().contentType()).startsWith("application/json");
    assertThat(captured.get().stripeSignature()).isEqualTo("t=123,v1=signature");
    assertThat(captured.get().authorization()).isNull();
    assertThat(captured.get().forwardedFor()).isNull();
  }

  @Test
  void rejectsUntrustedAccountCertificateBeforeSendingARequest() throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests = startServer(200, JSON_RESPONSE, captured);
    Path otherDirectory = Files.createDirectories(temporaryDirectory.resolve("other-ca"));
    var otherPki =
        LoopbackMtlsTestPki.create(
            otherDirectory,
            "Untrusted checkout HTTPS CA",
            new LoopbackMtlsTestPki.IdentitySpec("untrusted-server", ACCOUNT_WORKLOAD, true));
    client = newClient(otherPki.caCertificate(), server.port());

    var response =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, authorizationHeaders(), new byte[0])
            .block(Duration.ofSeconds(8));

    assertThat(response).isNotNull();
    assertThat(response.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(requests).hasValue(0);
    assertThat(captured.get()).isNull();
  }

  @Test
  void refusesRedirectsAndOverLimitResponsesWithoutFollowingOrReturningTheirBody()
      throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests = startRedirectingServer(captured);
    client = newClient(pki.caCertificate(), server.port());

    var redirectResponse =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, authorizationHeaders(), new byte[0])
            .block(Duration.ofSeconds(8));

    assertThat(redirectResponse).isNotNull();
    assertThat(redirectResponse.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(requests).hasValue(1);
    assertThat(redirectResponse.body()).isEmpty();

    client = null;
    server.disposeNow(Duration.ofSeconds(2));
    server = null;
    captured.set(null);
    AtomicInteger oversizedRequests =
        startServer(200, new byte[AccountCheckoutHttpClient.MAX_RESPONSE_BYTES + 1], captured);
    client = newClient(pki.caCertificate(), server.port());
    var oversizedResponse =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, authorizationHeaders(), new byte[0])
            .block(Duration.ofSeconds(8));

    assertThat(oversizedResponse).isNotNull();
    assertThat(oversizedResponse.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(oversizedResponse.body()).isEmpty();
    assertThat(oversizedRequests).hasValue(1);
  }

  @Test
  void doesNotRetryAnUncertainCheckoutPostAfterTheAccountConnectionResets() throws Exception {
    AtomicInteger requests = startResettingServer();
    client = newClient(pki.caCertificate(), server.port());
    HttpHeaders headers = authorizationHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    byte[] body = "{\"confirm\":true}".getBytes(StandardCharsets.UTF_8);

    var response =
        client
            .forward(HttpMethod.POST, SUBSCRIPTION_PATH + "/confirm", headers, body)
            .block(Duration.ofSeconds(8));

    assertThat(response).isNotNull();
    assertThat(response.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(requests).hasValue(1);
  }

  @ParameterizedTest
  @MethodSource("contractedErrors")
  void preservesOnlyContractedOwnerErrorCodesWithoutRetry(
      HttpMethod method, String suffix, int status, String code) throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    String body = "{ \"code\": \"" + code + "\" }";
    AtomicInteger requests = startServer(status, body.getBytes(StandardCharsets.UTF_8), captured);
    client = newClient(pki.caCertificate(), server.port());
    HttpHeaders headers = authorizationHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var result =
        client
            .forward(
                method,
                SUBSCRIPTION_PATH + suffix,
                headers,
                HttpMethod.GET.equals(method) ? new byte[0] : "{}".getBytes(StandardCharsets.UTF_8))
            .block(Duration.ofSeconds(8));
    assertThat(result).isNotNull();
    assertThat(result.status().value()).isEqualTo(status);
    assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(new String(result.body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"code\":\"" + code + "\"}");
    assertThat(requests).hasValue(1);
  }

  private static Stream<Arguments> contractedErrors() {
    return Stream.of(
        Arguments.of(HttpMethod.POST, "/reauthenticate", 400, "FIRST_INVOICE_INVALID_REQUEST"),
        Arguments.of(HttpMethod.GET, "", 401, "AUTH_SESSION_REVOKED"),
        Arguments.of(HttpMethod.POST, "/confirm", 409, "FIRST_INVOICE_EVIDENCE_REFUSED"),
        Arguments.of(HttpMethod.POST, "/confirm", 502, "FIRST_INVOICE_PROVIDER_UNCERTAIN"),
        Arguments.of(HttpMethod.POST, "/renew", 502, "FIRST_INVOICE_PROVIDER_UNCERTAIN"),
        Arguments.of(HttpMethod.GET, "", 503, "AUTH_UNAVAILABLE"));
  }

  @Test
  void preservesReconcilingWebhook503WithoutForwardingPrivateOutcomeOrRetrying() throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests =
        startServer(
            503,
            "{\"state\":\"RECONCILING\",\"private\":\"never-forward\"}"
                .getBytes(StandardCharsets.UTF_8),
            captured);
    client = newClient(pki.caCertificate(), server.port());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Stripe-Signature", "t=123,v1=signature");
    var result =
        client
            .forward(HttpMethod.POST, WEBHOOK_PATH, headers, "{}".getBytes(StandardCharsets.UTF_8))
            .block(Duration.ofSeconds(8));
    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(result.body()).isEmpty();
    assertThat(requests).hasValue(1);
  }

  @ParameterizedTest
  @MethodSource("untrustedErrors")
  void stripsUncontractedOrMalformedErrorBodies(int upstreamStatus, String body, int publicStatus)
      throws Exception {
    var captured = new AtomicReference<CapturedRequest>();
    AtomicInteger requests =
        startServer(upstreamStatus, body.getBytes(StandardCharsets.UTF_8), captured);
    client = newClient(pki.caCertificate(), server.port());
    var result =
        client
            .forward(HttpMethod.GET, SUBSCRIPTION_PATH, authorizationHeaders(), new byte[0])
            .block(Duration.ofSeconds(8));
    assertThat(result).isNotNull();
    assertThat(result.status().value()).isEqualTo(publicStatus);
    assertThat(result.body()).isEmpty();
    assertThat(requests).hasValue(1);
  }

  private static Stream<Arguments> untrustedErrors() {
    return Stream.of(
        Arguments.of(503, "{broken", 503),
        Arguments.of(503, "{\"code\":\"AUTH_UNAVAILABLE\",\"secret\":\"private\"}", 503),
        Arguments.of(503, "{\"code\":\"arbitrary-data\"}", 503),
        Arguments.of(409, "{\"code\":\"AUTH_UNAVAILABLE\"}", 409),
        Arguments.of(503, "{\"code\":\"AUTH_UNAVAILABLE\",\"code\":\"AUTH_UNAVAILABLE\"}", 503),
        Arguments.of(503, "{\"code\":\"AUTH_UNAVAILABLE\"} {}", 503),
        Arguments.of(503, "{\"code\":42}", 503),
        Arguments.of(503, "{\"code\":\"AUTH_UNAVAILABLE\"}" + " ".repeat(2048), 503),
        Arguments.of(502, "{\"code\":\"FIRST_INVOICE_PROVIDER_UNCERTAIN\"}", 502),
        Arguments.of(500, "{\"code\":\"AUTH_UNAVAILABLE\"}", 502));
  }

  private AtomicInteger startServer(
      int status, byte[] responseBody, AtomicReference<CapturedRequest> captured) throws Exception {
    Path cert = writeCertificate(temporaryDirectory.resolve("account-server.crt"));
    Path key = writePrivateKey(temporaryDirectory.resolve("account-server.key"));
    var sslContext = SslContextBuilder.forServer(cert.toFile(), key.toFile()).build();
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .secure(spec -> spec.sslContext(sslContext))
            .handle(
                (request, response) ->
                    request
                        .receive()
                        .aggregate()
                        .asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .flatMap(
                            body -> {
                              requests.incrementAndGet();
                              captured.set(capture(request, body));
                              return response
                                  .status(status)
                                  .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                  .sendByteArray(Mono.just(responseBody))
                                  .then();
                            }))
            .bindNow(Duration.ofSeconds(5));
    return requests;
  }

  private AtomicInteger startRedirectingServer(AtomicReference<CapturedRequest> captured)
      throws Exception {
    Path cert = writeCertificate(temporaryDirectory.resolve("account-server.crt"));
    Path key = writePrivateKey(temporaryDirectory.resolve("account-server.key"));
    var sslContext = SslContextBuilder.forServer(cert.toFile(), key.toFile()).build();
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .secure(spec -> spec.sslContext(sslContext))
            .handle(
                (request, response) ->
                    request
                        .receive()
                        .aggregate()
                        .asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .flatMap(
                            body -> {
                              requests.incrementAndGet();
                              captured.set(capture(request, body));
                              return response
                                  .status(302)
                                  .header(HttpHeaders.LOCATION, "/other-location")
                                  .send()
                                  .then();
                            }))
            .bindNow(Duration.ofSeconds(5));
    return requests;
  }

  private AtomicInteger startResettingServer() throws Exception {
    Path cert = writeCertificate(temporaryDirectory.resolve("account-server-reset.crt"));
    Path key = writePrivateKey(temporaryDirectory.resolve("account-server-reset.key"));
    var sslContext = SslContextBuilder.forServer(cert.toFile(), key.toFile()).build();
    AtomicInteger requests = new AtomicInteger();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .secure(spec -> spec.sslContext(sslContext))
            .handle(
                (request, response) ->
                    request
                        .receive()
                        .aggregate()
                        .asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .flatMap(
                            ignored -> {
                              requests.incrementAndGet();
                              response.withConnection(connection -> connection.channel().close());
                              return Mono.<Void>never();
                            }))
            .bindNow(Duration.ofSeconds(5));
    return requests;
  }

  private AccountCheckoutHttpClient newClient(
      java.security.cert.X509Certificate caCertificate, int port) throws Exception {
    Path ca =
        writeCertificate(temporaryDirectory.resolve("client-ca-" + port + ".crt"), caCertificate);
    var properties = new AccountCheckoutReceivingProperties();
    properties.setEnabled(true);
    properties.setWorkloadNamespace("checkout-test");
    properties.setAccountHttpsBaseUrl("https://127.0.0.1:" + port);
    properties.setAccountCaCertPath(ca.toString());
    return AccountCheckoutHttpClient.create(properties);
  }

  private static HttpHeaders authorizationHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer caller-jwt");
    return headers;
  }

  private static CapturedRequest capture(
      reactor.netty.http.server.HttpServerRequest request, byte[] body) {
    var headers = request.requestHeaders();
    return new CapturedRequest(
        request.method().name(),
        request.uri(),
        headers.get(HttpHeaders.AUTHORIZATION),
        headers.get(HttpHeaders.CONTENT_TYPE),
        headers.get("Stripe-Signature"),
        headers.get("X-Forwarded-Proto"),
        headers.get("X-Forwarded-For"),
        headers.get("X-Firemud-Account-Id"),
        headers.get("Cookie"),
        body.clone());
  }

  private Path writeCertificate(Path path) throws Exception {
    return writeCertificate(path, serverIdentity.certificate());
  }

  private static Path writeCertificate(Path path, java.security.cert.X509Certificate certificate)
      throws Exception {
    return Files.writeString(path, pem("CERTIFICATE", certificate.getEncoded()));
  }

  private Path writePrivateKey(Path path) throws Exception {
    return Files.writeString(path, pem("PRIVATE KEY", serverIdentity.privateKey().getEncoded()));
  }

  private static String pem(String label, byte[] value) {
    String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value);
    return "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n";
  }

  private record CapturedRequest(
      String method,
      String path,
      String authorization,
      String contentType,
      String stripeSignature,
      String forwardedProto,
      String forwardedFor,
      String accountIdentity,
      String cookie,
      byte[] body) {}
}
