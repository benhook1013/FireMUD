package net.firedevops.firemud.springcloudgateway.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.springcloudgateway.client.AccountCheckoutHttpClient;
import net.firedevops.firemud.springcloudgateway.client.AccountControlUiAuthenticationClient;
import net.firedevops.firemud.springcloudgateway.controller.AccountCheckoutGatewayController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/** Opt-in composition for the isolated checkout/login receiving endpoints. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountCheckoutReceivingProperties.class)
@Import({AccountCheckoutGatewayController.class, GatewayRateLimiterConfig.class})
public final class AccountCheckoutReceivingConfiguration {
  private static final String PROFILE = "isolated-checkout-receiving";

  public AccountCheckoutReceivingConfiguration(
      Environment environment, AccountCheckoutReceivingProperties properties) {
    boolean isolatedProfile = Arrays.asList(environment.getActiveProfiles()).contains(PROFILE);
    boolean productionProfile =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    if (isolatedProfile && productionProfile) {
      throw new IllegalStateException(
          "Isolated checkout receiving cannot be activated with a production profile");
    }
    if (properties.isEnabled() && !isolatedProfile) {
      throw new IllegalStateException(
          "Checkout receiving requires the isolated-checkout-receiving profile");
    }
    if (properties.isEnabled()) properties.validateEnabledConfiguration();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      prefix = "firemud.account.checkout-receiving",
      name = "enabled",
      havingValue = "true")
  @org.springframework.context.annotation.Profile(PROFILE)
  public AccountControlUiAuthenticationClient accountControlUiAuthenticationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcProperties,
      GrpcChannelFactory channelFactory,
      AccountCheckoutReceivingProperties properties)
      throws SSLException, IOException {
    var client =
        new AccountControlUiAuthenticationClient(
            endpoints, grpcProperties, channelFactory, properties.getWorkloadNamespace());
    client.init();
    return client;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.account.checkout-receiving",
      name = "enabled",
      havingValue = "true")
  @org.springframework.context.annotation.Profile(PROFILE)
  public AccountCheckoutHttpClient accountCheckoutHttpClient(
      AccountCheckoutReceivingProperties properties) {
    return AccountCheckoutHttpClient.create(properties);
  }

  /** Applies canonical rate limiting before the receiving controller reads credential/body data. */
  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.account.checkout-receiving",
      name = "enabled",
      havingValue = "true")
  @org.springframework.context.annotation.Profile(PROFILE)
  public WebFilter accountCheckoutReceivingRateLimitWebFilter(
      GatewayProperties gatewayProperties,
      ReactiveStringRedisTemplate redisTemplate,
      @Qualifier("gatewayClientIpKeyResolver") KeyResolver keyResolver,
      @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script) {
    Objects.requireNonNull(redisTemplate, "Gateway Redis rate limiting must be available");
    GatewayRateLimiterConfig.DefaultRequestRateLimiterArguments arguments =
        GatewayRateLimiterConfig.defaultRequestRateLimiterArguments(gatewayProperties);
    var rateLimiter = new AccountCheckoutReceivingRateLimiter(redisTemplate, script, arguments);
    return receivingRateLimitWebFilter(rateLimiter, keyResolver);
  }

  static WebFilter receivingRateLimitWebFilter(
      RateLimiter<?> rateLimiter, KeyResolver keyResolver) {
    return new AccountCheckoutReceivingRateLimitWebFilter(rateLimiter, keyResolver);
  }

  private static final class AccountCheckoutReceivingRateLimitWebFilter
      implements WebFilter, Ordered {
    private static final PathPattern LOGIN_PATH =
        PathPatternParser.defaultInstance.parse("/api/account/auth/login");
    private static final PathPattern WEBHOOK_PATH =
        PathPatternParser.defaultInstance.parse("/api/account/billing/hosting/webhook");
    private static final String FIRST_INVOICE =
        "/api/account/billing/hosting/subscriptions/{subscriptionId}/first-invoice";
    private static final PathPattern FIRST_INVOICE_PATH =
        PathPatternParser.defaultInstance.parse(FIRST_INVOICE);
    private static final List<PathPattern> FIRST_INVOICE_ACTIONS =
        List.of("reauthenticate", "confirm", "renew").stream()
            .map(action -> PathPatternParser.defaultInstance.parse(FIRST_INVOICE + "/" + action))
            .toList();
    private static final String RATE_LIMITED_BODY = "{\"status\":\"ERROR\"}";

    private final RateLimiter<?> rateLimiter;
    private final KeyResolver keyResolver;

    private AccountCheckoutReceivingRateLimitWebFilter(
        RateLimiter<?> rateLimiter, KeyResolver keyResolver) {
      this.rateLimiter = Objects.requireNonNull(rateLimiter);
      this.keyResolver = Objects.requireNonNull(keyResolver);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
      if (!isReceivingRequest(exchange)) return chain.filter(exchange);
      return Mono.defer(
              () ->
                  keyResolver
                      .resolve(exchange)
                      .filter(key -> key != null && !key.isBlank())
                      .switchIfEmpty(
                          Mono.error(new IllegalStateException("Rate-limit key unavailable")))
                      .flatMap(key -> rateLimiter.isAllowed("account-checkout-receiving", key)))
          .materialize()
          .flatMap(
              result -> {
                var decision = result.isOnNext() ? result.get() : null;
                if (decision == null) {
                  return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
                }
                return decision.isAllowed()
                    ? chain.filter(exchange)
                    : reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
              });
    }

    private static boolean isReceivingRequest(ServerWebExchange exchange) {
      // PathPattern uses the same decoded, matrix-parameter-free segments as WebFlux routing.
      var path = exchange.getRequest().getPath().pathWithinApplication();
      var method = exchange.getRequest().getMethod();
      if (org.springframework.http.HttpMethod.POST.equals(method)) {
        return LOGIN_PATH.matches(path)
            || WEBHOOK_PATH.matches(path)
            || FIRST_INVOICE_ACTIONS.stream().anyMatch(pattern -> pattern.matches(path));
      }
      return (org.springframework.http.HttpMethod.GET.equals(method)
              || org.springframework.http.HttpMethod.HEAD.equals(method))
          && FIRST_INVOICE_PATH.matches(path);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
      var response = exchange.getResponse();
      response.setStatusCode(status);
      response.getHeaders().setCacheControl("no-store");
      response.getHeaders().setPragma("no-cache");
      response.getHeaders().setExpires(0);
      response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      return response.writeWith(
          Mono.just(
              response.bufferFactory().wrap(RATE_LIMITED_BODY.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
      // Run after HeaderTrustFilter (-4) so KeyResolver sees its canonical client-IP header.
      return -3;
    }
  }
}
