package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneInstanceRepository extends JpaRepository<ZoneInstance, Long> {
  Optional<ZoneInstance> findByTenantIdAndGameInstanceIdAndZoneInstanceId(
      Long tenantId, Long gameInstanceId, Long zoneInstanceId);

  long countByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);

  void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);
}
