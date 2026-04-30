package net.firedevops.firemud.gamesession.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RemoteFollowupRepository extends JpaRepository<RemoteFollowup, Long> {
  Optional<RemoteFollowup> findByTenantIdAndFollowupId(Long tenantId, String followupId);

  Optional<RemoteFollowup> findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
      Long tenantId, String targetRegionId, long targetRegionEpoch, String effectKey);

  long countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
      Long tenantId, String targetRegionId, String status, long dueTickId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<RemoteFollowup>
      findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
          Long tenantId, String targetRegionId, String status, long dueTickId, Pageable pageable);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdAndStatusOrderByDueTickIdAsc(
      Long tenantId, String targetRegionId, String status);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdOrderByDueTickIdAsc(
      Long tenantId, String targetRegionId);

  List<RemoteFollowup> findByClaimedTickBatchIdOrderByIdAsc(String claimedTickBatchId);
}
