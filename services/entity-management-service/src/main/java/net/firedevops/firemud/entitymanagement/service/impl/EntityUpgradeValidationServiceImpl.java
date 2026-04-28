package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.EntityUpgradeValidationResultDto;
import net.firedevops.firemud.entitymanagement.repository.CharacterEquipmentRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterFriendRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are managed dependencies kept internal.")
public class EntityUpgradeValidationServiceImpl implements EntityUpgradeValidationService {
  private static final List<String> STATE_CLASSES = List.of("S1", "S2", "S3");
  private static final List<String> CHECKED_FAMILIES =
      List.of(
          "characters",
          "inventory",
          "character_equipment",
          "character_friend",
          "room_ground_inventory",
          "item_instances",
          "item_stacks",
          "container_instances");

  private final CharacterRepository characterRepository;
  private final InventoryEntryRepository inventoryEntryRepository;
  private final CharacterEquipmentRepository characterEquipmentRepository;
  private final CharacterFriendRepository characterFriendRepository;
  private final ItemInstanceRepository itemInstanceRepository;
  private final ItemStackRepository itemStackRepository;
  private final ContainerInstanceRepository containerInstanceRepository;
  private final RoomGroundInventoryRepository roomGroundInventoryRepository;

  public EntityUpgradeValidationServiceImpl(
      CharacterRepository characterRepository,
      InventoryEntryRepository inventoryEntryRepository,
      CharacterEquipmentRepository characterEquipmentRepository,
      CharacterFriendRepository characterFriendRepository,
      ItemInstanceRepository itemInstanceRepository,
      ItemStackRepository itemStackRepository,
      ContainerInstanceRepository containerInstanceRepository,
      RoomGroundInventoryRepository roomGroundInventoryRepository) {
    this.characterRepository = characterRepository;
    this.inventoryEntryRepository = inventoryEntryRepository;
    this.characterEquipmentRepository = characterEquipmentRepository;
    this.characterFriendRepository = characterFriendRepository;
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
    characterRepository.countByTenantId(tenantId);
    long inventoryRows = inventoryEntryRepository.countByCharacterTenantId(tenantId);
    long equipmentRows = characterEquipmentRepository.countByCharacterTenantId(tenantId);
    characterFriendRepository.countByTenantId(tenantId);
    itemInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    itemStackRepository.countByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    containerInstanceRepository.countByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    roomGroundInventoryRepository.countByIdTenantIdAndIdGameInstanceId(tenantId, gameInstanceId);
    String normalizedRemapSetId = normalizeBlank(remapSetId);
    boolean hasTemplateBoundRows = inventoryRows > 0 || equipmentRows > 0;
    if (hasTemplateBoundRows && normalizedRemapSetId == null) {
      return new EntityUpgradeValidationResultDto(
          STATE_CLASSES,
          CHECKED_FAMILIES,
          true,
          "INCOMPATIBLE",
          true,
          List.of(
              "ENTITY_REMAP_REQUIRED: inventory and equipment rows reference durable entity templates and require an approved remapSetId before replacement-instance cutover"),
          null);
    }
    return new EntityUpgradeValidationResultDto(
        STATE_CLASSES,
        CHECKED_FAMILIES,
        hasTemplateBoundRows,
        "COMPATIBLE",
        false,
        List.of(),
        normalizedRemapSetId);
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
