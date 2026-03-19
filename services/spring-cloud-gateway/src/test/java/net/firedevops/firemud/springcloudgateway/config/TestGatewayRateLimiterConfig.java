package net.firedevops.firemud.springcloudgateway.config;

import java.util.Collections;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@TestConfiguration
public class TestGatewayRateLimiterConfig {

  @Bean
  @Primary
  RateLimiter<Object> testRateLimiter() {
    return new RateLimiter<>() {
      @Override
      public Mono<Response> isAllowed(String routeId, String id) {
        return Mono.just(new Response(true, Collections.emptyMap()));
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

  @Bean
  @Primary
  KeyResolver testKeyResolver() {
    return (ServerWebExchange exchange) -> Mono.just("test");
  }

  @Bean
  RequestRateLimiterGatewayFilterFactory testRequestRateLimiterGatewayFilterFactory() {
    return new RequestRateLimiterGatewayFilterFactory(testRateLimiter(), testKeyResolver());
  }
}
