package net.firedevops.firemud.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcServerConfiguration;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerTlsReloader;
import net.firedevops.firemud.common.grpc.GrpcTlsMaterialResolver;
import net.firedevops.firemud.common.health.HttpEndpointAvailabilityChecker;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeIdentityController;
import net.firedevops.firemud.common.runtime.RuntimeIdentityFactory;
import net.firedevops.firemud.common.runtime.RuntimeIdentityInfoContributor;
import net.firedevops.firemud.common.runtime.RuntimeIdentityStartupLogger;
import net.firedevops.firemud.common.settings.GameDesignSettingsAuthorityClient;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.common.web.ReactiveRequestLoggingFilter;
import net.firedevops.firemud.common.web.ServletRequestLoggingFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties({
  ServiceEndpointsProperties.class,
  CommonGrpcClientProperties.class,
  GameplayCatalogProperties.class
})
@Import({TracingConfig.class, CommonGrpcServerConfiguration.class, GrpcServerTlsReloader.class})
public class CommonCoreAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MeterRegistry.class)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.simple.SimpleMeterRegistry")
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  public MeterRegistryCustomizer<MeterRegistry> commonServiceTag(
      @Value("${spring.application.name:unknown}") String serviceName) {
    return registry -> registry.config().commonTags("service", serviceName);
  }

  @Bean
  @ConditionalOnMissingBean
  public RuntimeIdentityFactory runtimeIdentityFactory() {
    return new RuntimeIdentityFactory();
  }

  @Bean
  @ConditionalOnMissingBean
  public RuntimeIdentity runtimeIdentity(
      RuntimeIdentityFactory runtimeIdentityFactory,
      Environment environment,
      ObjectProvider<BuildProperties> buildProperties,
      ObjectProvider<GitProperties> gitProperties,
      @Value("${spring.application.name:unknown}") String serviceName) {
    return runtimeIdentityFactory.create(
        environment, serviceName, buildProperties.getIfAvailable(), gitProperties.getIfAvailable());
  }

  @Bean
  @ConditionalOnMissingBean
  public RuntimeIdentityStartupLogger runtimeIdentityStartupLogger(
      RuntimeIdentity runtimeIdentity, Environment environment) {
    return new RuntimeIdentityStartupLogger(runtimeIdentity, environment);
  }

  @Bean
  @ConditionalOnMissingBean(RuntimeIdentityInfoContributor.class)
  public InfoContributor runtimeIdentityInfoContributor(RuntimeIdentity runtimeIdentity) {
    return new RuntimeIdentityInfoContributor(runtimeIdentity);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
  public RuntimeIdentityController runtimeIdentityController(RuntimeIdentity runtimeIdentity) {
    return new RuntimeIdentityController(runtimeIdentity);
  }

  @Bean
  @ConditionalOnMissingBean
  public GrpcChannelFactory grpcChannelFactory() {
    return new GrpcChannelFactory();
  }

  @Bean
  @ConditionalOnMissingBean
  public GrpcTlsMaterialResolver grpcTlsMaterialResolver() {
    return new GrpcTlsMaterialResolver();
  }

  @Bean
  @ConditionalOnMissingBean(SharedSettingsAuthorityReader.class)
  public SharedSettingsAuthorityReader sharedSettingsAuthorityReader(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory grpcChannelFactory,
      ObjectProvider<BlockingGrpcStubCustomizer> stubCustomizer) {
    return new GameDesignSettingsAuthorityClient(
        endpoints,
        grpcClientProperties,
        grpcChannelFactory,
        stubCustomizer.getIfAvailable(BlockingGrpcStubCustomizer::noop));
  }

  @Bean
  @ConditionalOnMissingBean
  public SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver(
      SharedSettingsAuthorityReader sharedSettingsAuthorityReader) {
    return new SharedEffectiveSettingsResolver(sharedSettingsAuthorityReader);
  }

  @Bean
  @ConditionalOnMissingBean
  public HttpEndpointAvailabilityChecker httpEndpointAvailabilityChecker() {
    return new HttpEndpointAvailabilityChecker();
  }

  @Bean
  @ConditionalOnMissingBean
  public ReadinessTransitionTracker readinessTransitionTracker(MeterRegistry meterRegistry) {
    return new ReadinessTransitionTracker(meterRegistry);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = Type.SERVLET)
  @ConditionalOnClass(name = "jakarta.servlet.Filter")
  static class ServletLoggingConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ServletRequestLoggingFilter servletRequestLoggingFilter(RuntimeIdentity runtimeIdentity) {
      return new ServletRequestLoggingFilter(runtimeIdentity);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
  static class ReactiveLoggingConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ReactiveRequestLoggingFilter reactiveRequestLoggingFilter(RuntimeIdentity runtimeIdentity) {
      return new ReactiveRequestLoggingFilter(runtimeIdentity);
    }
  }
}
