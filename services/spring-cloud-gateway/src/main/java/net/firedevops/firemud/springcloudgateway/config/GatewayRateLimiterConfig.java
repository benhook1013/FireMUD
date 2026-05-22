package net.firedevops.firemud.springcloudgateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Canonical low-cardinality key resolution for gateway-wide request rate limiting. */
@Configuration
public class GatewayRateLimiterConfig {
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";
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

    clientIp = firstForwardedFor(exchange.getRequest().getHeaders());
    if (clientIp != null) {
      return clientIp;
    }

    InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
    if (remoteAddress != null && remoteAddress.getAddress() != null) {
      return remoteAddress.getAddress().getHostAddress();
    }
    return UNKNOWN_CLIENT_KEY;
  }

  private String firstForwardedFor(HttpHeaders headers) {
    String xff = headers.getFirst(X_FORWARDED_FOR);
    if (xff == null || xff.isBlank()) {
      return null;
    }
    String[] segments = xff.split(",");
    if (segments.length == 0) {
      return null;
    }
    String candidate = segments[0].trim();
    return candidate.isEmpty() ? null : candidate;
  }
}
