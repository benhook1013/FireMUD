package net.firedevops.firemud.automationscripting.repository;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptHandoffEventRepository extends JpaRepository<ScriptHandoffEvent, Long> {
  @Query(
      """
      select event from ScriptHandoffEvent event
      where event.tenantId = :tenantId
        and (:gameInstanceId = '' or event.gameInstanceId = :gameInstanceId)
        and (:scriptPatchVersion = '' or event.scriptPatchVersion = :scriptPatchVersion)
        and (:workItemId is null or event.workItemId = :workItemId)
        and (:handoffOutcome = '' or event.handoffOutcome = :handoffOutcome)
        and (:targetGameInstanceId = '' or event.targetGameInstanceId = :targetGameInstanceId)
        and (:targetRegionId = '' or event.targetRegionId = :targetRegionId)
        and (:targetRegionEpoch <= 0 or event.targetRegionEpoch = :targetRegionEpoch)
        and (:remoteCoordinatorId = '' or event.remoteCoordinatorId = :remoteCoordinatorId)
        and (:remoteFollowupId = '' or event.remoteFollowupId = :remoteFollowupId)
        and (:scriptId = '' or event.scriptId = :scriptId)
        and (:pluginId = '' or event.pluginId = :pluginId)
        and (:automationDispatchId = '' or event.automationDispatchId = :automationDispatchId)
        and (:gameSessionCommandId = '' or event.gameSessionCommandId = :gameSessionCommandId)
        and (:targetEntityId = '' or event.targetEntityId = :targetEntityId)
        and (:playableStateScope = '' or event.playableStateScope = :playableStateScope)
        and (:worldSlug = '' or event.worldSlug = :worldSlug)
        and (:realmSlug = '' or event.realmSlug = :realmSlug)
        and (:pointerVersion = '' or event.pointerVersion = :pointerVersion)
        and (:sourceKind = '' or event.sourceKind = :sourceKind)
        and (:sourceState = '' or event.sourceState = :sourceState)
        and (:changedAfter is null or event.observedAt > :changedAfter)
        and (:changedBefore is null or event.observedAt < :changedBefore)
      order by event.observedAt desc, event.eventId desc
      """)
  List<ScriptHandoffEvent> findEvents(
      @Param("tenantId") String tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("scriptPatchVersion") String scriptPatchVersion,
      @Param("workItemId") Long workItemId,
      @Param("handoffOutcome") String handoffOutcome,
      @Param("targetGameInstanceId") String targetGameInstanceId,
      @Param("targetRegionId") String targetRegionId,
      @Param("targetRegionEpoch") long targetRegionEpoch,
      @Param("remoteCoordinatorId") String remoteCoordinatorId,
      @Param("remoteFollowupId") String remoteFollowupId,
      @Param("scriptId") String scriptId,
      @Param("pluginId") String pluginId,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("gameSessionCommandId") String gameSessionCommandId,
      @Param("targetEntityId") String targetEntityId,
      @Param("playableStateScope") String playableStateScope,
      @Param("worldSlug") String worldSlug,
      @Param("realmSlug") String realmSlug,
      @Param("pointerVersion") String pointerVersion,
      @Param("sourceKind") String sourceKind,
      @Param("sourceState") String sourceState,
      @Param("changedAfter") Instant changedAfter,
      @Param("changedBefore") Instant changedBefore,
      Pageable pageable);
}
