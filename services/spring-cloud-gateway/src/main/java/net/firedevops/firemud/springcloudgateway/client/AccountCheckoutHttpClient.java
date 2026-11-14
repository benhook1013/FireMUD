package net.firedevops.firemud.springcloudgateway.client;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.net.ssl.SSLParameters;
import net.firedevops.firemud.springcloudgateway.config.AccountCheckoutReceivingProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Narrow no-retry HTTPS proxy for Account's first-invoice and Stripe webhook routes. */
public final class AccountCheckoutHttpClient {
  public static final int MAX_SUBSCRIPTION_REQUEST_BYTES = 128 * 1024;
  public static final int MAX_WEBHOOK_REQUEST_BYTES = 256 * 1024;
  public static final int MAX_RESPONSE_BYTES = 256 * 1024;

  private static final Pattern SUBSCRIPTION_PATH =
      Pattern.compile(
          "/billing/hosting/subscriptions/"
              + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
              + "/first-invoice(?:/(?:reauthenticate|confirm|renew))?");
  private static final int MAX_ERROR_RESPONSE_BYTES = 1024;
  private static final JsonMapper ERROR_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final String WEBHOOK_PATH = "/billing/hosting/webhook";
  private static final Duration TOTAL_TIMEOUT_CEILING = Duration.ofSeconds(60);

  private final WebClient webClient;
  private final URI accountBaseUri;
  private final int connectTimeoutMs;
  private final int responseTimeoutMs;

  private AccountCheckoutHttpClient(
      WebClient webClient, URI accountBaseUri, int connectTimeoutMs, int responseTimeoutMs) {
    this.webClient = Objects.requireNonNull(webClient);
    this.accountBaseUri = Objects.requireNonNull(accountBaseUri);
    this.connectTimeoutMs = connectTimeoutMs;
    this.responseTimeoutMs = responseTimeoutMs;
  }

  /** Builds a client that trusts only the explicitly configured Account HTTPS CA. */
  public static AccountCheckoutHttpClient create(AccountCheckoutReceivingProperties properties) {
    Objects.requireNonNull(properties);
    properties.validateEnabledConfiguration();
    try {
      SslContext sslContext =
          SslContextBuilder.forClient()
              .trustManager(new File(properties.getAccountCaCertPath()))
              .build();
      HttpClient httpClient =
          HttpClient.newConnection()
              .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
              .responseTimeout(Duration.ofMillis(properties.getResponseTimeoutMs()))
              .disableRetry(true)
              .followRedirect(false)
              .secure(
                  ssl ->
                      ssl.sslContext(sslContext)
                          .handshakeTimeout(Duration.ofMillis(properties.getResponseTimeoutMs()))
                          .handlerConfigurator(
                              sslHandler -> {
                                SSLParameters parameters = sslHandler.engine().getSSLParameters();
                                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                                sslHandler.engine().setSSLParameters(parameters);
                              }));
      var strategies =
          org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
              .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
              .build();
      WebClient webClient =
          WebClient.builder()
              .clientConnector(new ReactorClientHttpConnector(httpClient))
              .exchangeStrategies(strategies)
              .build();
      return new AccountCheckoutHttpClient(
          webClient,
          properties.accountHttpsBaseUri(),
          properties.getConnectTimeoutMs(),
          properties.getResponseTimeoutMs());
    } catch (Exception invalidTls) {
      throw new IllegalStateException(
          "Checkout receiving could not initialize verified Account HTTPS", invalidTls);
    }
  }

  /**
   * Forwards one explicitly allowlisted method/path and only the caller headers required by that
   * operation. Webhook bytes remain byte-for-byte unchanged.
   */
  public Mono<ForwardedResponse> forward(
      HttpMethod method, String accountPath, HttpHeaders inboundHeaders, byte[] body) {
    Objects.requireNonNull(method);
    Objects.requireNonNull(inboundHeaders);
    Objects.requireNonNull(body);
    boolean webhook = isWebhook(method, accountPath);
    if (!webhook && !isSubscriptionRoute(method, accountPath)) {
      return Mono.just(ForwardedResponse.gatewayError());
    }
    int requestLimit = webhook ? MAX_WEBHOOK_REQUEST_BYTES : MAX_SUBSCRIPTION_REQUEST_BYTES;
    if (body.length > requestLimit) return Mono.just(ForwardedResponse.payloadTooLarge());
    if ((HttpMethod.GET.equals(method) && body.length != 0)
        || (HttpMethod.POST.equals(method) && body.length == 0))
      return Mono.just(ForwardedResponse.badRequest());

    String contentType = oneHeader(inboundHeaders, HttpHeaders.CONTENT_TYPE);
    if (contentType != null && !isJsonContentType(contentType)) {
      return Mono.just(ForwardedResponse.badRequest());
    }
    if (HttpMethod.POST.equals(method) && !isJsonContentType(contentType)) {
      return Mono.just(ForwardedResponse.badRequest());
    }
    String authorization = webhook ? null : oneHeader(inboundHeaders, HttpHeaders.AUTHORIZATION);
    String stripeSignature = webhook ? oneHeader(inboundHeaders, "Stripe-Signature") : null;
    if (!webhook && !validAuthorization(authorization)) {
      return Mono.just(ForwardedResponse.unauthorized());
    }
    if (webhook && !validStripeSignature(stripeSignature)) {
      return Mono.just(ForwardedResponse.badRequest());
    }

    URI target = accountBaseUri.resolve(accountPath);
    Duration totalTimeout =
        Duration.ofMillis((long) connectTimeoutMs + responseTimeoutMs)
                    .compareTo(TOTAL_TIMEOUT_CEILING)
                > 0
            ? TOTAL_TIMEOUT_CEILING
            : Duration.ofMillis((long) connectTimeoutMs + responseTimeoutMs);
    return webClient
        .method(method)
        .uri(target)
        .headers(
            outgoing -> {
              if (contentType != null) outgoing.set(HttpHeaders.CONTENT_TYPE, contentType);
              if (authorization != null) outgoing.set(HttpHeaders.AUTHORIZATION, authorization);
              if (stripeSignature != null) outgoing.set("Stripe-Signature", stripeSignature);
            })
        .bodyValue(body)
        .exchangeToMono(
            response ->
                readResponse(
                    method,
                    accountPath,
                    response.statusCode(),
                    response.headers().asHttpHeaders(),
                    response.bodyToMono(byte[].class)))
        .timeout(totalTimeout)
        .onErrorResume(ignored -> Mono.just(ForwardedResponse.gatewayError()));
  }

  private Mono<ForwardedResponse> readResponse(
      HttpMethod method,
      String path,
      HttpStatusCode status,
      HttpHeaders headers,
      Mono<byte[]> responseBody) {
    if (!status.is2xxSuccessful()) {
      HttpStatusCode safeStatus =
          status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
              ? status
              : status.is3xxRedirection() || status.is5xxServerError()
                  ? HttpStatus.BAD_GATEWAY
                  : status;
      var empty = new ForwardedResponse(safeStatus, null, new byte[0]);
      return responseBody
          .map(body -> closedErrorResponse(method, path, status, safeStatus, headers, body))
          .onErrorReturn(empty)
          .defaultIfEmpty(empty);
    }
    String contentType = oneHeader(headers, HttpHeaders.CONTENT_TYPE);
    if (status.value() == HttpStatus.NO_CONTENT.value()
        || status.value() == HttpStatus.RESET_CONTENT.value()) {
      return responseBody
          .onErrorResume(ignored -> Mono.empty())
          .thenReturn(new ForwardedResponse(status, null, new byte[0]));
    }
    if (!isJsonContentType(contentType)) return Mono.just(ForwardedResponse.gatewayError());
    return responseBody
        .defaultIfEmpty(new byte[0])
        .map(body -> new ForwardedResponse(status, contentType, body));
  }

  private static ForwardedResponse closedErrorResponse(
      HttpMethod method,
      String path,
      HttpStatusCode upstreamStatus,
      HttpStatusCode safeStatus,
      HttpHeaders headers,
      byte[] body) {
    var empty = new ForwardedResponse(safeStatus, null, new byte[0]);
    if (!isSubscriptionRoute(method, path)
        || body.length == 0
        || body.length > MAX_ERROR_RESPONSE_BYTES
        || !isJsonContentType(oneHeader(headers, HttpHeaders.CONTENT_TYPE))) return empty;
    boolean confirmation =
        HttpMethod.POST.equals(method) && (path.endsWith("/confirm") || path.endsWith("/renew"));
    String expected =
        switch (upstreamStatus.value()) {
          case 400 -> "FIRST_INVOICE_INVALID_REQUEST";
          case 401 -> "AUTH_SESSION_REVOKED";
          case 409 -> "FIRST_INVOICE_EVIDENCE_REFUSED";
          case 502 -> confirmation ? "FIRST_INVOICE_PROVIDER_UNCERTAIN" : null;
          case 503 -> "AUTH_UNAVAILABLE";
          default -> null;
        };
    if (expected == null) return empty;
    try {
      JsonNode root = ERROR_JSON.readTree(body);
      if (root == null
          || !root.isObject()
          || root.size() != 1
          || !root.has("code")
          || !root.get("code").isTextual()
          || !expected.equals(root.get("code").textValue())) return empty;
      byte[] safeBody =
          ("{\"code\":\"" + expected + "\"}").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      return new ForwardedResponse(safeStatus, MediaType.APPLICATION_JSON_VALUE, safeBody);
    } catch (RuntimeException invalid) {
      return empty;
    }
  }

  private static boolean isWebhook(HttpMethod method, String path) {
    return HttpMethod.POST.equals(method) && WEBHOOK_PATH.equals(path);
  }

  private static boolean isSubscriptionRoute(HttpMethod method, String path) {
    if (path == null || !SUBSCRIPTION_PATH.matcher(path).matches()) return false;
    boolean base = path.endsWith("/first-invoice");
    return (base && HttpMethod.GET.equals(method)) || (!base && HttpMethod.POST.equals(method));
  }

  private static boolean isJsonContentType(String contentType) {
    if (contentType == null) return false;
    try {
      MediaType parsed = MediaType.parseMediaType(contentType);
      return MediaType.APPLICATION_JSON.isCompatibleWith(parsed);
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  private static String oneHeader(HttpHeaders headers, String name) {
    List<String> values = headers.get(name);
    if (values == null || values.isEmpty()) return null;
    if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
      return "";
    }
    return values.getFirst();
  }

  private static boolean validAuthorization(String authorization) {
    return authorization != null
        && authorization.length() <= 16_400
        && authorization.startsWith("Bearer ")
        && authorization.length() > "Bearer ".length()
        && !authorization.contains("\r")
        && !authorization.contains("\n");
  }

  private static boolean validStripeSignature(String signature) {
    return signature != null
        && signature.length() <= 4_096
        && !signature.contains("\r")
        && !signature.contains("\n");
  }

  /** Raw successful response data is transient and deliberately hidden from diagnostics. */
  public record ForwardedResponse(HttpStatusCode status, String contentType, byte[] body) {
    public ForwardedResponse {
      Objects.requireNonNull(status);
      body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public String toString() {
      return "CheckoutUpstreamResponse[status=" + status.value() + ", body=redacted]";
    }

    private static ForwardedResponse unauthorized() {
      return new ForwardedResponse(
          HttpStatus.UNAUTHORIZED, MediaType.APPLICATION_JSON_VALUE, new byte[0]);
    }

    private static ForwardedResponse badRequest() {
      return new ForwardedResponse(
          HttpStatus.BAD_REQUEST, MediaType.APPLICATION_JSON_VALUE, new byte[0]);
    }

    private static ForwardedResponse gatewayError() {
      return new ForwardedResponse(
          HttpStatus.BAD_GATEWAY, MediaType.APPLICATION_JSON_VALUE, new byte[0]);
    }

    private static ForwardedResponse payloadTooLarge() {
      return new ForwardedResponse(
          HttpStatus.PAYLOAD_TOO_LARGE, MediaType.APPLICATION_JSON_VALUE, new byte[0]);
    }
  }
}
