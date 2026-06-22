package net.firedevops.firemud.gamesession.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamesession.config.GameplayAdmissionPointerBootstrapProperties;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps the persisted gameplay admission-pointer authority from configuration only when the
 * authority store is empty.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "firemud.database",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GameplayAdmissionPointerBootstrapInitializer implements ApplicationRunner {
  private final GameplayAdmissionPointerRepository pointerRepository;
  private final GameplayAdmissionPointerAuthorityService authorityService;
  private final GameplayAdmissionPointerBootstrapProperties bootstrapProperties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (pointerRepository.count() > 0) {
      return;
    }
    if (bootstrapProperties.getPointers() == null) {
      return;
    }
    for (GameplayAdmissionPointerBootstrapProperties.PointerSeed pointer :
        bootstrapProperties.getPointers()) {
      if (pointer == null
          || pointer.getWorldSlug() == null
          || pointer.getWorldSlug().isBlank()
          || pointer.getRealmSlug() == null
          || pointer.getRealmSlug().isBlank()) {
        continue;
      }
      authorityService.upsertPointer(
          new GameplayAdmissionPointerMutation(
              pointer.getWorldSlug(),
              pointer.getWorldDisplayName(),
              pointer.getRealmSlug(),
              pointer.getRealmDisplayName(),
              pointer.getTenantId(),
              pointer.getGameInstanceId(),
              pointer.isVisible(),
              pointer.isPublicProductionRealm(),
              pointer.isRequiresCharacterSelection(),
              pointer.getStateScope().name(),
              pointer.getCharacterCreationPolicy().name(),
              "system/bootstrap",
              "Initial gameplay pointer bootstrap",
              "bootstrap:" + pointer.getWorldSlug() + ":" + pointer.getRealmSlug(),
              null,
              null));
    }
  }
}
