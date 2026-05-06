package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.worldmanagement.dto.WorldUpgradeValidationResultDto;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed repositories are stored internally for validation queries")
public class WorldUpgradeValidationServiceImpl implements WorldUpgradeValidationService {
  private static final List<String> STATE_CLASSES = List.of("S3");
  private static final List<String> CHECKED_FAMILIES =
      List.of(
          "world_instance",
          "region_instance",
          "zone_instance",
          "room_instance",
          "room_instance_exit",
          "world_event");

  private final WorldInstanceRepository worldInstanceRepository;
  private final RegionInstanceRepository regionInstanceRepository;
  private final ZoneInstanceRepository zoneInstanceRepository;
  private final RoomInstanceRepository roomInstanceRepository;
  private final RoomInstanceExitRepository roomInstanceExitRepository;
  private final WorldEventRepository worldEventRepository;

  public WorldUpgradeValidationServiceImpl(
      WorldInstanceRepository worldInstanceRepository,
      RegionInstanceRepository regionInstanceRepository,
      ZoneInstanceRepository zoneInstanceRepository,
      RoomInstanceRepository roomInstanceRepository,
      RoomInstanceExitRepository roomInstanceExitRepository,
      WorldEventRepository worldEventRepository) {
    this.worldInstanceRepository = worldInstanceRepository;
    this.regionInstanceRepository = regionInstanceRepository;
    this.zoneInstanceRepository = zoneInstanceRepository;
    this.roomInstanceRepository = roomInstanceRepository;
    this.roomInstanceExitRepository = roomInstanceExitRepository;
    this.worldEventRepository = worldEventRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public WorldUpgradeValidationResultDto validateWorldUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId) {
    var worldInstance =
        worldInstanceRepository
            .findByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "NOT_FOUND: world instance not found for source game instance"));
    long regionCount =
        regionInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId);
    long zoneCount =
        zoneInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId);
    long roomCount =
        roomInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId);
    roomInstanceExitRepository.countByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId);
    worldEventRepository.countByTenantIdAndGameInstanceId(tenantId, sourceGameInstanceId);
    List<String> reasons = new java.util.ArrayList<>();
    if (!isCutoverEligibleStatus(worldInstance.getStatus())) {
      reasons.add(
          "WORLD.world_instance status "
              + worldInstance.getStatus()
              + " is not eligible for replacement-instance cutover");
    }
    if (regionCount <= 0 || zoneCount <= 0 || roomCount <= 0) {
      reasons.add(
          "WORLD.instance_topology is incomplete for replacement-instance cutover "
              + "(regions="
              + regionCount
              + ", zones="
              + zoneCount
              + ", rooms="
              + roomCount
              + ")");
    }
    return new WorldUpgradeValidationResultDto(
        STATE_CLASSES,
        CHECKED_FAMILIES,
        false,
        reasons.isEmpty() ? "COMPATIBLE" : "INCOMPATIBLE",
        false,
        List.copyOf(reasons),
        normalizeBlank(remapSetId));
  }

  private boolean isCutoverEligibleStatus(String status) {
    return "ACTIVE".equals(status) || "TERMINATING".equals(status);
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
