package net.firedevops.firemud.loggingadmin.repository;

import java.util.Optional;
import net.firedevops.firemud.loggingadmin.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {
  Optional<FeatureFlag> findByTenantIdAndName(Long tenantId, String name);
}
