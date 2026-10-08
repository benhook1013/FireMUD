package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsServerCredentials;
import java.util.List;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverEngine;
import net.firedevops.firemud.gamesession.service.impl.GameSessionJwtReadinessReceiverGrpcService;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ShadedNettyGrpcServerFactory;
import org.springframework.grpc.server.service.GrpcService;

class GameSessionJwtReadinessReceiverConfigurationTest {
  private static final String ENABLED = "firemud.game-session.jwt-readiness.receiver.enabled=true";

  private final ApplicationContextRunner routingRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(GameSessionJwtReadinessIsolatedGrpcRoutingConfiguration.class);

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              GameSessionJwtReadinessReceiverConfiguration.class,
              GameSessionJwtReadinessReceiverGrpcService.class);

  @Test
  void grpcAdapterUsesOnlyThePeerIdentityInterceptor() {
    GrpcService grpcService =
        GameSessionJwtReadinessReceiverGrpcService.class.getAnnotation(GrpcService.class);

    assertThat(grpcService).isNotNull();
    assertThat(grpcService.interceptorNames()).containsExactly("grpcPeerIdentityInterceptor");
    assertThat(grpcService.blendWithGlobalInterceptors()).isFalse();
  }

  @Test
  void receiverAndCryptoCompositionRemainAbsentByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context)
              .doesNotHaveBean(GameSessionJwtReadinessReceiverEngine.class)
              .doesNotHaveBean(GameSessionJwtReadinessLocalIdentityProvider.class)
              .doesNotHaveBean(GameSessionJwtReadinessReceiverGrpcService.class);
        });
  }

  @Test
  void explicitEnablementWithoutProtectedLocalIdentityStillLeavesReceiverUnregistered() {
    contextRunner
        .withPropertyValues(ENABLED)
        .run(
            context -> {
              assertThat(context)
                  .doesNotHaveBean(GameSessionJwtReadinessReceiverEngine.class)
                  .doesNotHaveBean(GameSessionJwtReadinessLocalIdentityProvider.class)
                  .doesNotHaveBean(GameSessionJwtReadinessReceiverGrpcService.class);
            });
  }

  @Test
  void disabledRoutingDoesNotAlterFilteringOrRequireSupportedFactory() {
    routingRunner.run(
        context -> {
          var customizer = context.getBean(GrpcServerFactoryCustomizer.class);
          var factory = factory();
          factory.setInterceptorFilter((interceptor, service) -> false);
          customizer.customize(factory);
          assertThat(
                  factory.supports(mock(ServerInterceptor.class), definition("ordinary.Service")))
              .isFalse();
          customizer.customize(mock(GrpcServerFactory.class));
        });
  }

  @Test
  void selectedReceiverFiltersOnlyExactServiceAndRequiresSupportedFactory() {
    routingRunner
        .withPropertyValues(ENABLED)
        .run(
            context -> {
              var customizer = context.getBean(GrpcServerFactoryCustomizer.class);
              var factory = factory();
              customizer.customize(factory);
              var interceptor = mock(ServerInterceptor.class);
              assertThat(
                      factory.supports(
                          interceptor,
                          definition(GameSessionJwtReadinessReceiverServiceGrpc.SERVICE_NAME)))
                  .isFalse();
              assertThat(factory.supports(interceptor, definition("ordinary.Service"))).isTrue();
              assertThat(
                      factory.supports(
                          interceptor,
                          definition(
                              GameSessionJwtReadinessReceiverServiceGrpc.SERVICE_NAME + "Extra")))
                  .isTrue();
              assertThatThrownBy(() -> customizer.customize(mock(GrpcServerFactory.class)))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("interceptor filtering");
            });
  }

  private static ShadedNettyGrpcServerFactory factory() {
    return new ShadedNettyGrpcServerFactory(
        "127.0.0.1:0", List.of(), null, null, TlsServerCredentials.ClientAuth.REQUIRE);
  }

  private static ServerServiceDefinition definition(String name) {
    return ServerServiceDefinition.builder(name).build();
  }
}
