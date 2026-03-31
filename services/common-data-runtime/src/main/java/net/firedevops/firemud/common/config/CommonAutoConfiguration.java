package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.RedisLookCacheService;
import net.firedevops.firemud.cache.RedisScreenBufferService;
import net.firedevops.firemud.cache.ScreenBufferService;
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

  @Bean
  @ConditionalOnBean(StringRedisTemplate.class)
  @ConditionalOnMissingBean(ScreenBufferService.class)
  public ScreenBufferService screenBufferService(
      StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
    return new RedisScreenBufferService(stringRedisTemplate, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(ScreenBufferService.class)
  public ScreenBufferService noopScreenBufferService() {
    return new ScreenBufferService() {
      @Override
      public void append(
          long tenantId, long gameInstanceId, long characterId, String protocolText) {}

      @Override
      public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
        return Optional.empty();
      }

      @Override
      public void clear(long tenantId, long gameInstanceId, long characterId) {}
    };
  }
}
