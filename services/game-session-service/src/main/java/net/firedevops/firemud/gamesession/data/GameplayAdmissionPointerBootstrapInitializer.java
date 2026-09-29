package net.firedevops.firemud.gamesession.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * authority store is empty and full pointer audit identity is available.
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
    pointerRepository.lockForBootstrap();
    if (pointerRepository.count() > 0) {
      return;
    }
    List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers =
        bootstrapProperties.getPointers();
    validateSeeds(pointers);
    for (GameplayAdmissionPointerBootstrapProperties.PointerSeed pointer : pointers) {
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
              stateScopeName(pointer),
              characterCreationPolicyName(pointer),
              "system/bootstrap",
              "Initial gameplay pointer bootstrap",
              "bootstrap:"
                  + pointer.getTenantId()
                  + ":"
                  + pointer.getGameInstanceId()
                  + ":"
                  + pointer.getWorldSlug()
                  + ":"
                  + pointer.getRealmSlug(),
              0L,
              0L,
              null));
    }
  }

  private static void validateSeeds(
      List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers) {
    if (pointers == null || pointers.isEmpty()) {
      throw new IllegalArgumentException("Gameplay admission pointer bootstrap seeds are required");
    }

    Set<String> worldRealmKeys = new HashSet<>();
    Set<String> runtimeTargetKeys = new HashSet<>();
    Map<Long, Long> publicRealmCounts = new HashMap<>();
    Set<Long> tenantIds = new HashSet<>();
    for (int index = 0; index < pointers.size(); index++) {
      GameplayAdmissionPointerBootstrapProperties.PointerSeed pointer = pointers.get(index);
      if (pointer == null) {
        throw invalidSeed(index, "must not be null");
      }
      requireText(pointer.getWorldSlug(), index, "world slug");
      requireText(pointer.getWorldDisplayName(), index, "world display name");
      requireText(pointer.getRealmSlug(), index, "realm slug");
      requireText(pointer.getRealmDisplayName(), index, "realm display name");
      if (pointer.getTenantId() <= 0) {
        throw invalidSeed(index, "tenant ID must be positive");
      }
      if (pointer.getGameInstanceId() <= 0) {
        throw invalidSeed(index, "game instance ID must be positive");
      }

      tenantIds.add(pointer.getTenantId());
      String worldRealmKey =
          pointer.getTenantId()
              + ":"
              + pointer.getWorldSlug().trim().toLowerCase(Locale.ROOT)
              + ":"
              + pointer.getRealmSlug().trim().toLowerCase(Locale.ROOT);
      if (!worldRealmKeys.add(worldRealmKey)) {
        throw invalidSeed(index, "duplicates a tenant world and realm selector");
      }
      String runtimeTargetKey = pointer.getTenantId() + ":" + pointer.getGameInstanceId();
      if (!runtimeTargetKeys.add(runtimeTargetKey)) {
        throw invalidSeed(index, "duplicates a tenant runtime target");
      }
      if (pointer.isVisible() && pointer.isPublicProductionRealm()) {
        publicRealmCounts.merge(pointer.getTenantId(), 1L, Long::sum);
      }
    }

    for (long tenantId : tenantIds) {
      if (publicRealmCounts.getOrDefault(tenantId, 0L) != 1L) {
        throw new IllegalArgumentException(
            "Gameplay admission pointer bootstrap must define exactly one visible public "
                + "production realm for tenant "
                + tenantId);
      }
    }
  }

  private static void requireText(String value, int index, String field) {
    if (value == null || value.isBlank()) {
      throw invalidSeed(index, field + " is required");
    }
  }

  private static IllegalArgumentException invalidSeed(int index, String reason) {
    return new IllegalArgumentException(
        "Invalid gameplay admission pointer bootstrap seed at index " + index + ": " + reason);
  }

  private static String stateScopeName(
      GameplayAdmissionPointerBootstrapProperties.PointerSeed pointer) {
    GameplayAdmissionPointerBootstrapProperties.StateScope stateScope = pointer.getStateScope();
    return (stateScope != null
            ? stateScope
            : GameplayAdmissionPointerBootstrapProperties.StateScope.SHARED)
        .name();
  }

  private static String characterCreationPolicyName(
      GameplayAdmissionPointerBootstrapProperties.PointerSeed pointer) {
    GameplayAdmissionPointerBootstrapProperties.CharacterCreationPolicy policy =
        pointer.getCharacterCreationPolicy();
    return (policy != null
            ? policy
            : GameplayAdmissionPointerBootstrapProperties.CharacterCreationPolicy.ALLOW_NEW)
        .name();
  }
}
