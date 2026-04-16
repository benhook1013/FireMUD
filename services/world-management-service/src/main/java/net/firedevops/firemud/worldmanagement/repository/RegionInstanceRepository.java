package net.firedevops.firemud.worldmanagement.repository;

import java.util.List;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegionInstanceRepository extends JpaRepository<RegionInstance, Long> {
  List<RegionInstance> findByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);

  void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);
}
