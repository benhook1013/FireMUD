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
  void keepsDefaultReadinessPostProcessorWhenApplicationProvidesUnrelatedOne() {
    contextRunner
        .withUserConfiguration(CustomPostProcessorConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasBean("postProcessor");
              assertThat(context)
                  .hasSingleBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class);
              assertThat(context.getBeansOfType(HealthEndpointGroupsPostProcessor.class)).hasSize(2);
              assertThat(context.getBean("tlsCertificateReadinessHealthEndpointGroupsPostProcessor"))
                  .isNotSameAs(context.getBean("postProcessor"));
            });
  }

  @Test
  void backsOffReadinessPostProcessorWhenApplicationProvidesSameType() {
    contextRunner
        .withUserConfiguration(CustomTlsReadinessPostProcessorConfiguration.class)
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class);
              assertThat(context.getBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class))
                  .isSameAs(context.getBean("tlsReadinessPostProcessor"));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomPostProcessorConfiguration {
    @Bean
    HealthEndpointGroupsPostProcessor postProcessor() {
      return mock(HealthEndpointGroupsPostProcessor.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomTlsReadinessPostProcessorConfiguration {
    @Bean
    TlsCertificateReadinessHealthEndpointGroupsPostProcessor tlsReadinessPostProcessor() {
      return new TlsCertificateReadinessHealthEndpointGroupsPostProcessor("custom-service");
    }
  }
}
