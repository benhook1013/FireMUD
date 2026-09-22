package net.firedevops.firemud.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Objects;
import net.firedevops.firemud.common.health.TlsCertificateReadinessHealthEndpointGroupsPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.contributor.HealthIndicator;
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
              assertThat(context.getBeansOfType(HealthEndpointGroupsPostProcessor.class))
                  .hasSize(2);
              assertThat(
                      context.getBean("tlsCertificateReadinessHealthEndpointGroupsPostProcessor"))
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
              assertThat(
                      context.getBean(
                          TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class))
                  .isSameAs(context.getBean("tlsReadinessPostProcessor"));
            });
  }

  @Test
  void bindsReadinessGatePropertyForTcpProxy() {
    contextRunner
        .withPropertyValues(
            "spring.application.name=tcp-proxy-service", "firemud.tls.readiness-gate.enabled=false")
        .run(
            context -> {
              assertThat(context)
                  .hasBean(
                      TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                          .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR);
              assertThat(
                      context.getBean(
                          TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                              .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR))
                  .isInstanceOf(HealthIndicator.class);
              assertThat(context)
                  .hasSingleBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class);
              HealthEndpointGroups groups = mock(HealthEndpointGroups.class);
              assertSame(
                  groups,
                  context
                      .getBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class)
                      .postProcessHealthEndpointGroups(groups));
            });
  }

  @Test
  void defaultsReadinessGateToOff() {
    contextRunner.run(
        context -> {
          HealthEndpointGroups groups = mock(HealthEndpointGroups.class);

          assertSame(
              groups,
              context
                  .getBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class)
                  .postProcessHealthEndpointGroups(groups));
        });
  }

  @Test
  void enablesReadinessGateWhenExplicitlyConfigured() {
    contextRunner
        .withPropertyValues("firemud.tls.readiness-gate.enabled=true")
        .run(
            context -> {
              assertThat(context)
                  .hasBean(
                      TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                          .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR);
              assertThat(
                      context.getBean(
                          TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                              .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR))
                  .isInstanceOf(HealthIndicator.class);
              HealthEndpointGroups groups = mock(HealthEndpointGroups.class);
              HealthEndpointGroup readiness = mock(HealthEndpointGroup.class);
              when(groups.get("readiness")).thenReturn(readiness);

              HealthEndpointGroups processed =
                  context
                      .getBean(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class)
                      .postProcessHealthEndpointGroups(groups);

              assertThat(processed).isNotSameAs(groups);
              assertThat(
                      Objects.requireNonNull(processed.get("readiness"))
                          .isMember(
                              TlsCertificateReadinessHealthEndpointGroupsPostProcessor
                                  .TLS_CERTIFICATE_RELOAD_CONTRIBUTOR))
                  .isTrue();
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
      return new TlsCertificateReadinessHealthEndpointGroupsPostProcessor(true);
    }
  }
}
