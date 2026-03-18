package net.firedevops.firemud.accountservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration
public class GrpcConfig {
  @Bean
  @GlobalServerInterceptor
  public LoggingInterceptor loggingInterceptor() {
    return new LoggingInterceptor();
  }

  @Bean
  @GlobalServerInterceptor
  public MetricsInterceptor metricsInterceptor(MeterRegistry registry) {
    return new MetricsInterceptor(registry);
  }

  @Bean
  @GlobalServerInterceptor
  public TracingInterceptor tracingInterceptor(Tracer tracer) {
    return new TracingInterceptor(tracer);
  }

  @Bean
  @GlobalServerInterceptor
  public AuthTokenInterceptor authTokenInterceptor(
      net.firedevops.firemud.common.security.JwtUtil jwtUtil) {
    return new AuthTokenInterceptor(
        jwtUtil,
        Set.of(
            AccountServiceGrpc.getAuthenticateMethod().getFullMethodName(),
            AccountServiceGrpc.getPingMethod().getFullMethodName()));
  }
}
