package net.firedevops.firemud.tcpproxy.config;

import net.firedevops.firemud.common.config.RedisProperties;
import net.firedevops.firemud.gamesession.cache.RedisLookCacheService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@ConditionalOnProperty(
    prefix = "firemud.redis",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(RedisProperties.class)
@Import(RedisLookCacheService.class)
public class TelnetRedisConfiguration {
  @Bean
  public RedisConnectionFactory redisConnectionFactory(RedisProperties redis) {
    return new LettuceConnectionFactory(redis.getHost(), redis.getPort());
  }

  @Bean
  public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    return template;
  }
}
