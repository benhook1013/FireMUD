package net.firedevops.firemud.gamesession.config;

import java.time.Duration;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.StartSessionOperatorRedemptionClient;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.service.GameSessionStartSessionOperatorAuthorizationCoordinator;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit, disabled-by-default composition for StartSession authorization preparation only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.start-session-operator-authorization",
    name = "enabled",
    havingValue = "true")
public class GameSessionStartSessionOperatorAuthorizationConfiguration {
  @Bean
  GameSessionStartSessionOperatorAttemptRepository gameSessionStartSessionOperatorAttemptRepository(
      DSLContext dsl,
      @Value("${firemud.start-session-operator-authorization.owner-claim-lease}")
          Duration ownerClaimLease) {
    return new GameSessionStartSessionOperatorAttemptRepository(dsl, ownerClaimLease);
  }

  @Bean(initMethod = "initialize", destroyMethod = "close")
  StartSessionOperatorRedemptionClient startSessionOperatorRedemptionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    return new StartSessionOperatorRedemptionClient(
        endpoints, tlsProperties, channelFactory, stubCustomizer);
  }

  @Bean
  GameSessionStartSessionOperatorAuthorizationCoordinator
      gameSessionStartSessionOperatorAuthorizationCoordinator(
          GameSessionStartSessionOperatorAttemptRepository attemptRepository,
          StartSessionOperatorRedemptionClient redemptionClient,
          PlatformTransactionManager transactionManager,
          @Value("${firemud.grpc.workload-namespace}") String workloadNamespace) {
    return new GameSessionStartSessionOperatorAuthorizationCoordinator(
        attemptRepository, redemptionClient, transactionManager, workloadNamespace);
  }
}
