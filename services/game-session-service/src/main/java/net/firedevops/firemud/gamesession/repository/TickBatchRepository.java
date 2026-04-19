package net.firedevops.firemud.gamesession.repository;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TickBatchRepository extends JpaRepository<TickBatch, Long> {
  Optional<TickBatch> findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
      Long tenantId, Long gameInstanceId, String status);
}
