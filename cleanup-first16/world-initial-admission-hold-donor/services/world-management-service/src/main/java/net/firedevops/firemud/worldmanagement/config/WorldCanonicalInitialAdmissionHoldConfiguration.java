package net.firedevops.firemud.worldmanagement.config;

import net.firedevops.firemud.worldmanagement.client.WorldCanonicalInitialAdmissionGameSessionProofClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.WorldCanonicalInitialAdmissionHoldOwner;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the canonical fixture hold owner only under the already-existing default-off fixture
 * capability. This adds no second runtime toggle.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED", havingValue = "true")
public class WorldCanonicalInitialAdmissionHoldConfiguration {
  @Bean
  WorldCanonicalInstanceAssociationRepository worldCanonicalInstanceAssociationRepository(
      DSLContext dsl, WorldAuthoredSourceIntakeRepository sourceIntakes) {
    return new WorldCanonicalInstanceAssociationRepository(
        dsl,
        new WorldCompleteLaunchBindingRepository(dsl),
        sourceIntakes,
        new WorldAuthoredVersionIdentityRepository(dsl));
  }

  @Bean
  WorldCanonicalInitialAdmissionHoldOwner worldCanonicalInitialAdmissionHoldOwner(
      RunOwnedWorldLaunchEvidenceProvider evidenceProvider,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalInitialAdmissionHoldRepository holdRepository,
      WorldCanonicalInitialAdmissionGameSessionProofClient proofClient,
      WorldInstanceRepository worldInstanceRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
    return new WorldCanonicalInitialAdmissionHoldOwner(
        evidenceProvider,
        associationRepository,
        holdRepository,
        proofClient,
        worldInstanceRepository,
        dsl,
        transactionManager);
  }
}
