package net.firedevops.firemud.entitymanagement.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemStackRepository extends JpaRepository<ItemStack, Long> {
  @EntityGraph(attributePaths = {"character", "item"})
  Page<ItemStack>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullOrderByIdAsc(
          Long tenantId, Long characterId, Pageable pageable);

  @EntityGraph(attributePaths = {"item"})
  Page<ItemStack>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
          Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable);

  @EntityGraph(attributePaths = {"item", "containerInstance"})
  Page<ItemStack> findByTenantIdAndContainerInstance_IdOrderByIdAsc(
      Long tenantId, Long containerInstanceId, Pageable pageable);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ItemStack>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
          Long tenantId, Long characterId, Long itemId, String compatibilityFingerprint);

  @EntityGraph(attributePaths = {"item"})
  Optional<ItemStack>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullAndItem_IdAndCompatibilityFingerprint(
          Long tenantId,
          String gameInstanceId,
          String roomInstanceId,
          Long itemId,
          String compatibilityFingerprint);

  @EntityGraph(attributePaths = {"item", "containerInstance"})
  Optional<ItemStack> findByTenantIdAndContainerInstance_IdAndItem_IdAndCompatibilityFingerprint(
      Long tenantId, Long containerInstanceId, Long itemId, String compatibilityFingerprint);

  @EntityGraph(attributePaths = {"character", "item"})
  List<ItemStack>
      findByTenantIdAndCharacter_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNullAndContainerInstanceIsNullAndItem_IdOrderByIdAsc(
          Long tenantId, Long characterId, Long itemId);
}
