package net.firedevops.firemud.automationscripting.repository;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScriptEventAuditRepository extends JpaRepository<ScriptEventAudit, Long> {
  Optional<ScriptEventAudit>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndScriptIdAndPluginIdAndPluginVersionIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String scriptId,
          String pluginId,
          String pluginVersionId,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          String scriptEventId,
          boolean dryRun);
}
