package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.entitymanagement.dto.RuntimeInstanceCleanupResultDto;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are managed dependencies kept internal.")
public class RuntimeInstanceCleanupServiceImpl implements RuntimeInstanceCleanupService {
  private final RoomGroundInventoryRepository roomGroundInventoryRepository;
  private final ItemStackRepository itemStackRepository;
  private final ItemInstanceRepository itemInstanceRepository;
  private final ContainerInstanceRepository containerInstanceRepository;

  public RuntimeInstanceCleanupServiceImpl(
      RoomGroundInventoryRepository roomGroundInventoryRepository,
      ItemStackRepository itemStackRepository,
      ItemInstanceRepository itemInstanceRepository,
      ContainerInstanceRepository containerInstanceRepository) {
    this.roomGroundInventoryRepository = roomGroundInventoryRepository;
    this.itemStackRepository = itemStackRepository;
    this.itemInstanceRepository = itemInstanceRepository;
    this.containerInstanceRepository = containerInstanceRepository;
  }

  @Override
  @Transactional
  public RuntimeInstanceCleanupResultDto cleanupRuntimeInstance(
      Long tenantId, String gameInstanceId, String terminationRequestId) {
    if (tenantId == null || tenantId <= 0L) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: tenantId is required");
    }
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: gameInstanceId is required");
    }
    if (terminationRequestId == null || terminationRequestId.isBlank()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: terminationRequestId is required");
    }
    long deletedRoomGroundEntries =
        roomGroundInventoryRepository.deleteByIdTenantIdAndIdGameInstanceId(
            tenantId, gameInstanceId);
    long deletedItemStacks =
        itemStackRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    long deletedItemInstances =
        itemInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    long deletedContainerInstances =
        containerInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    return new RuntimeInstanceCleanupResultDto(
        deletedRoomGroundEntries,
        deletedItemStacks,
        deletedItemInstances,
        deletedContainerInstances);
  }
}
