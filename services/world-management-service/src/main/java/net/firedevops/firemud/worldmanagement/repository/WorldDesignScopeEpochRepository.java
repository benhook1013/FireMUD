package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignScopeEpoch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldDesignScopeEpochRepository
    extends JpaRepository<WorldDesignScopeEpoch, Long> {
  Optional<WorldDesignScopeEpoch> findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
      Long tenantId, Long versionId, String scopeType, String scopeId);
}
