package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.service.PlatformAuthBucketStore;
import net.firedevops.firemud.accountservice.service.impl.PlatformAuthCacheRedisClient;
import net.firedevops.firemud.accountservice.service.impl.RedisPlatformAuthBucketStore;
import net.firedevops.firemud.common.config.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires Account's isolated credential-abuse Cache Redis client without replacing Coord Redis. */
@Configuration
@EnableConfigurationProperties(PlatformAuthRateLimitProperties.class)
public class AccountPlatformAuthCacheConfiguration {
  @Bean(destroyMethod = "close")
  public PlatformAuthCacheRedisClient platformAuthCacheRedisClient(
      PlatformAuthRateLimitProperties properties, RedisProperties coordinationRedis) {
    return new PlatformAuthCacheRedisClient(properties.getCacheRedis(), coordinationRedis);
  }

  @Bean
  public PlatformAuthBucketStore platformAuthBucketStore(
      PlatformAuthCacheRedisClient cacheRedisClient) {
    return new RedisPlatformAuthBucketStore(cacheRedisClient);
  }
}
