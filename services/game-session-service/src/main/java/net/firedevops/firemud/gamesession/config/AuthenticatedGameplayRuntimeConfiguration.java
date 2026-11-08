package net.firedevops.firemud.gamesession.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.repository.DirectTextAuthenticatedLobbyRepository;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCleanupOwner;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextRedisClient;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCoordinationQualification;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisAttemptClosure;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisBinding;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Default-off receiving composition; every protected authority must be independently supplied. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.game-session.authenticated-gameplay",
    name = "enabled",
    havingValue = "true")
public class AuthenticatedGameplayRuntimeConfiguration {
  @Bean(initMethod = "initialize", destroyMethod = "close")
  CanonicalGameplayLoginAccountClient canonicalGameplayLoginAccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      BlockingGrpcStubCustomizer customizer,
      AccountAsymmetricJwtVerifier verifier,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    return new CanonicalGameplayLoginAccountClient(
        endpoints,
        tls,
        channels,
        customizer,
        namespace,
        Clock.systemUTC(),
        Duration.ofSeconds(3),
        verifier);
  }

  @Bean
  InitialGameplayLoginOwner initialGameplayLoginOwner(
      InitialGameplayLoginRedisBinding binding,
      InitialGameplayLoginCoordinationQualification qualification,
      CanonicalGameplayLoginAccountClient account,
      DSLContext dsl,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    var contexts =
        new InitialGameplayLoginContextRedisClient(
            binding,
            RedisScriptCatalog.fromContributions(
                List.of(new InitialGameplayLoginRedisScriptContribution())),
            qualification);
    var cleanup =
        new InitialGameplayLoginCleanupOwner(
            new InitialGameplayLoginCoordinationJournalRepository(dsl),
            new InitialGameplayLoginRedisAttemptClosure(contexts),
            account,
            transactions);
    return new InitialGameplayLoginOwner(contexts, cleanup, account);
  }

  @Bean
  DirectTextWorldSelectionService authenticatedDirectTextWorldSelections(
      GameplayWorldCatalog catalog, CanonicalPlayerRouteReadService routes) {
    return new DirectTextWorldSelectionService(catalog, routes, Clock.systemUTC());
  }

  @Bean
  DirectTextAuthenticatedLobbyOwner directTextAuthenticatedLobbyOwner(
      DSLContext dsl,
      DirectTextWorldSelectionService selections,
      AccountClient account,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    return new DirectTextAuthenticatedLobbyOwner(
        new DirectTextAuthenticatedLobbyRepository(dsl),
        selections,
        account,
        transactions,
        Clock.systemUTC());
  }
}
