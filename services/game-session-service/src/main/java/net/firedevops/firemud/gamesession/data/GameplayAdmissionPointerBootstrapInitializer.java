package net.firedevops.firemud.gamesession.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps the persisted gameplay admission-pointer authority from configuration only when the
 * authority store is empty.
 */
@Component
@RequiredArgsConstructor
public class GameplayAdmissionPointerBootstrapInitializer implements ApplicationRunner {
  private final GameplayAdmissionPointerRepository pointerRepository;
  private final GameplayAdmissionPointerAuthorityService authorityService;
  private final GameplayCatalogProperties gameplayCatalogProperties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (pointerRepository.count() > 0) {
      return;
    }
    if (gameplayCatalogProperties.getWorlds() == null) {
      return;
    }
    for (GameplayCatalogProperties.World world : gameplayCatalogProperties.getWorlds()) {
      if (world == null || world.getSlug() == null || world.getSlug().isBlank()) {
        continue;
      }
      if (world.getRealms() == null) {
        continue;
      }
      for (GameplayCatalogProperties.Realm realm : world.getRealms()) {
        if (realm == null || realm.getSlug() == null || realm.getSlug().isBlank()) {
          continue;
        }
        authorityService.upsertPointer(
            new GameplayAdmissionPointerMutation(
                world.getSlug(),
                world.getDisplayName(),
                realm.getSlug(),
                realm.getDisplayName(),
                realm.getTenantId(),
                realm.getGameInstanceId(),
                realm.isVisible(),
                realm.isRequiresCharacterSelection(),
                realm.getStateScope().name(),
                realm.getCharacterCreationPolicy().name(),
                "system/bootstrap",
                "Initial gameplay catalog bootstrap",
                "bootstrap:" + world.getSlug() + ":" + realm.getSlug(),
                null));
      }
    }
  }
}
