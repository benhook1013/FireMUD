package net.firedevops.firemud.common.config;

import io.grpc.BindableService;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.grpc.server.autoconfigure.ConditionalOnGrpcNativeServer;
import org.springframework.boot.grpc.server.autoconfigure.ConditionalOnGrpcServerEnabled;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.util.LambdaSafe;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcServiceConfigurer;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;

/** Owns the shared production registration boundary; alternate factories fail startup. */
@AutoConfiguration(
    beforeName =
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration")
@ConditionalOnClass({GrpcServerFactory.class, NettyServerBuilder.class})
@ConditionalOnBean(BindableService.class)
@ConditionalOnGrpcServerEnabled
@EnableConfigurationProperties(GrpcServerProperties.class)
public class CommonGrpcRequestBoundsAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(GrpcRequestBytePolicy.class)
  GrpcRequestBytePolicy ordinaryGrpcRequestBytePolicy() {
    return GrpcRequestBytePolicy.ordinary();
  }

  @Bean
  @ConditionalOnGrpcNativeServer
  BoundedGrpcServerFactory boundedGrpcServerFactory(
      GrpcServerProperties properties,
      GrpcRequestBytePolicy policy,
      GrpcServiceDiscoverer discoverer,
      GrpcServiceConfigurer configurer,
      ObjectProvider<SslBundles> bundles,
      ObjectProvider<ServerBuilderCustomizer<?>> builderCustomizers,
      ObjectProvider<GrpcServerFactoryCustomizer> factoryCustomizers) {
    KeyManagerFactory keys = null;
    TrustManagerFactory trust = null;
    if (properties.getSsl().determineEnabled()) {
      String bundleName = properties.getSsl().getBundle();
      if (bundleName == null || bundleName.isBlank()) {
        throw new IllegalStateException("A gRPC SSL bundle is required");
      }
      SslBundle bundle = bundles.getObject().getBundle(bundleName);
      keys = bundle.getManagers().getKeyManagerFactory();
      trust =
          properties.getSsl().isSecure()
              ? bundle.getManagers().getTrustManagerFactory()
              : InsecureTrustManagerFactory.INSTANCE;
    }
    // Preserve the framework's 4 MiB default; policy maxima only constrain explicit opt-in.
    int transportMaximum = Math.toIntExact(properties.getMaxInboundMessageSize().toBytes());
    List<ServerBuilderCustomizer<NettyServerBuilder>> customizers =
        List.of(
            builder -> configureProperties(builder, properties),
            builder ->
                applyBuilderCustomizers(builder, builderCustomizers.orderedStream().toList()));
    BoundedGrpcServerFactory factory =
        new BoundedGrpcServerFactory(
            properties.determineAddress(),
            customizers,
            keys,
            trust,
            properties.getSsl().getClientAuth(),
            policy,
            transportMaximum);
    factoryCustomizers.orderedStream().forEach(customizer -> customizer.customize(factory));
    discoverer
        .findServices()
        .forEach(service -> factory.addService(configurer.configure(service, factory)));
    return factory;
  }

  @Bean
  @ConditionalOnMissingBean(GrpcServerLifecycle.class)
  GrpcServerLifecycle boundedGrpcServerLifecycle(
      BoundedGrpcServerFactory factory,
      GrpcServerProperties properties,
      ApplicationEventPublisher publisher) {
    return new GrpcServerLifecycle(factory, properties.getShutdownGracePeriod(), publisher);
  }

  @Bean
  SmartInitializingSingleton requireOnlyBoundedGrpcServerFactory(ApplicationContext context) {
    return () -> {
      var factories = context.getBeansOfType(GrpcServerFactory.class);
      if (factories.size() != 1
          || factories.values().iterator().next().getClass() != BoundedGrpcServerFactory.class) {
        throw new IllegalStateException(
            "Production gRPC requires exactly one bounded server factory");
      }
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void applyBuilderCustomizers(
      NettyServerBuilder builder, List<ServerBuilderCustomizer<?>> customizers) {
    LambdaSafe.callbacks(ServerBuilderCustomizer.class, customizers, builder)
        .invoke(customizer -> customizer.customize(builder));
  }

  private static void configureProperties(
      NettyServerBuilder builder, GrpcServerProperties properties) {
    var keepAlive = properties.getKeepAlive();
    duration(keepAlive.getTime(), builder::keepAliveTime);
    duration(keepAlive.getTimeout(), builder::keepAliveTimeout);
    duration(keepAlive.getMaxIdle(), builder::maxConnectionIdle);
    duration(keepAlive.getMaxAge(), builder::maxConnectionAge);
    duration(keepAlive.getMaxAgeGrace(), builder::maxConnectionAgeGrace);
    duration(keepAlive.getPermitTime(), builder::permitKeepAliveTime);
    builder.permitKeepAliveWithoutCalls(keepAlive.isPermitWithoutCalls());
    builder.maxInboundMetadataSize(
        Math.toIntExact(properties.getMaxInboundMetadataSize().toBytes()));
  }

  private static void duration(Duration duration, BiConsumer<Long, TimeUnit> apply) {
    if (duration != null) {
      apply.accept(duration.toNanos(), TimeUnit.NANOSECONDS);
    }
  }
}
