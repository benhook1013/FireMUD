package net.firedevops.firemud.common.config;

import net.firedevops.firemud.cache.RedisLookCacheService;
import net.firedevops.firemud.common.conflict.RedisConflictTracker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({
  CommonCoreAutoConfiguration.class,
  RedisConflictTracker.class,
  RedisLookCacheService.class
})
public class CommonAutoConfiguration {}
