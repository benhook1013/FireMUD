package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.cache.RedisLookCacheService;
import net.firedevops.firemud.common.conflict.RedisConflictTracker;
import net.firedevops.firemud.common.grpc.GrpcServerTlsReloader;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.common.security.RequireAdminRoleAspect;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(ServiceEndpointsProperties.class)
@Import({
  TracingConfig.class,
  RequireAdminRoleAspect.class,
  GrpcServerTlsReloader.class,
  RedisConflictTracker.class,
  RedisLookCacheService.class
})
public class CommonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MeterRegistry.class)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.simple.SimpleMeterRegistry")
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  public MeterRegistryCustomizer<MeterRegistry> commonServiceTag(
      @Value("${spring.application.name:unknown}") String serviceName) {
    return registry -> registry.config().commonTags("service", serviceName);
  }

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
