package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteFollowupResultRepository extends JpaRepository<RemoteFollowupResult, Long> {
  java.util.Optional<RemoteFollowupResult> findByTenantIdAndResultId(
      Long tenantId, String resultId);

  java.util.Optional<RemoteFollowupResult> findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(
      Long tenantId, String coordinatorId);

  List<RemoteFollowupResult> findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
      Long tenantId, String coordinatorId);

  @Query(
      """
      select result from RemoteFollowupResult result
      where result.tenantId = :tenantId
        and (:coordinatorId = '' or result.coordinatorId = :coordinatorId)
        and (:followupId = '' or result.followupId = :followupId)
        and (:originGameInstanceId is null or result.originGameInstanceId = :originGameInstanceId)
        and (:originRegionId = '' or result.originRegionId = :originRegionId)
        and (:targetGameInstanceId is null or result.targetGameInstanceId = :targetGameInstanceId)
        and (:targetRegionId = '' or result.targetRegionId = :targetRegionId)
        and (:outcome = '' or result.outcome = :outcome)
        and (:scriptId = '' or result.scriptId = :scriptId)
        and (:pluginId = '' or result.pluginId = :pluginId)
        and (:automationDispatchId = '' or result.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or result.commandId = :commandId)
      order by result.observedAt asc, result.id asc
      """)
  List<RemoteFollowupResult> findForControlPlane(
      @Param("tenantId") Long tenantId,
      @Param("coordinatorId") String coordinatorId,
      @Param("followupId") String followupId,
      @Param("originGameInstanceId") Long originGameInstanceId,
      @Param("originRegionId") String originRegionId,
      @Param("targetGameInstanceId") Long targetGameInstanceId,
      @Param("targetRegionId") String targetRegionId,
      @Param("outcome") String outcome,
      @Param("scriptId") String scriptId,
      @Param("pluginId") String pluginId,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      Pageable pageable);
}
