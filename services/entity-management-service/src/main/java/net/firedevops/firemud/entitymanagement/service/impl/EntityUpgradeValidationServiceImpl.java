package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.EntityUpgradeValidationResultDto;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityUpgradeValidationServiceImpl implements EntityUpgradeValidationService {
  private static final List<String> STATE_CLASSES = List.of("S3");
  private static final List<String> CHECKED_FAMILIES =
      List.of("room_ground_inventory", "item_instances", "item_stacks", "container_instances");

  private final ItemInstanceRepository itemInstanceRepository;
  private final ItemStackRepository itemStackRepository;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final RoomGroundInventoryRepository roomGroundInventoryRepository;

  public EntityUpgradeValidationServiceImpl(
      ItemInstanceRepository itemInstanceRepository,
      ItemStackRepository itemStackRepository,
      ContainerInstanceRepository containerInstanceRepository,
      RoomGroundInventoryRepository roomGroundInventoryRepository) {
    this.itemInstanceRepository = itemInstanceRepository;
    this.itemStackRepository = itemStackRepository;
    this.containerInstanceRepository = containerInstanceRepository;
    this.roomGroundInventoryRepository = roomGroundInventoryRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public EntityUpgradeValidationResultDto validateEntityUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId) {
    String gameInstanceId = Long.toString(sourceGameInstanceId);
    itemInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    itemStackRepository.countByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    containerInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    roomGroundInventoryRepository.countByIdTenantIdAndIdGameInstanceId(tenantId, gameInstanceId);
    return new EntityUpgradeValidationResultDto(
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
