package net.firedevops.firemud.springcloudgateway.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Authenticated;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Request;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.springcloudgateway.client.AccountCheckoutHttpClient;
import net.firedevops.firemud.springcloudgateway.client.AccountCheckoutHttpClient.ForwardedResponse;
import net.firedevops.firemud.springcloudgateway.client.AccountControlUiAuthenticationClient;
import net.firedevops.firemud.springcloudgateway.config.AccountCheckoutReceivingProperties;
import net.firedevops.firemud.springcloudgateway.filter.VerifiedHttpClientOrigin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact checkout/login receiving allowlist; this controller does not define broad Gateway routes.
 */
@RestController
@RequestMapping("/api/account")
@Profile("isolated-checkout-receiving")
@ConditionalOnProperty(
    prefix = "firemud.account.checkout-receiving",
    name = "enabled",
    havingValue = "true")
public final class AccountCheckoutGatewayController {
  private static final int MAX_LOGIN_BODY_BYTES = 8 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountControlUiAuthenticationClient authenticationClient;
  private final AccountCheckoutHttpClient checkoutHttpClient;
  private final String workloadNamespace;

  public AccountCheckoutGatewayController(
      AccountControlUiAuthenticationClient authenticationClient,
      AccountCheckoutHttpClient checkoutHttpClient,
      AccountCheckoutReceivingProperties properties) {
    this.authenticationClient = Objects.requireNonNull(authenticationClient);
    this.checkoutHttpClient = Objects.requireNonNull(checkoutHttpClient);
    String configuredNamespace = Objects.requireNonNull(properties).getWorkloadNamespace();
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("Checkout Gateway namespace must be canonical");
    }
    this.workloadNamespace = configuredNamespace;
  }

  /** Public Control-UI login. The credential body is transient and errors never include it. */
  @PostMapping("/auth/login")
  public Mono<ResponseEntity<byte[]>> login(ServerWebExchange exchange) {
    if (!isSecureRequest(exchange) || hasQuery(exchange)) {
      return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR")));
    }
    String contentType = oneHeader(exchange.getRequest().getHeaders(), HttpHeaders.CONTENT_TYPE);
    if (!isJsonContentType(contentType)) {
      return Mono.just(jsonResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, errorJson("ERROR")));
    }
    VerifiedHttpClientOrigin origin = VerifiedHttpClientOrigin.from(exchange);
    if (origin == null) {
      return Mono.just(jsonResponse(HttpStatus.SERVICE_UNAVAILABLE, errorJson("ERROR")));
    }
    return boundedBody(exchange, MAX_LOGIN_BODY_BYTES)
        .flatMap(
            body -> {
              final LoginBody input;
              final Request request;
              try {
                input = JSON.readValue(body, LoginBody.class);
                if (input == null) throw new IllegalArgumentException("Login object required");
                request =
                    new Request(
                        parseUuid(input.requestId()),
                        parseUuid(input.callerContextId()),
                        workloadNamespace,
                        input.email(),
                        input.secret(),
                        origin.sourceContext());
              } catch (JacksonException | IllegalArgumentException malformed) {
                return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR")));
              }
              return Mono.fromCallable(() -> authenticationClient.login(request))
                  .subscribeOn(Schedulers.boundedElastic())
                  .map(
                      result -> {
                        if (result.outcome()
                            == AccountControlUiAuthenticationGrpcCodec.Outcome.DENIED) {
                          return jsonResponse(HttpStatus.UNAUTHORIZED, errorJson("DENIED"));
                        }
                        Authenticated authenticated = result.authenticated();
                        LoginSuccess success =
                            new LoginSuccess(
                                "SUCCESS",
                                new LoginData(
                                    authenticated.accountId().toString(),
                                    authenticated.authToken(),
                                    Instant.ofEpochSecond(authenticated.expiresAtEpochSeconds())
                                        .toString()));
                        try {
                          return jsonResponse(HttpStatus.OK, JSON.writeValueAsBytes(success));
                        } catch (JacksonException serializationFailure) {
                          return jsonResponse(HttpStatus.BAD_GATEWAY, errorJson("ERROR"));
                        }
                      })
                  .onErrorResume(
                      AccountControlUiAuthenticationClient.AuthenticationInvalidRequestException
                          .class,
                      invalid ->
                          Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR"))))
                  .onErrorResume(
                      AccountControlUiAuthenticationClient.AuthenticationConflictException.class,
                      conflict ->
                          Mono.just(jsonResponse(HttpStatus.CONFLICT, errorJson("CONFLICT"))))
                  .onErrorResume(
                      AccountControlUiAuthenticationClient.AuthenticationUnavailableException.class,
                      unavailable ->
                          Mono.just(
                              jsonResponse(HttpStatus.SERVICE_UNAVAILABLE, errorJson("ERROR"))))
                  .onErrorResume(
                      IllegalStateException.class,
                      invalid ->
                          Mono.just(jsonResponse(HttpStatus.BAD_GATEWAY, errorJson("ERROR"))));
            })
        .onErrorResume(
            DataBufferLimitException.class,
            oversized -> Mono.just(jsonResponse(HttpStatus.PAYLOAD_TOO_LARGE, errorJson("ERROR"))));
  }

  @GetMapping("/billing/hosting/subscriptions/{subscriptionId}/first-invoice")
  public Mono<ResponseEntity<byte[]>> getFirstInvoice(
      @PathVariable String subscriptionId, ServerWebExchange exchange) {
    if (!isSecureRequest(exchange) || hasQuery(exchange) || !canonicalUuid(subscriptionId)) {
      return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR")));
    }
    return forward(exchange, HttpMethod.GET, firstInvoicePath(subscriptionId));
  }

  @PostMapping("/billing/hosting/subscriptions/{subscriptionId}/first-invoice/reauthenticate")
  public Mono<ResponseEntity<byte[]>> reauthenticateFirstInvoice(
      @PathVariable String subscriptionId, ServerWebExchange exchange) {
    return postSubscriptionAction(subscriptionId, exchange, "reauthenticate");
  }

  @PostMapping("/billing/hosting/subscriptions/{subscriptionId}/first-invoice/confirm")
  public Mono<ResponseEntity<byte[]>> confirmFirstInvoice(
      @PathVariable String subscriptionId, ServerWebExchange exchange) {
    return postSubscriptionAction(subscriptionId, exchange, "confirm");
  }

  @PostMapping("/billing/hosting/subscriptions/{subscriptionId}/first-invoice/renew")
  public Mono<ResponseEntity<byte[]>> renewFirstInvoice(
      @PathVariable String subscriptionId, ServerWebExchange exchange) {
    return postSubscriptionAction(subscriptionId, exchange, "renew");
  }

  @PostMapping("/billing/hosting/webhook")
  public Mono<ResponseEntity<byte[]>> receiveStripeWebhook(ServerWebExchange exchange) {
    if (!isSecureRequest(exchange) || hasQuery(exchange)) {
      return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR")));
    }
    String contentType = oneHeader(exchange.getRequest().getHeaders(), HttpHeaders.CONTENT_TYPE);
    String signature = oneHeader(exchange.getRequest().getHeaders(), "Stripe-Signature");
    if (!isJsonContentType(contentType) || signature == null || signature.isBlank()) {
      return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR")));
    }
    return boundedBody(exchange, AccountCheckoutHttpClient.MAX_WEBHOOK_REQUEST_BYTES)
        .flatMap(body -> forwardBytes(exchange, HttpMethod.POST, "/billing/hosting/webhook", body))
        .onErrorResume(
            DataBufferLimitException.class,
            oversized -> Mono.just(jsonResponse(HttpStatus.PAYLOAD_TOO_LARGE, errorJson("ERROR"))));
  }

  private Mono<ResponseEntity<byte[]>> postSubscriptionAction(
      String subscriptionId, ServerWebExchange exchange, String action) {
    if (!isSecureRequest(exchange) || hasQuery(exchange) || !canonicalUuid(subscriptionId)) {
      return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, errorJson("ERROR")));
    }
    String contentType = oneHeader(exchange.getRequest().getHeaders(), HttpHeaders.CONTENT_TYPE);
    if (!isJsonContentType(contentType)) {
      return Mono.just(jsonResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, errorJson("ERROR")));
    }
    String path = firstInvoicePath(subscriptionId) + "/" + action;
    return forward(exchange, HttpMethod.POST, path);
  }

  private Mono<ResponseEntity<byte[]>> forward(
      ServerWebExchange exchange, HttpMethod method, String path) {
    String contentType = oneHeader(exchange.getRequest().getHeaders(), HttpHeaders.CONTENT_TYPE);
    if (HttpMethod.POST.equals(method) && !isJsonContentType(contentType)) {
      return Mono.just(jsonResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, errorJson("ERROR")));
    }
    return boundedBody(exchange, AccountCheckoutHttpClient.MAX_SUBSCRIPTION_REQUEST_BYTES)
        .flatMap(body -> forwardBytes(exchange, method, path, body))
        .onErrorResume(
            DataBufferLimitException.class,
            oversized -> Mono.just(jsonResponse(HttpStatus.PAYLOAD_TOO_LARGE, errorJson("ERROR"))));
  }

  private Mono<ResponseEntity<byte[]>> forwardBytes(
      ServerWebExchange exchange, HttpMethod method, String path, byte[] body) {
    return checkoutHttpClient
        .forward(method, path, exchange.getRequest().getHeaders(), body)
        .map(response -> response(response));
  }

  private static Mono<byte[]> boundedBody(ServerWebExchange exchange, int maximumBytes) {
    long contentLength = exchange.getRequest().getHeaders().getContentLength();
    if (contentLength > maximumBytes) {
      return Mono.error(new DataBufferLimitException("Request body exceeds the route limit"));
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), maximumBytes)
        .map(AccountCheckoutGatewayController::readAndRelease)
        .defaultIfEmpty(new byte[0]);
  }

  private static byte[] readAndRelease(DataBuffer buffer) {
    try {
      byte[] bytes = new byte[buffer.readableByteCount()];
      buffer.read(bytes);
      return bytes;
    } finally {
      DataBufferUtils.release(buffer);
    }
  }

  private static boolean isSecureRequest(ServerWebExchange exchange) {
    return exchange.getRequest().getSslInfo() != null
        && "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
  }

  private static boolean hasQuery(ServerWebExchange exchange) {
    return exchange.getRequest().getURI().getRawQuery() != null;
  }

  private static String oneHeader(HttpHeaders headers, String name) {
    var values = headers.get(name);
    if (values == null || values.isEmpty()) return null;
    if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) return "";
    return values.getFirst();
  }

  private static boolean isJsonContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) return false;
    try {
      return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static UUID parseUuid(String value) {
    if (!canonicalUuid(value)) throw new IllegalArgumentException("Canonical UUID required");
    return UUID.fromString(value);
  }

  private static boolean canonicalUuid(String value) {
    if (value == null) return false;
    try {
      UUID parsed = UUID.fromString(value);
      return !parsed.equals(new UUID(0L, 0L)) && parsed.toString().equals(value);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static String firstInvoicePath(String subscriptionId) {
    return "/billing/hosting/subscriptions/" + subscriptionId + "/first-invoice";
  }

  private static ResponseEntity<byte[]> response(ForwardedResponse upstream) {
    HttpStatusCode status = upstream.status();
    byte[] body = upstream.body();
    String contentType = upstream.contentType();
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.EXPIRES, "0")
        .contentType(
            contentType == null
                ? MediaType.APPLICATION_JSON
                : MediaType.parseMediaType(contentType))
        .body(body);
  }

  private static ResponseEntity<byte[]> jsonResponse(HttpStatus status, byte[] body) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.EXPIRES, "0")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  private static byte[] errorJson(String status) {
    return ("{\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  private record LoginBody(String requestId, String callerContextId, String email, String secret) {
    @Override
    public String toString() {
      return "ControlUiLoginBody[redacted]";
    }
  }

  private record LoginSuccess(String status, LoginData data) {
    @Override
    public String toString() {
      return "ControlUiLoginSuccess[redacted]";
    }
  }

  private record LoginData(String accountId, String authToken, String expiresAt) {
    @Override
    public String toString() {
      return "ControlUiLoginData[redacted]";
    }
  }
}
