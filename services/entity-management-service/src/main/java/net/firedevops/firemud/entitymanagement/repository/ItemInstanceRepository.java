package net.firedevops.firemud.entitymanagement.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemInstanceRepository extends JpaRepository<ItemInstance, Long> {
  @EntityGraph(attributePaths = {"character", "item"})
  Page<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
          Long tenantId, Long characterId, Pageable pageable);

  @EntityGraph(attributePaths = {"character", "item"})
  Page<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNotNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByEquipmentSlotAscIdAsc(
          Long tenantId, Long characterId, Pageable pageable);

  @EntityGraph(attributePaths = {"character", "item"})
  Page<ItemInstance>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable);

  @EntityGraph(attributePaths = {"character", "item"})
  List<ItemInstance>
      findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullOrderByIdAsc(
          Long tenantId, Long characterId, Long itemId);

  @EntityGraph(attributePaths = {"character", "item"})
  List<ItemInstance>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ItemInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, String equipmentSlot);

  @EntityGraph(attributePaths = {"character", "item"})
  boolean
      existsByTenantIdAndCharacter_IdAndEquipmentSlotAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, String equipmentSlot);

  @EntityGraph(attributePaths = {"character", "item"})
  List<ItemInstance> findByTenantIdAndCharacter_IdAndItem_IdOrderByIdAsc(
      Long tenantId, Long characterId, Long itemId);

  @EntityGraph(attributePaths = {"character", "item", "containerInstance"})
  Page<ItemInstance> findByTenantIdAndContainerInstance_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Pageable pageable);

  @EntityGraph(attributePaths = {"character", "item", "containerInstance"})
  List<ItemInstance> findByTenantIdAndContainerInstance_IdAndItem_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Long itemId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ItemInstance> findByIdAndTenantId(Long id, Long tenantId);
}
