package net.firedevops.firemud.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import net.firedevops.firemud.common.grpc.LoggingInterceptor;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.security.GrpcJwtAuthInterceptor;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for gRPC interceptors and OpenTelemetry tracing. */
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
  public GrpcJwtAuthInterceptor grpcJwtAuthInterceptor(JwtUtil jwtUtil) {
    return new GrpcJwtAuthInterceptor(jwtUtil);
  }

  @Bean
  public OpenTelemetry openTelemetry() {
    OtlpGrpcSpanExporter exporter =
        OtlpGrpcSpanExporter.builder().setEndpoint("http://otel-collector:4317").build();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
  }

  @Bean
  public Tracer tracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("world-management-service");
  }
}
