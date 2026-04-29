package net.firedevops.firemud.automationscripting.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptWorkItemRepository extends JpaRepository<ScriptWorkItem, Long> {
  interface ScriptPatchInstanceProjection {
    String getGameInstanceId();

    String getScriptPatchVersion();
  }

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

  boolean existsByTenantIdAndScriptIdAndStatusIn(
      String tenantId, String scriptId, Collection<String> statuses);

  List<ScriptWorkItem> findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
      String tenantId, String scriptPatchVersion, Collection<String> statuses);

  List<ScriptWorkItem>
      findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
          String tenantId, String pluginId, String pluginVersionId, Collection<String> statuses);

  List<ScriptWorkItem> findByTenantIdAndScriptPatchVersion(
      String tenantId, String scriptPatchVersion);

  List<ScriptWorkItem> findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
      String tenantId, String gameInstanceId, String scriptPatchVersion);

  @Query(
      """
      select item
      from ScriptWorkItem item
      where item.tenantId = :tenantId
        and item.gameInstanceId = :gameInstanceId
        and (:regionId = '' or item.regionId = :regionId)
        and item.status in :statuses
      order by item.createdAt asc, item.id asc
      """)
  List<ScriptWorkItem> findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
      @Param("tenantId") String tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("regionId") String regionId,
      @Param("statuses") Collection<String> statuses);

  @Query(
      "select distinct item.scriptPatchVersion from ScriptWorkItem item where item.tenantId = :tenantId")
  List<String> findDistinctScriptPatchVersionsByTenantId(@Param("tenantId") String tenantId);

  @Query(
      """
      select distinct item.gameInstanceId as gameInstanceId, item.scriptPatchVersion as scriptPatchVersion
      from ScriptWorkItem item
      where item.tenantId = :tenantId
        and (:gameInstanceId = '' or item.gameInstanceId = :gameInstanceId)
        and (:scriptPatchVersion = '' or item.scriptPatchVersion = :scriptPatchVersion)
      """)
  List<ScriptPatchInstanceProjection> findDistinctInstancePatchPairs(
      @Param("tenantId") String tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("scriptPatchVersion") String scriptPatchVersion);

  List<ScriptWorkItem> findByStatusOrderByCreatedAtAscIdAsc(String status, Pageable pageable);

  List<ScriptWorkItem> findByIdInAndStatusOrderByCreatedAtAscIdAsc(
      Collection<Long> ids, String status, Pageable pageable);

  List<ScriptWorkItem> findByStatusInOrderByCreatedAtAscIdAsc(
      Collection<String> statuses, Pageable pageable);

  List<ScriptWorkItem> findByStatusOrderByUpdatedAtAscIdAsc(String status, Pageable pageable);

  List<ScriptWorkItem> findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
      String tenantId, String status, Pageable pageable);

  long countByStatus(String status);

  long deleteByStatusAndUpdatedAtBefore(String status, Instant updatedAt);
}
