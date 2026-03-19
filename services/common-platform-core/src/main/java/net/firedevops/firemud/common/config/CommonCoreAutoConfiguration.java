package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcServerConfiguration;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerTlsReloader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@EnableConfigurationProperties({ServiceEndpointsProperties.class, CommonGrpcClientProperties.class})
@Import({TracingConfig.class, CommonGrpcServerConfiguration.class, GrpcServerTlsReloader.class})
public class CommonCoreAutoConfiguration {

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
  public GrpcChannelFactory grpcChannelFactory() {
    return new GrpcChannelFactory();
  }
}
