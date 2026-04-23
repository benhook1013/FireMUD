package net.firedevops.firemud.automationscripting.repository;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutomationAdmissionStateRepository
    extends JpaRepository<AutomationAdmissionState, Long> {
  Optional<AutomationAdmissionState> findByTenantIdAndGameInstanceIdAndRegionId(
      String tenantId, String gameInstanceId, String regionId);
}
