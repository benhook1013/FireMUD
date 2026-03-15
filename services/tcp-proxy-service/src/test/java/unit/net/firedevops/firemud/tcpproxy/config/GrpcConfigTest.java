package net.firedevops.firemud.tcpproxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import net.firedevops.firemud.common.grpc.TracingInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Unit tests for {@link GrpcConfig} verifying conditional bean creation. */
class GrpcConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withUserConfiguration(GrpcConfig.class);

  @Test
  void tracingInterceptorNotLoadedWhenTracerMissing() {
    contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(TracingInterceptor.class));
  }

  @Test
  void tracingInterceptorLoadedWhenTracerPresent() {
    contextRunner
        .withBean(Tracer.class, () -> Mockito.mock(Tracer.class))
        .run(ctx -> assertThat(ctx).hasSingleBean(TracingInterceptor.class));
  }
}
