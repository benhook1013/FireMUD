package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AccountCheckoutReceivingRateLimiterTest {
  @ParameterizedTest
  @MethodSource("redisFailures")
  void configuredFilterRefusesReactiveAndSynchronousRedisErrorsWithoutForwarding(
      boolean synchronous, HttpMethod method, String path) {
    var redis = mock(ReactiveStringRedisTemplate.class);
    var script = tokenBucketScript();
    if (synchronous) {
      when(redis.execute(eq(script), anyList(), anyList()))
          .thenThrow(new IllegalStateException("controlled Redis failure"));
    } else {
      when(redis.execute(eq(script), anyList(), anyList()))
          .thenReturn(Flux.error(new IllegalStateException("controlled Redis failure")));
    }

    assertDecision(
        configuredFilter(redis, script), HttpStatus.SERVICE_UNAVAILABLE, false, method, path);

    verify(redis).execute(eq(script), anyList(), anyList());
  }

  static Stream<Arguments> redisFailures() {
    String firstInvoice =
        "/api/account/billing/hosting/subscriptions/927fb6a8-3975-4cba-8145-c2a8f4a9b0d1/first-invoice";
    return Stream.of(false, true)
        .flatMap(
            synchronous ->
                Stream.of(
                    Arguments.of(synchronous, HttpMethod.POST, "/api/account/auth/login"),
                    Arguments.of(
                        synchronous, HttpMethod.POST, "/api/account/billing/hosting/webhook"),
                    Arguments.of(synchronous, HttpMethod.GET, firstInvoice),
                    Arguments.of(synchronous, HttpMethod.POST, firstInvoice + "/reauthenticate"),
                    Arguments.of(synchronous, HttpMethod.POST, firstInvoice + "/confirm"),
                    Arguments.of(synchronous, HttpMethod.POST, firstInvoice + "/renew")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedResults")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void configuredFilterRefusesMalformedOrMissingScriptResultsWithoutForwarding(
      String description, Flux<? extends List<?>> result) {
    var redis = mock(ReactiveStringRedisTemplate.class);
    var script = tokenBucketScript();
    when(redis.execute(eq(script), anyList(), anyList())).thenReturn((Flux) result);

    assertDecision(configuredFilter(redis, script), HttpStatus.SERVICE_UNAVAILABLE, false);

    verify(redis).execute(eq(script), anyList(), anyList());
  }

  static Stream<Arguments> malformedResults() {
    return Stream.of(
        Arguments.of("empty publisher", Flux.empty()),
        Arguments.of("empty tuple", Flux.just(List.of())),
        Arguments.of("short tuple", Flux.just(List.of(1L))),
        Arguments.of("extra value", Flux.just(List.of(1L, 10L, 9L))),
        Arguments.of("unknown decision", Flux.just(List.of(2L, 10L))),
        Arguments.of("negative remainder", Flux.just(List.of(1L, -1L))),
        Arguments.of("remainder above capacity", Flux.just(List.of(1L, 41L))),
        Arguments.of("unspent allowed tokens", Flux.just(List.of(1L, 40L))),
        Arguments.of("unexhausted denial", Flux.just(List.of(0L, 20L))),
        Arguments.of("wrong numeric type", Flux.just(List.of(1, 10))),
        Arguments.of("null decision", Flux.just(Arrays.asList(null, 10L))),
        Arguments.of("null remainder", Flux.just(Arrays.asList(1L, null))),
        Arguments.of("multiple replies", Flux.just(List.of(1L, 10L), List.of(0L, 0L))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void configuredFilterPreservesTokenBucketArgumentsAndNormalAllowOr429(boolean allowed) {
    var redis = mock(ReactiveStringRedisTemplate.class);
    var script = tokenBucketScript();
    when(redis.execute(eq(script), anyList(), anyList()))
        .thenReturn(Flux.just(List.of(allowed ? 1L : 0L, allowed ? 38L : 0L)));

    assertDecision(
        configuredFilter(redis, script), allowed ? null : HttpStatus.TOO_MANY_REQUESTS, allowed);

    verify(redis)
        .execute(
            script,
            List.of(
                "request_rate_limiter.{account-checkout-receiving.192.0.2.123}.tokens",
                "request_rate_limiter.{account-checkout-receiving.192.0.2.123}.timestamp"),
            List.of("20", "40", "", "2"));
  }

  @Test
  void configuredFilterLeavesOrdinaryPathsOutsideTheIsolatedLimiter() {
    var redis = mock(ReactiveStringRedisTemplate.class);
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("https://localhost/api/ordinary"));
    var forwarded = new AtomicBoolean();

    configuredFilter(redis, tokenBucketScript())
        .filter(
            exchange,
            ignored -> {
              forwarded.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(forwarded).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
    org.mockito.Mockito.verifyNoInteractions(redis);
  }

  private static WebFilter configuredFilter(
      ReactiveStringRedisTemplate redis, RedisScript<List<Long>> script) {
    var gateway = new GatewayProperties();
    var rateLimit = new FilterDefinition();
    rateLimit.setName("RequestRateLimiter");
    rateLimit.setArgs(
        Map.of(
            "redis-rate-limiter.replenishRate", "20",
            "redis-rate-limiter.burstCapacity", "40",
            "redis-rate-limiter.requestedTokens", "2"));
    gateway.setDefaultFilters(List.of(rateLimit));
    return new AccountCheckoutReceivingConfiguration(
            new MockEnvironment(), new AccountCheckoutReceivingProperties())
        .accountCheckoutReceivingRateLimitWebFilter(
            gateway, redis, new GatewayRateLimiterConfig().gatewayClientIpKeyResolver(), script);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static RedisScript<List<Long>> tokenBucketScript() {
    var script = new DefaultRedisScript<List<Long>>();
    script.setLocation(new ClassPathResource("META-INF/scripts/request_rate_limiter.lua"));
    script.setResultType((Class) List.class);
    return script;
  }

  private static void assertDecision(WebFilter filter, HttpStatus expected, boolean allowed) {
    assertDecision(filter, expected, allowed, HttpMethod.POST, "/api/account/auth/login");
  }

  private static void assertDecision(
      WebFilter filter, HttpStatus expected, boolean allowed, HttpMethod method, String path) {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(method, "https://localhost" + path)
                .header("X-Client-IP", "192.0.2.123"));
    var forwarded = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              forwarded.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(forwarded.get()).isEqualTo(allowed);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expected);
    if (!allowed) {
      assertThat(exchange.getResponse().getHeaders().getCacheControl()).contains("no-store");
      assertThat(exchange.getResponse().getBodyAsString().block())
          .isEqualTo("{\"status\":\"ERROR\"}");
    }
  }
}
