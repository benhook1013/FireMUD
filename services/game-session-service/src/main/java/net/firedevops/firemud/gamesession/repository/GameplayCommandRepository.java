package net.firedevops.firemud.gamesession.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GameplayCommandRepository extends JpaRepository<GameplayCommand, Long> {
  Optional<GameplayCommand> findByCommandId(String commandId);

  Optional<GameplayCommand>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
          Long tenantId,
          Long gameInstanceId,
          String regionId,
          Long regionEpoch,
          String automationDispatchId);

  Optional<GameplayCommand>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
          Long tenantId,
          Long gameInstanceId,
          String regionId,
          Long regionEpoch,
          String remoteFollowupId);

  Optional<GameplayCommand> findFirstByTenantIdAndRemoteFollowupId(
      Long tenantId, String remoteFollowupId);

  List<GameplayCommand> findByCommandIdIn(Collection<String> commandIds);

  long countByTenantIdAndGameInstanceIdAndCompletedAtIsNullAndExecutionOutcomeIn(
      Long tenantId, Long gameInstanceId, Collection<String> executionOutcomes);

  List<GameplayCommand> findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore(
      String executionOutcome, Instant acceptedBefore);

  @Query(
      """
      select command from GameplayCommand command
      where command.tenantId = :tenantId
        and command.gameInstanceId = :gameInstanceId
        and command.sourceType = 'AUTOMATION'
        and command.completedAt is null
        and command.executionOutcome in ('ACCEPTED', 'STAGED', 'RETRY_QUEUED')
        and (:regionId = '' or command.regionId = :regionId)
        and command.scriptPatchVersion = :scriptPatchVersion
      """)
  List<GameplayCommand> findQueuedAutomationCommandsForScriptPatch(
      @Param("tenantId") Long tenantId,
      @Param("gameInstanceId") Long gameInstanceId,
      @Param("regionId") String regionId,
      @Param("scriptPatchVersion") String scriptPatchVersion);

  @Query(
      """
      select command from GameplayCommand command
      where command.tenantId = :tenantId
        and command.gameInstanceId = :gameInstanceId
        and command.sourceType = 'AUTOMATION'
        and command.completedAt is null
        and command.executionOutcome in ('ACCEPTED', 'STAGED', 'RETRY_QUEUED')
        and (:regionId = '' or command.regionId = :regionId)
        and command.pluginId = :pluginId
        and command.pluginVersionId = :pluginVersionId
      """)
  List<GameplayCommand> findQueuedAutomationCommandsForPluginVersion(
      @Param("tenantId") Long tenantId,
      @Param("gameInstanceId") Long gameInstanceId,
      @Param("regionId") String regionId,
      @Param("pluginId") String pluginId,
      @Param("pluginVersionId") String pluginVersionId);
}
