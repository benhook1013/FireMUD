package net.firedevops.firemud.worldmanagement.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomInstanceRepository extends JpaRepository<RoomInstance, Long> {
  Optional<RoomInstance> findByTenantIdAndGameInstanceIdAndRoomInstanceId(
      Long tenantId, Long gameInstanceId, Long roomInstanceId);

  List<RoomInstance> findByTenantIdAndGameInstanceIdOrderByRoomInstanceIdAsc(
      Long tenantId, Long gameInstanceId);

  void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);
}
