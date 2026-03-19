package net.firedevops.firemud.gamelogic.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/** Registers gRPC interceptors for logging, metrics, and tracing. */
@Configuration
public class GrpcServerConfig {

  @Bean
  @GlobalServerInterceptor
  public LoggingInterceptor loggingInterceptor() {
    return new LoggingInterceptor();
  }

  @Bean
  @GlobalServerInterceptor
  public MetricsInterceptor metricsInterceptor(MeterRegistry meterRegistry) {
    return new MetricsInterceptor(meterRegistry);
  }

  @Bean
  @GlobalServerInterceptor
  public TracingInterceptor tracingInterceptor(Tracer tracer) {
    return new TracingInterceptor(tracer);
  }
}
