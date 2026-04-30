package net.firedevops.firemud.gamesession.repository;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeRegionStatusRepository extends JpaRepository<RuntimeRegionStatus, Long> {
  Optional<RuntimeRegionStatus> findByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);

  Optional<RuntimeRegionStatus> findByTenantIdAndRegionId(Long tenantId, String regionId);
}
