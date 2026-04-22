package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
  @Query(
      """
      select ci
      from ContainerInstance ci
      where ci.id = :id
        and ci.tenantId = :tenantId
        and (
          ci.character.id = :characterId
          or (
            :gameInstanceId is not null
            and :roomInstanceId is not null
            and ci.character is null
            and ci.gameInstanceId = :gameInstanceId
            and ci.roomInstanceId = :roomInstanceId
          )
        )
      """)
  Optional<ContainerInstance> findAccessibleByIdAndTenantIdAndCharacterIdOrRoom(
      @Param("id") Long id,
      @Param("tenantId") Long tenantId,
      @Param("characterId") Long characterId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("roomInstanceId") String roomInstanceId);

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

  @EntityGraph(attributePaths = {"character", "item", "itemInstance"})
  Optional<ContainerInstance> findByItemInstance_Id(Long itemInstanceId);

  @Transactional
  long deleteByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId);

  long countByTenantIdAndGameInstanceId(Long tenantId, String gameInstanceId);
}
