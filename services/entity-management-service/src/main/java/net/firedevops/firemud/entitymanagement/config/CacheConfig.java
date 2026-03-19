package net.firedevops.firemud.entitymanagement.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/** Configures Spring Cache to use Redis for entity graph caching. */
@Configuration
@EnableCaching
@EnableConfigurationProperties(EntityCacheProperties.class)
public class CacheConfig {

  @Bean
  public RedisCacheManager cacheManager(
      RedisConnectionFactory factory, EntityCacheProperties properties) {
    RedisCacheConfiguration defaultConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJacksonJsonRedisSerializer.builder()
                        .enableSpringCacheNullValueSupport()
                        .enableUnsafeDefaultTyping()
                        .build()))
            .entryTtl(Duration.ofSeconds(properties.getCharacterGraphTtlSeconds()));

    return RedisCacheManager.builder(factory).cacheDefaults(defaultConfig).build();
  }
}
