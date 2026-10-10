package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsServerCredentials;
import java.util.List;
import net.firedevops.firemud.account.v1.AccountJwtReadinessPodReceiverServiceGrpc;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.AccountJwtSignerMaterializationServiceGrpc;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtReadinessPodReceiverGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtReadinessProbeOwnerGrpcService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ShadedNettyGrpcServerFactory;
import org.springframework.grpc.server.service.GrpcService;

class AccountJwtReadinessIsolatedGrpcRoutingTest {
  private final ApplicationContextRunner routingRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountJwtReadinessIsolatedGrpcRoutingConfiguration.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              AccountJwtReadinessProbeOwnerGrpcService.class,
              AccountJwtReadinessPodReceiverGrpcService.class);

  @Test
  void bothAdaptersUseOnlyThePeerIdentityInterceptor() {
    assertIsolatedGrpcService(AccountJwtReadinessProbeOwnerGrpcService.class);
    assertIsolatedGrpcService(AccountJwtReadinessPodReceiverGrpcService.class);
  }

  @Test
  void bothAdaptersRemainUnregisteredByDefault() {
    contextRunner.run(
        context ->
            assertThat(context)
                .doesNotHaveBean(AccountJwtReadinessProbeOwnerGrpcService.class)
                .doesNotHaveBean(AccountJwtReadinessPodReceiverGrpcService.class));
  }

  @Test
  void disabledRoutingLeavesExistingFactoryFilteringAndUnsupportedFactoriesAlone() {
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
  void eachFlagFiltersOnlyItsExactServiceAndEnabledRoutingRejectsUnsupportedFactories() {
    for (boolean owner : List.of(false, true)) {
      for (boolean receiver : List.of(false, true)) {
        for (boolean materialization : List.of(false, true)) {
          routingRunner
              .withPropertyValues(
                  "firemud.account.jwt-readiness.probe-owner.enabled=" + owner,
                  "firemud.account.jwt-readiness.pod-receiver.enabled=" + receiver,
                  "firemud.account.jwt-signer.materialization.enabled=" + materialization)
              .run(
                  context -> {
                    var customizer = context.getBean(GrpcServerFactoryCustomizer.class);
                    var factory = factory();
                    customizer.customize(factory);
                    var interceptor = mock(ServerInterceptor.class);
                    assertThat(
                            factory.supports(
                                interceptor,
                                definition(AccountJwtReadinessProbeOwnerServiceGrpc.SERVICE_NAME)))
                        .isEqualTo(!owner);
                    assertThat(
                            factory.supports(
                                interceptor,
                                definition(AccountJwtReadinessPodReceiverServiceGrpc.SERVICE_NAME)))
                        .isEqualTo(!receiver);
                    assertThat(
                            factory.supports(
                                interceptor,
                                definition(
                                    AccountJwtSignerMaterializationServiceGrpc.SERVICE_NAME)))
                        .isEqualTo(!materialization);
                    assertThat(factory.supports(interceptor, definition("ordinary.Service")))
                        .isTrue();
                    assertThat(
                            factory.supports(
                                interceptor,
                                definition(
                                    AccountJwtReadinessProbeOwnerServiceGrpc.SERVICE_NAME
                                        + "Extra")))
                        .isTrue();
                    assertThat(
                            factory.supports(
                                interceptor,
                                definition(
                                    AccountJwtSignerMaterializationServiceGrpc.SERVICE_NAME
                                        + "Extra")))
                        .isTrue();
                    if (owner || receiver || materialization) {
                      assertThatThrownBy(() -> customizer.customize(mock(GrpcServerFactory.class)))
                          .isInstanceOf(IllegalStateException.class)
                          .hasMessageContaining("interceptor filtering");
                    } else {
                      customizer.customize(mock(GrpcServerFactory.class));
                    }
                  });
        }
      }
    }
  }

  private static ShadedNettyGrpcServerFactory factory() {
    return new ShadedNettyGrpcServerFactory(
        "127.0.0.1:0", List.of(), null, null, TlsServerCredentials.ClientAuth.REQUIRE);
  }

  private static ServerServiceDefinition definition(String name) {
    return ServerServiceDefinition.builder(name).build();
  }

  private static void assertIsolatedGrpcService(Class<?> serviceType) {
    GrpcService grpcService = serviceType.getAnnotation(GrpcService.class);
    ConditionalOnProperty property = serviceType.getAnnotation(ConditionalOnProperty.class);

    assertThat(grpcService).isNotNull();
    assertThat(grpcService.interceptorNames()).containsExactly("grpcPeerIdentityInterceptor");
    assertThat(grpcService.blendWithGlobalInterceptors()).isFalse();
    assertThat(property).isNotNull();
    assertThat(property.name()).containsExactly("enabled");
    assertThat(property.havingValue()).isEqualTo("true");
    assertThat(property.matchIfMissing()).isFalse();
  }
}
