package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteFollowupResultRepository extends JpaRepository<RemoteFollowupResult, Long> {
  List<RemoteFollowupResult> findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
      Long tenantId, String coordinatorId);
}
