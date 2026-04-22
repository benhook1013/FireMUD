package net.firedevops.firemud.automationscripting.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import org.springframework.data.domain.Pageable;
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

  List<ScriptWorkItem> findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
      String tenantId, String scriptPatchVersion, Collection<String> statuses);

  List<ScriptWorkItem> findByStatusOrderByCreatedAtAscIdAsc(String status, Pageable pageable);

  List<ScriptWorkItem> findByStatusOrderByUpdatedAtAscIdAsc(String status, Pageable pageable);

  long countByStatus(String status);

  long deleteByStatusAndUpdatedAtBefore(String status, Instant updatedAt);
}
