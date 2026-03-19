package net.firedevops.firemud.springcloudgateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/** Registers gRPC interceptors for logging, metrics, and tracing. */
@Configuration
public class GrpcServerConfig {

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnMissingBean(LoggingInterceptor.class)
  public LoggingInterceptor loggingInterceptor() {
    return new LoggingInterceptor();
  }

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnMissingBean(MetricsInterceptor.class)
  public MetricsInterceptor metricsInterceptor(MeterRegistry meterRegistry) {
    return new MetricsInterceptor(meterRegistry);
  }

  @Bean
  @GlobalServerInterceptor
  @ConditionalOnBean(Tracer.class)
  @ConditionalOnMissingBean(TracingInterceptor.class)
  public TracingInterceptor tracingInterceptor(Tracer tracer) {
    return new TracingInterceptor(tracer);
  }
}
