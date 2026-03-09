package net.firedevops.firemud.automationscripting.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.automationscripting.security.GrpcJwtAuthInterceptor;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  @GRpcGlobalInterceptor
  public GrpcJwtAuthInterceptor grpcJwtAuthInterceptor(
      net.firedevops.firemud.common.security.JwtUtil jwtUtil) {
    return new GrpcJwtAuthInterceptor(jwtUtil);
  }
}
