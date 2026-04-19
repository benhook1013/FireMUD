package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RoomGroundInventoryRepository
    extends JpaRepository<RoomGroundInventoryEntry, RoomGroundInventoryKey> {

  @EntityGraph(attributePaths = {"item"})
  Page<RoomGroundInventoryEntry> findByIdTenantIdAndIdGameInstanceIdAndIdRoomInstanceId(
      Long tenantId, String gameInstanceId, String roomInstanceId, Pageable pageable);

  @Transactional
  long deleteByIdTenantIdAndIdGameInstanceId(Long tenantId, String gameInstanceId);
}
