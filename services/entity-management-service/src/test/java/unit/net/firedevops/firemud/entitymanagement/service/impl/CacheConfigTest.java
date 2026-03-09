package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import net.firedevops.firemud.entitymanagement.config.CacheConfig;
import net.firedevops.firemud.entitymanagement.config.EntityCacheProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** Unit tests for {@link CacheConfig}. */
class CacheConfigTest {
  @Test
  void characterGraphCacheUsesConfiguredTtl() {
    CacheConfig config = new CacheConfig();
    EntityCacheProperties props = new EntityCacheProperties();
    props.setCharacterGraphTtlSeconds(5);
    RedisConnectionFactory factory = Mockito.mock(RedisConnectionFactory.class);

    RedisCacheManager manager = config.cacheManager(factory, props);
    // trigger initialization
    manager.getCache("characterGraph");
    RedisCacheConfiguration cfg = manager.getCacheConfigurations().get("characterGraph");

    assertEquals(Duration.ofSeconds(5), cfg.getTtlFunction().getTimeToLive("characterGraph", null));
  }
}
