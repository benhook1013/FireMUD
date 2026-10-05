package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayRateLimiterConfigTest {
  @Test
  void prefersGatewayOwnedClientIpHeader() {
    KeyResolver resolver = new GatewayRateLimiterConfig().gatewayClientIpKeyResolver();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/auth/player-bootstrap")
                .header("X-Client-IP", "198.51.100.10")
                .header("X-Forwarded-For", "203.0.113.99")
                .build());

    String key = resolver.resolve(exchange).block();

    assertThat(key).isEqualTo("198.51.100.10");
  }

  @Test
  void ignoresUncanonicalizedForwardedForWhenGatewayOwnedHeaderMissing() {
    KeyResolver resolver = new GatewayRateLimiterConfig().gatewayClientIpKeyResolver();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/auth/verify-email")
                .header("X-Forwarded-For", "203.0.113.77, 10.0.0.1")
                .remoteAddress(new java.net.InetSocketAddress("192.0.2.44", 0))
                .build());

    String key = resolver.resolve(exchange).block();

    assertThat(key).isEqualTo("192.0.2.44");
  }
}
