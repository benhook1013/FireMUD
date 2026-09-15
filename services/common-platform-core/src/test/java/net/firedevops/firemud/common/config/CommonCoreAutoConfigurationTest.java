package net.firedevops.firemud.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.common.health.TlsCertificateReadinessHealthEndpointGroupsPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CommonCoreAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CommonCoreAutoConfiguration.class));

  @Test
  void backsOffReadinessPostProcessorWhenApplicationProvidesOne() {
    contextRunner
        .withUserConfiguration(CustomPostProcessorConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(HealthEndpointGroupsPostProcessor.class);
              assertThat(context)
                  .doesNotHaveBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class);
              assertThat(context.getBean(HealthEndpointGroupsPostProcessor.class))
                  .isSameAs(context.getBean("postProcessor"));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomPostProcessorConfiguration {
    @Bean
    HealthEndpointGroupsPostProcessor postProcessor() {
      return mock(HealthEndpointGroupsPostProcessor.class);
    }
  }
}
