package net.firedevops.firemud.springcloudgateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Canonical low-cardinality key resolution for gateway-wide request rate limiting. */
@Configuration
public class GatewayRateLimiterConfig {
  private static final String UNKNOWN_CLIENT_KEY = "unknown-client";

  @Bean
  KeyResolver gatewayClientIpKeyResolver() {
    return exchange -> Mono.just(resolveClientKey(exchange));
  }

  private String resolveClientKey(ServerWebExchange exchange) {
    String clientIp = exchange.getRequest().getHeaders().getFirst("X-Client-IP");
    if (clientIp != null && !clientIp.isBlank()) {
      return clientIp.trim();
    }

    InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
    if (remoteAddress != null && remoteAddress.getAddress() != null) {
      return remoteAddress.getAddress().getHostAddress();
    }
    return UNKNOWN_CLIENT_KEY;
  }
}
