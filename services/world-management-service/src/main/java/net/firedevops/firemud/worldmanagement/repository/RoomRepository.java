package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
  java.util.List<Room> findByTenantIdOrderByIdAsc(Long tenantId);

  java.util.List<Room> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId);

  Optional<Room> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id);
}
