package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.cache.RedisLookCacheService;
import net.firedevops.firemud.common.conflict.RedisConflictTracker;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.common.security.RequireAdminRoleAspect;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({
  CommonCoreAutoConfiguration.class,
  RequireAdminRoleAspect.class,
  RedisConflictTracker.class,
  RedisLookCacheService.class
})
public class CommonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SagaMetrics sagaMetrics(MeterRegistry registry) {
    return new SagaMetrics(registry);
  }

  @Bean
  @ConditionalOnBean({SagaInstanceRepository.class, SagaStepRepository.class})
  @ConditionalOnMissingBean
  public SagaRunner sagaRunner(
      SagaMetrics sagaMetrics,
      SagaInstanceRepository instanceRepository,
      SagaStepRepository stepRepository) {
    return new SagaRunner(sagaMetrics, instanceRepository, stepRepository);
  }
}
