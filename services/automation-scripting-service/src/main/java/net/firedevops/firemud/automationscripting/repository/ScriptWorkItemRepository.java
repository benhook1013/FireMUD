package net.firedevops.firemud.automationscripting.repository;

import java.util.Collection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptWorkItemRepository extends JpaRepository<ScriptWorkItem, Long> {
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

  boolean existsByTenantIdAndScriptIdAndStatusIn(
      String tenantId, String scriptId, Collection<String> statuses);
}
