package net.firedevops.firemud.automationscripting.repository;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptEventAuditRepository extends JpaRepository<ScriptEventAudit, Long> {
  boolean
      existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String scriptId,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          String scriptEventId,
          boolean dryRun);

  Optional<ScriptEventAudit> findByWorkItemId(Long workItemId);
}
