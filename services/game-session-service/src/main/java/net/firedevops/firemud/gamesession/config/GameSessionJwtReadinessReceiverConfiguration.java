package net.firedevops.firemud.gamesession.config;

import java.time.Clock;
import java.time.Duration;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeCrypto;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeOwnerClient;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProbeOwnerReadPort;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProtectedJwksSource;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessProtectedLocalIdentityProvider;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverEngine;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverProtoMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Explicit default-off composition; every identity read requires current protected Pod and TLS
 * evidence plus authenticated Account metadata.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.game-session.jwt-readiness.receiver",
    name = "enabled",
    havingValue = "true")
@ConditionalOnBean(SslBundles.class)
@Lazy
public class GameSessionJwtReadinessReceiverConfiguration {
  @Bean
  @Lazy
  public GameSessionJwtReadinessLocalIdentityProvider gameSessionJwtReadinessLocalIdentityProvider(
      GameSessionJwtReadinessProbeOwnerClient metadataReadClient,
      SslBundles sslBundles,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionJwtReadinessProtectedLocalIdentityProvider(
        metadataReadClient, sslBundles, workloadNamespace);
  }

  @Bean
  @Lazy
  public GameSessionJwtReadinessProtectedJwksSource gameSessionJwtReadinessPublicJwksSource(
      GameSessionJwtReadinessLocalIdentityProvider localIdentityProvider) {
    return new GameSessionJwtReadinessProtectedJwksSource(localIdentityProvider);
  }

  @Bean
  @Lazy
  public AccountPublicJwksCache gameSessionJwtReadinessPublicJwksCache(
      GameSessionJwtReadinessProtectedJwksSource trustedJwksSource,
      GameSessionJwtReadinessLocalIdentityProvider localIdentityProvider) {
    return new AccountPublicJwksCache(
        trustedJwksSource,
        localIdentityProvider.observe().jwksSourceIdentity(),
        Clock.systemUTC(),
        Duration.ofSeconds(300));
  }

  @Bean
  @Lazy
  public AccountAsymmetricJwtVerifier gameSessionJwtReadinessProductionVerifier(
      @Qualifier("gameSessionJwtReadinessPublicJwksCache")
          AccountPublicJwksCache gameSessionJwtReadinessPublicJwksCache) {
    return new AccountAsymmetricJwtVerifier(
        gameSessionJwtReadinessPublicJwksCache, Clock.systemUTC());
  }

  @Bean
  @Lazy
  public GameSessionJwtReadinessProbeCrypto gameSessionJwtReadinessProbeCrypto(
      @Qualifier("gameSessionJwtReadinessProductionVerifier")
          AccountAsymmetricJwtVerifier gameSessionJwtReadinessProductionVerifier,
      @Value("${firemud.game-session.jwt-readiness.receiver.max-control-ui-tenant-scopes}")
          int maxControlUiTenantScopes) {
    return new GameSessionJwtReadinessProbeCrypto(
        gameSessionJwtReadinessProductionVerifier, maxControlUiTenantScopes);
  }

  @Bean
  @Lazy
  public GameSessionJwtReadinessProbeOwnerClient gameSessionJwtReadinessProbeOwnerReadPort(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionJwtReadinessProbeOwnerClient(
        endpoints, tlsProperties, channelFactory, workloadNamespace);
  }

  @Bean
  @Lazy
  public GameSessionJwtReadinessProbeOwnerWorkloadGuard
      gameSessionJwtReadinessProbeOwnerWorkloadGuard(
          @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionJwtReadinessProbeOwnerWorkloadGuard(workloadNamespace);
  }

  @Bean
  @Lazy
  public GameSessionJwtReadinessReceiverProtoMapper gameSessionJwtReadinessReceiverProtoMapper() {
    return new GameSessionJwtReadinessReceiverProtoMapper();
  }

  @Bean
  @Lazy
  public GameSessionJwtReadinessReceiverEngine gameSessionJwtReadinessReceiverEngine(
      GameSessionJwtReadinessProbeOwnerWorkloadGuard workloadGuard,
      GameSessionJwtReadinessLocalIdentityProvider localIdentityProvider,
      GameSessionJwtReadinessProbeOwnerReadPort ownerReadPort,
      GameSessionJwtReadinessReceiverProtoMapper protoMapper,
      @Qualifier("gameSessionJwtReadinessProbeCrypto") GameSessionJwtReadinessProbeCrypto crypto) {
    return new GameSessionJwtReadinessReceiverEngine(
        workloadGuard,
        localIdentityProvider,
        ownerReadPort,
        protoMapper,
        crypto,
        Clock.systemUTC());
  }
}
