package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteFollowupRepository extends JpaRepository<RemoteFollowup, Long> {
  Optional<RemoteFollowup> findByTenantIdAndFollowupId(Long tenantId, String followupId);

  Optional<RemoteFollowup> findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
      Long tenantId, String targetRegionId, long targetRegionEpoch, String effectKey);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdAndStatusOrderByDueTickIdAsc(
      Long tenantId, String targetRegionId, String status);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdOrderByDueTickIdAsc(
      Long tenantId, String targetRegionId);
}
