package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldInstanceRepository extends JpaRepository<WorldInstance, Long> {
  Optional<WorldInstance> findByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId);
}
