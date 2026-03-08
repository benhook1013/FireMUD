package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ServiceEndpointsProperties.class)
public class CommonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MeterRegistry.class)
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  public MeterRegistryCustomizer<MeterRegistry> commonServiceTag(
      @Value("${spring.application.name:unknown}") String serviceName) {
    return registry -> registry.config().commonTags("service", serviceName);
  }
}
