package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.RedisLookCacheService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.common.conflict.RedisConflictTracker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@AutoConfigureAfter(DatabaseAutoConfiguration.class)
@Import(CommonCoreAutoConfiguration.class)
public class CommonAutoConfiguration {

  @Bean
  @ConditionalOnBean(StringRedisTemplate.class)
  @ConditionalOnMissingBean(ConflictTracker.class)
  public ConflictTracker conflictTracker(
      StringRedisTemplate stringRedisTemplate, MeterRegistry meterRegistry) {
    return new RedisConflictTracker(stringRedisTemplate, meterRegistry);
  }

  @Bean
  @ConditionalOnBean(StringRedisTemplate.class)
  @ConditionalOnMissingBean(LookCacheService.class)
  public LookCacheService lookCacheService(
      StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
    return new RedisLookCacheService(stringRedisTemplate, objectMapper);
  }
}
