package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.springcloudgateway.client.AccountCheckoutHttpClient;
import net.firedevops.firemud.springcloudgateway.client.AccountControlUiAuthenticationClient;
import net.firedevops.firemud.springcloudgateway.controller.AccountCheckoutGatewayController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

class AccountCheckoutReceivingConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void defaultAbsentConfigurationCreatesNoHandlerOrReceivingClients() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(AccountCheckoutReceivingConfiguration.class);
      context.refresh();

      assertThat(context.getBean(AccountCheckoutReceivingProperties.class).isEnabled()).isFalse();
      assertThat(context.getBeansOfType(AccountCheckoutGatewayController.class)).isEmpty();
      assertThat(context.getBeansOfType(AccountCheckoutHttpClient.class)).isEmpty();
      assertThat(context.getBeansOfType(AccountControlUiAuthenticationClient.class)).isEmpty();
      assertThat(
              context.getBeansOfType(org.springframework.cloud.gateway.route.RouteDefinition.class))
          .isEmpty();
    }
  }

  @Test
  void enabledPropertyWithoutTheIsolatedProfileFailsClosed() {
    try (var context = new AnnotationConfigApplicationContext()) {
      addProperties(
          context,
          Map.of(
              "firemud.account.checkout-receiving.enabled", "true",
              "firemud.account.checkout-receiving.workload-namespace", "checkout-test"));
      context.register(AccountCheckoutReceivingConfiguration.class);

      Throwable failure = catchThrowable(context::refresh);

      assertThat(failure).isNotNull();
      assertThat(messageChain(failure))
          .contains("requires the isolated-checkout-receiving profile");
    }
  }

  @Test
  void isolatedReceivingProfileCannotRunWithProductionProfile() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("isolated-checkout-receiving", "prod");
      context.register(AccountCheckoutReceivingConfiguration.class);

      Throwable failure = catchThrowable(context::refresh);

      assertThat(failure).isNotNull();
      assertThat(messageChain(failure)).contains("cannot be activated with a production profile");
    }
  }

  @Test
  void enabledReceivingRequiresHttpsAccountAuthorityAndReadableCaFile() throws Exception {
    Path ca = Files.writeString(temporaryDirectory.resolve("account-ca.pem"), "test CA");
    var properties = new AccountCheckoutReceivingProperties();
    properties.setEnabled(true);
    properties.setWorkloadNamespace("checkout-test");
    properties.setAccountHttpsBaseUrl("https://account-service:8443");
    properties.setAccountCaCertPath(ca.toString());

    properties.validateEnabledConfiguration();

    properties.setAccountHttpsBaseUrl("http://account-service:8080");
    Throwable httpFailure = catchThrowable(properties::validateEnabledConfiguration);
    assertThat(httpFailure).isInstanceOf(IllegalStateException.class);
    assertThat(httpFailure.getMessage()).contains("authority-only HTTPS Account upstream");

    properties.setAccountHttpsBaseUrl("https://user:secret@account-service:8443");
    Throwable credentialFailure = catchThrowable(properties::validateEnabledConfiguration);
    assertThat(credentialFailure).isInstanceOf(IllegalStateException.class);
    assertThat(credentialFailure.getMessage()).contains("authority-only HTTPS Account upstream");
  }

  @Test
  void authenticatedAccountInvalidArgumentMapsToSafeBadRequest() {
    var authenticationClient = mock(AccountControlUiAuthenticationClient.class);
    var checkoutClient = mock(AccountCheckoutHttpClient.class);
    var controller =
        new AccountCheckoutGatewayController(
            authenticationClient, checkoutClient, receivingProperties());
    when(authenticationClient.login(any()))
        .thenThrow(
            new AccountControlUiAuthenticationClient.AuthenticationInvalidRequestException());
    String secret = "controlled-primary-secret";
    String body =
        "{\"requestId\":\"927fb6a8-3975-4cba-8145-c2a8f4a9b0d1\","
            + "\"callerContextId\":\"af64ad5f-e9ee-48a1-9b5d-6fb28845ff71\","
            + "\"email\":\"owner@example.test\",\"secret\":\""
            + secret
            + "\"}";
    var request =
        MockServerHttpRequest.post(URI.create("https://localhost/api/account/auth/login"))
            .sslInfo(mock(SslInfo.class))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body);

    var response = controller.login(MockServerWebExchange.from(request)).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
        .contains("\"status\":\"ERROR\"")
        .doesNotContain(secret);
    verify(authenticationClient).login(any());
  }

  @Test
  void receivingRateLimitRunsOnlyOnSelectedHandlersAndFailsClosedWhenDenied() {
    AtomicReference<String> resolvedKey = new AtomicReference<>();
    AtomicReference<String> routeId = new AtomicReference<>();
    AtomicBoolean handlerCalled = new AtomicBoolean();
    WebFilter filter =
        AccountCheckoutReceivingConfiguration.receivingRateLimitWebFilter(
            limiter(
                false,
                (route, key) -> {
                  routeId.set(route);
                  resolvedKey.set(key);
                }),
            new GatewayRateLimiterConfig().gatewayClientIpKeyResolver());
    var exchange =
        receivingExchange(
            HttpMethod.POST,
            "/api/account/billing/hosting/subscriptions/927fb6a8-3975-4cba-8145-c2a8f4a9b0d1/first-invoice/confirm");

    filter
        .filter(
            exchange,
            ignored -> {
              handlerCalled.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(handlerCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(exchange.getResponse().getHeaders().getCacheControl()).contains("no-store");
    assertThat(routeId).hasValue("account-checkout-receiving");
    assertThat(resolvedKey).hasValue("192.0.2.123");
  }

  @Test
  void receivingRateLimitUnavailableReturnsServiceUnavailableAndIgnoresOtherPaths() {
    AtomicBoolean handlerCalled = new AtomicBoolean();
    WebFilter filter =
        AccountCheckoutReceivingConfiguration.receivingRateLimitWebFilter(
            failingLimiter(), new GatewayRateLimiterConfig().gatewayClientIpKeyResolver());
    var unrelated = receivingExchange(HttpMethod.POST, "/api/account/auth/logout");

    filter
        .filter(
            unrelated,
            ignored -> {
              handlerCalled.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(handlerCalled).isTrue();
    assertThat(unrelated.getResponse().getStatusCode()).isNull();

    var login = receivingExchange(HttpMethod.POST, "/api/account/auth/login");
    filter.filter(login, ignored -> Mono.empty()).block();

    assertThat(login.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(login.getResponse().getHeaders().getCacheControl()).contains("no-store");
  }

  private static AccountCheckoutReceivingProperties receivingProperties() {
    var properties = new AccountCheckoutReceivingProperties();
    properties.setEnabled(true);
    properties.setWorkloadNamespace("checkout-test");
    return properties;
  }

  private static void addProperties(
      AnnotationConfigApplicationContext context, Map<String, Object> properties) {
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("checkout-test", properties));
  }

  private static String messageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    return messages.toString();
  }

  private static MockServerWebExchange receivingExchange(HttpMethod method, String path) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.method(method, URI.create("https://localhost" + path))
            .header("X-Client-IP", "192.0.2.123")
            .build());
  }

  private static RateLimiter<Object> limiter(boolean allowed, RateLimitObservation observation) {
    return new RateLimiter<>() {
      @Override
      public Mono<Response> isAllowed(String routeId, String id) {
        observation.record(routeId, id);
        return Mono.just(new Response(allowed, Collections.emptyMap()));
      }

      @Override
      public Map<String, Object> getConfig() {
        return Collections.emptyMap();
      }

      @Override
      public Class<Object> getConfigClass() {
        return Object.class;
      }

      @Override
      public Object newConfig() {
        return new Object();
      }
    };
  }

  private static RateLimiter<Object> failingLimiter() {
    return new RateLimiter<>() {
      @Override
      public Mono<Response> isAllowed(String routeId, String id) {
        return Mono.error(new IllegalStateException("controlled test failure"));
      }

      @Override
      public Map<String, Object> getConfig() {
        return Collections.emptyMap();
      }

      @Override
      public Class<Object> getConfigClass() {
        return Object.class;
      }

      @Override
      public Object newConfig() {
        return new Object();
      }
    };
  }

  @FunctionalInterface
  private interface RateLimitObservation {
    void record(String routeId, String key);
  }
}
