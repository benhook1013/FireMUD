package net.firedevops.firemud.gamesession.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for gRPC interceptors and OpenTelemetry. */
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
}
