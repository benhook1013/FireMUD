package net.firedevops.firemud.worldmanagement.service.impl;

import java.util.List;
import net.firedevops.firemud.worldmanagement.dto.WorldUpgradeValidationResultDto;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorldUpgradeValidationServiceImpl implements WorldUpgradeValidationService {
  private static final List<String> STATE_CLASSES = List.of("S3");
  private static final List<String> CHECKED_FAMILIES =
      List.of("world_instance", "region_instance", "zone_instance", "room_instance", "world_event");

  private final WorldInstanceRepository worldInstanceRepository;

  public WorldUpgradeValidationServiceImpl(WorldInstanceRepository worldInstanceRepository) {
    this.worldInstanceRepository = worldInstanceRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public WorldUpgradeValidationResultDto validateWorldUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId) {
    worldInstanceRepository
        .findByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "NOT_FOUND: world instance not found for source game instance"));
    return new WorldUpgradeValidationResultDto(
        STATE_CLASSES,
        CHECKED_FAMILIES,
        false,
        "COMPATIBLE",
        false,
        List.of(),
        normalizeBlank(remapSetId));
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
