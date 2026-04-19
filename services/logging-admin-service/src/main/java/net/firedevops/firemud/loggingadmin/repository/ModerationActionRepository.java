package net.firedevops.firemud.loggingadmin.repository;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {
  @Query(
      """
      select action
      from ModerationAction action
      where action.tenantId = :tenantId
        and action.accountId = :accountId
        and lower(action.action) in :actions
        and (action.expiresAt is null or action.expiresAt > :now)
      order by action.createdAt desc
      """)
  List<ModerationAction> findActivePolicyActions(
      @Param("tenantId") Long tenantId,
      @Param("accountId") Long accountId,
      @Param("actions") List<String> actions,
      @Param("now") Instant now);
}
