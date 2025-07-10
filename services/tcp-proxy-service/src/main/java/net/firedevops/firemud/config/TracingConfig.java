package net.firedevops.firemud.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Basic OpenTelemetry configuration exporting spans to the OTLP collector. */
@Configuration
public class TracingConfig {

  @Value("${otel.endpoint:http://otel-collector:4317}")
  private String otelEndpoint;

  @Bean
  public OpenTelemetry openTelemetry() {
    OtlpGrpcSpanExporter exporter =
        OtlpGrpcSpanExporter.builder().setEndpoint(otelEndpoint).build();

    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), "tcp-proxy-service")))
            .build();

    return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
  }

  @Bean
  public Tracer tracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("tcp-proxy-service");
  }
}
