package net.firedevops.firemud.common.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides OpenTelemetry beans configured for FireMUD services. */
@Configuration
public class TracingConfig {

  @Value("${otel.endpoint:http://otel-collector:4317}")
  private String otelEndpoint;

  @Value("${spring.application.name}")
  private String serviceName;

  @Bean
  @SuppressWarnings("null")
  public OpenTelemetry openTelemetry() {
    String endpoint = Objects.requireNonNullElse(otelEndpoint, "");
    if (isDisabledEndpoint(endpoint)) {
      return OpenTelemetry.noop();
    }
    String applicationName = Objects.requireNonNullElse(serviceName, "unknown-service");

    OtlpGrpcSpanExporter exporter =
        Objects.requireNonNull(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build());
    BatchSpanProcessor spanProcessor =
        Objects.requireNonNull(BatchSpanProcessor.builder(exporter).build());
    Attributes resourceAttributes =
        Objects.requireNonNull(
            Attributes.of(AttributeKey.stringKey("service.name"), applicationName));
    Resource resource = Objects.requireNonNull(Resource.create(resourceAttributes));

    SdkTracerProvider tracerProvider =
        Objects.requireNonNull(
            SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .setResource(resource)
                .build());

    return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
  }

  @Bean
  @SuppressWarnings("null")
  public Tracer tracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer(Objects.requireNonNullElse(serviceName, "unknown-service"));
  }

  private static boolean isDisabledEndpoint(String endpoint) {
    if (endpoint == null) {
      return true;
    }
    String normalized = endpoint.trim().toLowerCase(Locale.ROOT);
    return normalized.isEmpty() || normalized.equals("none") || normalized.equals("disabled");
  }
}
