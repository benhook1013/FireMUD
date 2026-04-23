package net.firedevops.firemud.automationscripting.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptPatchPinProjectionRepository
    extends JpaRepository<ScriptPatchPinProjection, Long> {
  Optional<ScriptPatchPinProjection> findByTenantIdAndGameInstanceId(
      String tenantId, String gameInstanceId);

  List<ScriptPatchPinProjection> findByTenantIdAndObservedPinnedScriptPatchVersion(
      String tenantId, String observedPinnedScriptPatchVersion);
}
