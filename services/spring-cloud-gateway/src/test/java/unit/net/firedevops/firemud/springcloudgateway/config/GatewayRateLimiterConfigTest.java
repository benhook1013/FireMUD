package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayRateLimiterConfigTest {
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  @Test
  void prefersGatewayOwnedClientIpHeader() {
    KeyResolver resolver = new GatewayRateLimiterConfig().gatewayClientIpKeyResolver();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/auth/player-bootstrap")
                .header("X-Client-IP", "198.51.100.10")
                .header(X_FORWARDED_FOR, "203.0.113.99")
                .build());

    String key = resolver.resolve(exchange).block();

    assertThat(key).isEqualTo("198.51.100.10");
  }

  @Test
  void fallsBackToForwardedForWhenGatewayOwnedHeaderMissing() {
    KeyResolver resolver = new GatewayRateLimiterConfig().gatewayClientIpKeyResolver();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/auth/player-bootstrap")
                .header(X_FORWARDED_FOR, "203.0.113.77, 10.0.0.1")
                .build());

    String key = resolver.resolve(exchange).block();

    assertThat(key).isEqualTo("203.0.113.77");
  }

  @Test
  void readsOnlyTheCanonicalBoundedRequestRateLimiterDefaults() {
    GatewayProperties properties = new GatewayProperties();
    FilterDefinition limiter = new FilterDefinition("RequestRateLimiter");
    limiter.setArgs(
        Map.of(
            "redis-rate-limiter.replenishRate", "20",
            "redis-rate-limiter.burstCapacity", "40"));
    properties.setDefaultFilters(List.of(limiter));

    var arguments = GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(properties);

    assertThat(arguments.replenishRate()).isEqualTo(20);
    assertThat(arguments.burstCapacity()).isEqualTo(40L);
    assertThat(arguments.requestedTokens()).isEqualTo(1);
  }

  @Test
  void preservesConfiguredRequestedTokensWhenPresent() {
    GatewayProperties properties = new GatewayProperties();
    FilterDefinition limiter = limiter("20", "40");
    limiter.getArgs().put("redis-rate-limiter.requestedTokens", "2");
    properties.setDefaultFilters(List.of(limiter));

    var arguments = GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(properties);

    assertThat(arguments.requestedTokens()).isEqualTo(2);
  }

  @Test
  void rejectsRequestedTokensAboveBurstAndAllowsEqualityAtSupportedBounds() {
    GatewayProperties oversizedRequest = new GatewayProperties();
    FilterDefinition oversizedLimiter = limiter("20", "40");
    oversizedLimiter.getArgs().put("redis-rate-limiter.requestedTokens", "41");
    oversizedRequest.setDefaultFilters(List.of(oversizedLimiter));

    GatewayProperties equalityAtMaximumBounds = new GatewayProperties();
    FilterDefinition maximumLimiter =
        limiter(Integer.toString(Integer.MAX_VALUE), Long.toString(Long.MAX_VALUE));
    maximumLimiter
        .getArgs()
        .put("redis-rate-limiter.requestedTokens", Integer.toString(Integer.MAX_VALUE));
    equalityAtMaximumBounds.setDefaultFilters(List.of(maximumLimiter));

    GatewayProperties equality = new GatewayProperties();
    FilterDefinition equalityLimiter = limiter("20", "40");
    equalityLimiter.getArgs().put("redis-rate-limiter.requestedTokens", "40");
    equality.setDefaultFilters(List.of(equalityLimiter));

    assertThatThrownBy(
            () -> GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(oversizedRequest))
        .isInstanceOf(IllegalStateException.class);
    var arguments =
        GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(equalityAtMaximumBounds);
    assertThat(arguments.replenishRate()).isEqualTo(Integer.MAX_VALUE);
    assertThat(arguments.burstCapacity()).isEqualTo(Long.MAX_VALUE);
    assertThat(arguments.requestedTokens()).isEqualTo(Integer.MAX_VALUE);
    assertThat(
            GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(equality)
                .requestedTokens())
        .isEqualTo(40);
  }

  @Test
  void rejectsMissingDuplicateOrNonpositiveDefaultRateLimiterConfiguration() {
    GatewayProperties missing = new GatewayProperties();
    missing.setDefaultFilters(List.of());

    GatewayProperties duplicate = new GatewayProperties();
    duplicate.setDefaultFilters(List.of(limiter("20", "40"), limiter("10", "20")));

    GatewayProperties malformed = new GatewayProperties();
    malformed.setDefaultFilters(List.of(limiter("0", "40")));

    GatewayProperties unsupported = new GatewayProperties();
    FilterDefinition unsupportedLimiter = limiter("20", "40");
    unsupportedLimiter.getArgs().put("key-resolver", "customKeyResolver");
    unsupported.setDefaultFilters(List.of(unsupportedLimiter));

    assertThatThrownBy(() -> GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(missing))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(duplicate))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(malformed))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(unsupported))
        .isInstanceOf(IllegalStateException.class);
  }

  private static FilterDefinition limiter(String replenishRate, String burstCapacity) {
    FilterDefinition limiter = new FilterDefinition("RequestRateLimiter");
    limiter.setArgs(
        new java.util.HashMap<>(
            Map.of(
                "redis-rate-limiter.replenishRate", replenishRate,
                "redis-rate-limiter.burstCapacity", burstCapacity)));
    return limiter;
  }
}
