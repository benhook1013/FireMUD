package net.firedevops.firemud.common.grpc;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/** Shared gRPC server interceptors for FireMUD services. */
@Configuration
@ConditionalOnClass(GlobalServerInterceptor.class)
public class CommonGrpcServerConfiguration {

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnMissingBean(LoggingInterceptor.class)
  public LoggingInterceptor loggingInterceptor(RuntimeIdentity runtimeIdentity) {
    return new LoggingInterceptor(runtimeIdentity);
  }

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnMissingBean(MetricsInterceptor.class)
  public MetricsInterceptor metricsInterceptor(MeterRegistry registry) {
    return new MetricsInterceptor(registry);
  }

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnBean(Tracer.class)
  @ConditionalOnMissingBean(TracingInterceptor.class)
  public TracingInterceptor tracingInterceptor(Tracer tracer) {
    return new TracingInterceptor(tracer);
  }
}
