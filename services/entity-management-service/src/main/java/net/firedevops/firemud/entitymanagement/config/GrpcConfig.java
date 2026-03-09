package net.firedevops.firemud.entitymanagement.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers global gRPC interceptors for logging, metrics and tracing. */
@Configuration
public class GrpcConfig {

  @Bean
  @GRpcGlobalInterceptor
  public LoggingInterceptor loggingInterceptor() {
    return new LoggingInterceptor();
  }

  @Bean
  @GRpcGlobalInterceptor
  public MetricsInterceptor metricsInterceptor(MeterRegistry registry) {
    return new MetricsInterceptor(registry);
  }

  @Bean
  @GRpcGlobalInterceptor
  public TracingInterceptor tracingInterceptor(Tracer tracer) {
    return new TracingInterceptor(tracer);
  }

  @Bean
  public Tracer tracer(io.opentelemetry.api.OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("entity-management-service");
  }
}
