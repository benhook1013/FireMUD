package net.firedevops.firemud.gamesession.config;

import java.io.IOException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedFixtureFirstOpenCoordinator;
import net.firedevops.firemud.gamesession.data.RunOwnedFixtureFirstOpenDriver;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.RunOwnedFixtureRuntimeCompletionRepository;
import net.firedevops.firemud.gamesession.service.impl.AuthenticatedCanonicalInitialAdmissionWorldVerifier;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Composes the canonical run-owned Compose fixture through its first OPEN. Requires the canonical
 * launch-association owner read (which supplies the launch and initial-admission repositories) and
 * registers the catalog and pointer owners those and the player route read need.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}"
        + " and ${firemud.canonical-game-instance-launch-association-owner-read.enabled:false}")
public class RunOwnedFixtureFirstOpenConfiguration {

  @Bean
  GameSessionCanonicalRealmCatalogRepository runOwnedFixtureCanonicalRealmCatalogRepository(
      DSLContext dsl,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionCanonicalRealmCatalogRepository(
        dsl, transactionManager, workloadNamespace);
  }

  @Bean
  GameSessionCanonicalAdmissionPointerRepository runOwnedFixtureCanonicalAdmissionPointerRepository(
      DSLContext dsl, GameSessionCanonicalRealmCatalogRepository catalogRepository) {
    return new GameSessionCanonicalAdmissionPointerRepository(
        dsl, catalogRepository, new GameplayAdmissionPointerEventRepository(dsl));
  }

  @Bean(destroyMethod = "close")
  WorldCanonicalInitialAdmissionHoldClient runOwnedFixtureWorldHoldClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace)
      throws IOException {
    var client =
        new WorldCanonicalInitialAdmissionHoldClient(
            endpoints, tlsProperties, channelFactory, workloadNamespace);
    client.init();
    return client;
  }

  @Bean
  RunOwnedFixtureFirstOpenCoordinator runOwnedFixtureFirstOpenCoordinator(
      DSLContext dsl,
      RunOwnedCanonicalLaunchPreparationOwner launchOwner,
      WorldManagementClient world,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      WorldCanonicalInitialAdmissionHoldClient holds,
      CanonicalInitialAdmissionRepository admissions,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    var intents = new CanonicalInitialAdmissionIntentRepository(dsl, catalogRepository, launches);
    return new RunOwnedFixtureFirstOpenCoordinator(
        launchOwner,
        world,
        launches,
        intents,
        holds,
        admissions,
        new RunOwnedFixtureRuntimeCompletionRepository(dsl, launches),
        new AuthenticatedCanonicalInitialAdmissionWorldVerifier(holds, intents, workloadNamespace),
        transactionManager,
        workloadNamespace);
  }

  @Bean
  RunOwnedFixtureFirstOpenDriver runOwnedFixtureFirstOpenDriver(
      RunOwnedFixtureFirstOpenCoordinator coordinator) {
    return new RunOwnedFixtureFirstOpenDriver(coordinator);
  }
}
