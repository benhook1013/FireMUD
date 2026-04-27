package net.firedevops.firemud.automationscripting.repository;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptPatchInstanceRolloutEventRepository
    extends JpaRepository<ScriptPatchInstanceRolloutEvent, Long> {
  @Query(
      """
      select event from ScriptPatchInstanceRolloutEvent event
      where event.tenantId = :tenantId
        and (:gameInstanceId = '' or event.gameInstanceId = :gameInstanceId)
        and (:scriptPatchVersion = '' or event.scriptPatchVersion = :scriptPatchVersion)
        and (:rolloutStatus = '' or event.rolloutStatus = :rolloutStatus)
        and (:changedAfter is null or event.observedAt > :changedAfter)
        and (:changedBefore is null or event.observedAt < :changedBefore)
      order by event.observedAt desc, event.eventId desc
      """)
  List<ScriptPatchInstanceRolloutEvent> findEvents(
      @Param("tenantId") String tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("scriptPatchVersion") String scriptPatchVersion,
      @Param("rolloutStatus") String rolloutStatus,
      @Param("changedAfter") Instant changedAfter,
      @Param("changedBefore") Instant changedBefore,
      Pageable pageable);
}
