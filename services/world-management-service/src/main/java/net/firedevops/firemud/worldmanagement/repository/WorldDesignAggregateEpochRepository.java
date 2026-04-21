package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignAggregateEpoch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldDesignAggregateEpochRepository
    extends JpaRepository<WorldDesignAggregateEpoch, Long> {
  Optional<WorldDesignAggregateEpoch> findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
      Long tenantId, Long versionId, String aggregateType, Long aggregateId);
}
