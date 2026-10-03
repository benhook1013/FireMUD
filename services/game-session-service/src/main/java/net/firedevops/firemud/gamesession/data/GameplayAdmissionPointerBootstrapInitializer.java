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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Validates configured admission-pointer seeds while keeping an empty authority store closed until
 * owner-validated World lifecycle proof is available.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "firemud.database",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GameplayAdmissionPointerBootstrapInitializer implements ApplicationRunner {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(GameplayAdmissionPointerBootstrapInitializer.class);

  private final GameplayAdmissionPointerRepository pointerRepository;
  private final GameplayAdmissionPointerBootstrapProperties bootstrapProperties;

  @Override
  public void run(ApplicationArguments args) {
    if (pointerRepository.count() > 0) {
      return;
    }
    List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers =
        bootstrapProperties.getPointers();
    if (pointers != null && pointers.isEmpty()) {
      LOGGER.warn(
          "Skipping gameplay admission pointer bootstrap because no pointer seeds are configured; "
              + "admission remains closed");
      return;
    }
    validateSeeds(pointers);
    LOGGER.warn(
        "Skipping configured gameplay admission pointer bootstrap because owner-validated "
            + "World ACTIVE lifecycle and epoch proof is unavailable; admission remains closed");
  }

  private static void validateSeeds(
      List<GameplayAdmissionPointerBootstrapProperties.PointerSeed> pointers) {
    if (pointers == null) {
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
}
