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
      @Param("changedAfter") Instant changedAfter,
      @Param("changedBefore") Instant changedBefore,
      Pageable pageable);
}
