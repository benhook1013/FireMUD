package net.firedevops.firemud.springcloudgateway.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/** Fixed isolated-handler adapter for the Gateway token bucket, without its fail-open fallback. */
final class AccountCheckoutReceivingRateLimiter
    implements RateLimiter<GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments> {
  private final ReactiveStringRedisTemplate redisTemplate;
  private final RedisScript<List<Long>> script;
  private final GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments configuration;
  private final List<String> arguments;

  AccountCheckoutReceivingRateLimiter(
      ReactiveStringRedisTemplate redisTemplate,
      RedisScript<List<Long>> script,
      GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments configuration) {
    this.redisTemplate = Objects.requireNonNull(redisTemplate);
    this.script = Objects.requireNonNull(script);
    this.configuration = Objects.requireNonNull(configuration);
    // Retain the installed Gateway's token-bucket configuration constraints as well as its script.
    new RedisRateLimiter.Config()
        .setReplenishRate(configuration.replenishRate())
        .setBurstCapacity(configuration.burstCapacity())
        .setRequestedTokens(configuration.requestedTokens());
    this.arguments =
        List.of(
            Integer.toString(configuration.replenishRate()),
            Long.toString(configuration.burstCapacity()),
            "",
            Integer.toString(configuration.requestedTokens()));
  }

  @Override
  public Mono<Response> isAllowed(String routeId, String id) {
    // Match Gateway's cluster hash tag and let the existing Lua script obtain Redis server time.
    String prefix = "request_rate_limiter.{" + routeId + "." + id + "}.";
    var keys = List.of(prefix + "tokens", prefix + "timestamp");
    return Mono.defer(() -> redisTemplate.execute(script, keys, arguments).single())
        .map(this::decision);
  }

  private Response decision(List<?> result) {
    if (result.size() != 2
        || !(result.get(0) instanceof Long allowed)
        || (allowed != 0L && allowed != 1L)
        || !(result.get(1) instanceof Long remaining)
        || remaining < 0L
        || remaining > configuration.burstCapacity()
        || (allowed == 1L
            && remaining > configuration.burstCapacity() - configuration.requestedTokens())
        || (allowed == 0L && remaining >= configuration.requestedTokens())) {
      throw new IllegalStateException("Gateway rate-limit result unavailable");
    }
    return new Response(allowed == 1L, Map.of());
  }

  @Override
  public Map<String, GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments> getConfig() {
    return Map.of();
  }

  @Override
  public Class<GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments> getConfigClass() {
    return GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments.class;
  }

  @Override
  public GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments newConfig() {
    return configuration;
  }
}
