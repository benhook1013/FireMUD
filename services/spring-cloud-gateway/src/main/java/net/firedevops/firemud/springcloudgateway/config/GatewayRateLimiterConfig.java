package net.firedevops.firemud.springcloudgateway.config;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
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
  private static final String RATE_LIMITER_FILTER = "RequestRateLimiter";
  private static final String REPLENISH_RATE = "redis-rate-limiter.replenishRate";
  private static final String BURST_CAPACITY = "redis-rate-limiter.burstCapacity";
  private static final String REQUESTED_TOKENS = "redis-rate-limiter.requestedTokens";

  @Bean
  KeyResolver gatewayClientIpKeyResolver() {
    return exchange -> Mono.just(resolveClientKey(exchange));
  }

  static DefaultRequestRateLimiterArguments defaultRequestRateLimiterArguments(
      GatewayProperties properties) {
    if (properties == null) throw invalidRateLimiterConfiguration();
    List<FilterDefinition> filters = properties.getDefaultFilters();
    if (filters == null) throw invalidRateLimiterConfiguration();
    List<FilterDefinition> matches =
        filters.stream()
            .filter(filter -> RATE_LIMITER_FILTER.equalsIgnoreCase(filter.getName()))
            .toList();
    if (matches.size() != 1) throw invalidRateLimiterConfiguration();
    Map<String, String> args = matches.getFirst().getArgs();
    if (args == null
        || !Set.of(REPLENISH_RATE, BURST_CAPACITY, REQUESTED_TOKENS).containsAll(args.keySet())) {
      throw invalidRateLimiterConfiguration();
    }
    int replenishRate = parsePositiveInt(args.get(REPLENISH_RATE));
    long burstCapacity = parsePositiveLong(args.get(BURST_CAPACITY));
    int requestedTokens = parsePositiveInt(args.getOrDefault(REQUESTED_TOKENS, "1"));
    if (requestedTokens > burstCapacity) throw invalidRateLimiterConfiguration();
    return new DefaultRequestRateLimiterArguments(replenishRate, burstCapacity, requestedTokens);
  }

  private static int parsePositiveInt(String value) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0) throw invalidRateLimiterConfiguration();
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalidRateLimiterConfiguration();
    }
  }

  private static long parsePositiveLong(String value) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L) throw invalidRateLimiterConfiguration();
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalidRateLimiterConfiguration();
    }
  }

  private static IllegalStateException invalidRateLimiterConfiguration() {
    return new IllegalStateException("Canonical Gateway rate-limit configuration is required");
  }

  record DefaultRequestRateLimiterArguments(
      int replenishRate, long burstCapacity, int requestedTokens) {}

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
