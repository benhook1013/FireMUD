package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import net.firedevops.firemud.gamesession.service.impl.DatabaseCanonicalInitialAdmissionService;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit opt-in composition for the canonical initial-admission owner. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "firemud.canonical-game-instance-launch-association-owner-read",
    name = "enabled",
    havingValue = "true")
public class CanonicalInitialAdmissionOwnerConfiguration {
  @Bean
  CanonicalInitialAdmissionRepository canonicalInitialAdmissionRepository(
      DSLContext dsl,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository) {
    return new CanonicalInitialAdmissionRepository(dsl, catalogRepository, pointerRepository);
  }

  @Bean
  CanonicalInitialAdmissionService canonicalInitialAdmissionService(
      CanonicalInitialAdmissionRepository repository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalInitialAdmissionWorldVerifier worldVerifier,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
    return new DatabaseCanonicalInitialAdmissionService(
        repository, pointerRepository, worldVerifier, transactionManager);
  }
}

/** The existing isolated-fixture flag supplies its canonical STARTING launch owner. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
class RunOwnedFixtureCanonicalLaunchOwnerConfiguration {
  @Bean
  GameSessionCanonicalInstanceLaunchRepository runOwnedFixtureCanonicalLaunchOwner(
      DSLContext dsl,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    return new GameSessionCanonicalInstanceLaunchRepository(
        dsl,
        transactionManager,
        workloadNamespace,
        new GameSessionCanonicalRealmCatalogRepository(dsl, transactionManager, workloadNamespace));
  }
}
