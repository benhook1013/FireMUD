package net.firedevops.firemud.gamesession.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteFollowupRepository extends JpaRepository<RemoteFollowup, Long> {
  Optional<RemoteFollowup> findByFollowupId(String followupId);

  Optional<RemoteFollowup> findByTenantIdAndFollowupId(Long tenantId, String followupId);

  Optional<RemoteFollowup> findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
      Long tenantId, String targetRegionId, long targetRegionEpoch, String effectKey);

  long countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
      Long tenantId, String targetRegionId, String status, long dueTickId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<RemoteFollowup>
      findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
          Long tenantId, String targetRegionId, String status, long dueTickId, Pageable pageable);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdAndStatusOrderByDueTickIdAscIdAsc(
      Long tenantId, String targetRegionId, String status);

  Optional<RemoteFollowup>
      findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
          Long tenantId, String targetRegionId, String status, long dueTickId);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdOrderByDueTickIdAsc(
      Long tenantId, String targetRegionId);

  List<RemoteFollowup> findByClaimedTickBatchIdOrderByIdAsc(String claimedTickBatchId);

  @Query(
      """
      select followup from RemoteFollowup followup
      where followup.tenantId = :tenantId
        and (:targetRegionId = '' or followup.targetRegionId = :targetRegionId)
        and (:status = '' or followup.status = :status)
        and (:originGameInstanceId is null or followup.originGameInstanceId = :originGameInstanceId)
        and (:originRegionId = '' or followup.originRegionId = :originRegionId)
        and (:originRegionEpoch <= 0 or followup.originRegionEpoch = :originRegionEpoch)
        and (:targetGameInstanceId is null or followup.targetGameInstanceId = :targetGameInstanceId)
        and (:targetRegionEpoch <= 0 or followup.targetRegionEpoch = :targetRegionEpoch)
        and (:followupId = '' or followup.followupId = :followupId)
        and (:scriptId = '' or followup.scriptId = :scriptId)
        and (:pluginId = '' or followup.pluginId = :pluginId)
        and (:automationDispatchId = '' or followup.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or followup.commandId = :commandId)
      order by followup.dueTickId asc, followup.id asc
      """)
  List<RemoteFollowup> findForControlPlane(
      @Param("tenantId") Long tenantId,
      @Param("targetRegionId") String targetRegionId,
      @Param("status") String status,
      @Param("originGameInstanceId") Long originGameInstanceId,
      @Param("originRegionId") String originRegionId,
      @Param("originRegionEpoch") long originRegionEpoch,
      @Param("targetGameInstanceId") Long targetGameInstanceId,
      @Param("targetRegionEpoch") long targetRegionEpoch,
      @Param("followupId") String followupId,
      @Param("scriptId") String scriptId,
      @Param("pluginId") String pluginId,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      Pageable pageable);
}
