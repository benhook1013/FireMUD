package net.firedevops.firemud.automationscripting.repository;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScriptEventIngressAuditRepository
    extends JpaRepository<ScriptEventIngressAudit, Long> {
  Optional<ScriptEventIngressAudit>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          String scriptEventId,
          boolean dryRun);
}
