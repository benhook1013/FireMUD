package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContainerInstanceRepository extends JpaRepository<ContainerInstance, Long> {
  @EntityGraph(attributePaths = {"character", "item"})
  @Query(
      """
      select ci
      from ContainerInstance ci
      where ci.id = :id
        and ci.tenantId = :tenantId
        and ci.character.id = :characterId
      """)
  Optional<ContainerInstance> findAccessibleByIdAndTenantIdAndCharacterId(
      @Param("id") Long id,
      @Param("tenantId") Long tenantId,
      @Param("characterId") Long characterId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ContainerInstance>
      findByTenantIdAndCharacter_IdAndItem_IdAndEquipmentSlotIsNullAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, Long itemId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ContainerInstance>
      findByTenantIdAndCharacter_IdAndEquipmentSlotAndItem_IdAndGameInstanceIdIsNullAndRoomInstanceIdIsNull(
          Long tenantId, Long characterId, String equipmentSlot, Long itemId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ContainerInstance>
      findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndItem_IdAndCharacterIsNullAndEquipmentSlotIsNull(
          Long tenantId, String gameInstanceId, String roomInstanceId, Long itemId);

  @EntityGraph(attributePaths = {"character", "item"})
  Optional<ContainerInstance> findByTenantIdAndItem_Id(Long tenantId, Long itemId);
}
