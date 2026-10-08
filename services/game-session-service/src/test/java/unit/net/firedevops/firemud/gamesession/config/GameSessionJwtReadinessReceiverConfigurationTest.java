package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsServerCredentials;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.common.security.ProtectedPodUidProjection;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeOwnerClient;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProtectedLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverEngine;
import net.firedevops.firemud.gamesession.service.impl.GameSessionJwtReadinessReceiverGrpcService;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ShadedNettyGrpcServerFactory;
import org.springframework.grpc.server.service.GrpcService;

class GameSessionJwtReadinessReceiverConfigurationTest {
  private static final String ENABLED = "firemud.game-session.jwt-readiness.receiver.enabled=true";
  private static final String POD_UID = "44444444-4444-4444-8444-444444444444";

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
  void explicitEnablementComposesOnlyWithSpringManagedTlsIdentitySource() {
    contextRunner
        .withUserConfiguration(TestReadinessDependencies.class)
        .withBean(SslBundles.class, () -> mock(SslBundles.class))
        .withPropertyValues(ENABLED)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(GameSessionJwtReadinessLocalIdentityProvider.class)
                    .hasSingleBean(GameSessionJwtReadinessReceiverEngine.class)
                    .hasSingleBean(GameSessionJwtReadinessReceiverGrpcService.class));
  }

  @Test
  void selectedIdentityProviderRejectsMissingWorkloadNamespace() {
    contextRunner
        .withUserConfiguration(TestReadinessDependencies.class)
        .withBean(SslBundles.class, () -> mock(SslBundles.class))
        .withPropertyValues(ENABLED)
        .run(
            context ->
                assertThatThrownBy(
                        () -> context.getBean(GameSessionJwtReadinessLocalIdentityProvider.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class));
  }

  @Test
  void selectedIdentityProviderRejectsMissingFiremudGrpcLeafBeforeAccountRead() {
    try (MockedStatic<ProtectedPodUidProjection> uidProjection =
        mockStatic(ProtectedPodUidProjection.class)) {
      uidProjection.when(ProtectedPodUidProjection::read).thenReturn(POD_UID);
      contextRunner
          .withUserConfiguration(TestReadinessDependencies.class)
          .withBean(SslBundles.class, () -> mock(SslBundles.class))
          .withPropertyValues(ENABLED, "firemud.grpc.workload-namespace=firemud-prod")
          .run(
              context -> {
                GameSessionJwtReadinessProbeOwnerClient metadataReadClient =
                    context.getBean(GameSessionJwtReadinessProbeOwnerClient.class);
                var identityProvider =
                    context.getBean(GameSessionJwtReadinessLocalIdentityProvider.class);
                assertThatThrownBy(identityProvider::observe)
                    .isInstanceOf(
                        GameSessionJwtReadinessProtectedLocalIdentityProvider
                            .IdentityUnavailableException.class);
                verify(metadataReadClient, never())
                    .readCurrent(any(GetCurrentReadinessReceiverMetadataRequest.class));
              });
    }
  }

  @Test
  void selectedIdentityProviderRejectsUnavailableAccountMetadataRead() throws Exception {
    SslBundles sslBundles = tlsBundlesWithGameSessionLeaf();
    try (MockedStatic<ProtectedPodUidProjection> uidProjection =
        mockStatic(ProtectedPodUidProjection.class)) {
      uidProjection.when(ProtectedPodUidProjection::read).thenReturn(POD_UID);
      contextRunner
          .withUserConfiguration(TestReadinessDependencies.class)
          .withBean(SslBundles.class, () -> sslBundles)
          .withPropertyValues(ENABLED, "firemud.grpc.workload-namespace=firemud-prod")
          .run(
              context -> {
                GameSessionJwtReadinessProbeOwnerClient metadataClient =
                    context.getBean(GameSessionJwtReadinessProbeOwnerClient.class);
                doThrow(new GameSessionJwtReadinessProbeOwnerClient.OwnerReadUnavailableException())
                    .when(metadataClient)
                    .readCurrent(any(GetCurrentReadinessReceiverMetadataRequest.class));
                var identityProvider =
                    context.getBean(GameSessionJwtReadinessLocalIdentityProvider.class);
                assertThatThrownBy(identityProvider::observe)
                    .isInstanceOf(
                        GameSessionJwtReadinessProtectedLocalIdentityProvider
                            .IdentityUnavailableException.class);
                verify(metadataClient)
                    .readCurrent(any(GetCurrentReadinessReceiverMetadataRequest.class));
              });
    }
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

  private static GameSessionJwtReadinessProbeOwnerClient mockMetadataClient() {
    return mock(GameSessionJwtReadinessProbeOwnerClient.class);
  }

  private static SslBundles tlsBundlesWithGameSessionLeaf() throws Exception {
    String serviceUri = "spiffe://firemud/ns/firemud-prod/sa/game-session-service";
    X509Certificate certificate = mock(X509Certificate.class);
    PublicKey publicKey = mock(PublicKey.class);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(publicKey.getEncoded())
        .thenReturn("game-session tls leaf".getBytes(StandardCharsets.US_ASCII));
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, serviceUri)));
    KeyStore keyStore = mock(KeyStore.class);
    when(keyStore.aliases()).thenReturn(Collections.enumeration(List.of("game-session")));
    when(keyStore.isKeyEntry("game-session")).thenReturn(true);
    when(keyStore.getCertificate("game-session")).thenReturn(certificate);
    SslStoreBundle stores = mock(SslStoreBundle.class);
    when(stores.getKeyStore()).thenReturn(keyStore);
    SslBundle bundle = mock(SslBundle.class);
    when(bundle.getStores()).thenReturn(stores);
    SslBundles sslBundles = mock(SslBundles.class);
    when(sslBundles.getBundle("firemud-grpc")).thenReturn(bundle);
    return sslBundles;
  }

  @Configuration(proxyBeanMethods = false)
  static class TestReadinessDependencies {
    @Bean
    @Primary
    GameSessionJwtReadinessProbeOwnerClient testMetadataClient() {
      return mockMetadataClient();
    }
  }
}
