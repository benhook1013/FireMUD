package net.firedevops.firemud.tcpproxy;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.gamesession.CrossServiceAppHarness;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;

/** Shared Game Session overrides for gateway-backed TCP proxy cross-service tests. */
@TestConfiguration
public class GatewayBackedGameSessionTestOverrides
    extends CrossServiceAppHarness.GameSessionTestOverrides {

  @Bean
  RedisConnectionFactory redisConnectionFactory(
      @Value("${firemud.redis.host}") String redisHost,
      @Value("${firemud.redis.port}") int redisPort) {
    LettuceConnectionFactory factory = new LettuceConnectionFactory(redisHost, redisPort);
    factory.afterPropertiesSet();
    return factory;
  }

  @Bean
  RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.afterPropertiesSet();
    return template;
  }

  @Bean
  StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(factory);
    template.afterPropertiesSet();
    return template;
  }

  @Bean
  @Primary
  GatewayRouteService gatewayRouteService() {
    return new GatewayRouteService() {
      @Override
      public Mono<GatewayRoute> upsert(GatewayRoute route) {
        return Mono.just(route);
      }

      @Override
      public Mono<Boolean> remove(String routeId) {
        return Mono.just(true);
      }
    };
  }

  @Bean
  @Primary
  Tracer tracer() {
    return GlobalOpenTelemetry.getTracer("test");
  }
}
