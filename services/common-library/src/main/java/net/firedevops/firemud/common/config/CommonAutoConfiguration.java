package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcServerTlsReloader;
import net.firedevops.firemud.common.security.RequireAdminRoleAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(ServiceEndpointsProperties.class)
@Import({TracingConfig.class, RequireAdminRoleAspect.class, GrpcServerTlsReloader.class})
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
