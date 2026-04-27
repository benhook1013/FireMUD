package net.firedevops.firemud.automationscripting.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptPatchInstanceRolloutProjectionRepository
    extends JpaRepository<ScriptPatchInstanceRolloutProjection, Long> {
  Optional<ScriptPatchInstanceRolloutProjection>
      findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
          String tenantId, String gameInstanceId, String scriptPatchVersion);

  List<ScriptPatchInstanceRolloutProjection>
      findByTenantIdOrderByLastChangedAtDescGameInstanceIdAscScriptPatchVersionAsc(String tenantId);

  List<ScriptPatchInstanceRolloutProjection>
      findByTenantIdAndGameInstanceIdOrderByLastChangedAtDescScriptPatchVersionAsc(
          String tenantId, String gameInstanceId);

  List<ScriptPatchInstanceRolloutProjection> findByTenantIdAndGameInstanceId(
      String tenantId, String gameInstanceId);
}
