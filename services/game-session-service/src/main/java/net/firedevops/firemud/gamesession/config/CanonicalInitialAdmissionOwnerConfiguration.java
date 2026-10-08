package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionService;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import net.firedevops.firemud.gamesession.service.impl.DatabaseCanonicalInitialAdmissionService;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      GameplayAdmissionPointerEventRepository eventRepository) {
    return new CanonicalInitialAdmissionRepository(
        dsl, catalogRepository, pointerRepository, launchRepository, eventRepository);
  }

  @Bean
  CanonicalInitialAdmissionService canonicalInitialAdmissionService(
      CanonicalInitialAdmissionRepository repository,
      GameSessionCanonicalAdmissionPointerRepository pointerRepository,
      CanonicalInitialAdmissionWorldVerifier worldVerifier) {
    return new DatabaseCanonicalInitialAdmissionService(
        repository, pointerRepository, worldVerifier);
  }
}
