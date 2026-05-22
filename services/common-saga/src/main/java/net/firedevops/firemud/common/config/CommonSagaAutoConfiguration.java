package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration")
public class CommonSagaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public SagaMetrics sagaMetrics(MeterRegistry registry) {
    return new SagaMetrics(registry);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.database",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnClass(DSLContext.class)
  @ConditionalOnMissingBean(SagaInstanceRepository.class)
  public SagaInstanceRepository sagaInstanceRepository(
      DSLContext dsl,
      @Value("${SERVICE_SCHEMA:${firemud.postgres.schema:public}}") String serviceSchema) {
    return new SagaInstanceRepository(dsl, serviceSchema);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.database",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnClass(DSLContext.class)
  @ConditionalOnMissingBean(SagaStepRepository.class)
  public SagaStepRepository sagaStepRepository(
      DSLContext dsl,
      @Value("${SERVICE_SCHEMA:${firemud.postgres.schema:public}}") String serviceSchema) {
    return new SagaStepRepository(dsl, serviceSchema);
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
