package net.firedevops.firemud.automationscripting.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptEventAuditRepository extends JpaRepository<ScriptEventAudit, Long> {
  boolean
      existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String worldSlug,
          String realmSlug,
          String pointerVersion,
          String scriptId,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          String scriptEventId,
          boolean dryRun);

  Optional<ScriptEventAudit> findByWorkItemId(Long workItemId);

  @Query(
      """
      select audit from ScriptEventAudit audit
      where audit.tenantId = :tenantId
        and audit.sourceKind = 'SCHEDULE_TIMER'
        and (:gameInstanceId = '' or audit.gameInstanceId = :gameInstanceId)
        and (:scriptPatchVersion = '' or audit.scriptPatchVersion = :scriptPatchVersion)
        and (:scriptId = '' or audit.scriptId = :scriptId)
        and (:eventType = '' or audit.eventType = :eventType)
        and (:finalReason = '' or audit.finalReason = :finalReason)
        and (:changedAfter is null or audit.createdAt > :changedAfter)
        and (:changedBefore is null or audit.createdAt < :changedBefore)
      order by audit.createdAt desc, audit.id desc
      """)
  List<ScriptEventAudit> findTimerAuditEvents(
      @Param("tenantId") String tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("scriptPatchVersion") String scriptPatchVersion,
      @Param("scriptId") String scriptId,
      @Param("eventType") String eventType,
      @Param("finalReason") String finalReason,
      @Param("changedAfter") Instant changedAfter,
      @Param("changedBefore") Instant changedBefore,
      Pageable pageable);
}
