package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaPersistenceEnabledMarker;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

@AutoConfiguration(after = DataJpaRepositoriesAutoConfiguration.class)
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
  @ConditionalOnBean(value = {EntityManagerFactory.class, SagaPersistenceEnabledMarker.class})
  @ConditionalOnMissingBean(SagaInstanceRepository.class)
  public SagaInstanceRepository sagaInstanceRepository(EntityManagerFactory entityManagerFactory) {
    return new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
        .getRepository(SagaInstanceRepository.class);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.database",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnBean(value = {EntityManagerFactory.class, SagaPersistenceEnabledMarker.class})
  @ConditionalOnMissingBean(SagaStepRepository.class)
  public SagaStepRepository sagaStepRepository(EntityManagerFactory entityManagerFactory) {
    return new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
        .getRepository(SagaStepRepository.class);
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
