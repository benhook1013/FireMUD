package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
}
