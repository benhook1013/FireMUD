package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.websocket.DevEchoWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

class DevWebSocketConfigTest {

  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withUserConfiguration(DevWebSocketConfig.class, MeterRegistryConfig.class);

  @Test
  void createsBeansWhenDevProfileActive() {
    contextRunner
        .withPropertyValues("spring.profiles.active=dev")
        .run(
            context -> {
              assertThat(context).hasSingleBean(DevEchoWebSocketHandler.class);
              assertThat(context).hasSingleBean(SimpleUrlHandlerMapping.class);
              SimpleUrlHandlerMapping handlerMapping =
                  context.getBean(SimpleUrlHandlerMapping.class);
              Object handler = context.getBean(DevEchoWebSocketHandler.class);

              assertThat(handlerMapping.getUrlMap().get("/dev/echo")).isSameAs(handler);
            });
  }

  @Test
  void doesNotRegisterBeansOutsideDevProfile() {
    contextRunner
        .withPropertyValues("spring.profiles.active=prod")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(DevEchoWebSocketHandler.class);
              assertThat(context).doesNotHaveBean(HandlerMapping.class);
            });
  }

  @Configuration
  static class MeterRegistryConfig {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    RuntimeIdentity runtimeIdentity() {
      return new RuntimeIdentity(
          "spring-cloud-gateway", "gateway-test", "localhost", Instant.EPOCH, null, null, null);
    }
  }
}
