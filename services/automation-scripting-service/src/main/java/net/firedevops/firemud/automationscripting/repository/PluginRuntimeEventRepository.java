package net.firedevops.firemud.automationscripting.repository;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PluginRuntimeEventRepository extends JpaRepository<PluginRuntimeEvent, Long> {
  @Query(
      """
      select event from PluginRuntimeEvent event
      where event.tenantId = :tenantId
        and (:gameInstanceId = '' or event.gameInstanceId = :gameInstanceId)
        and (:pluginId = '' or event.pluginId = :pluginId)
        and (:pluginState = '' or event.pluginState = :pluginState)
        and (:activePluginVersionId = '' or event.activePluginVersionId = :activePluginVersionId)
        and (:changedAfter is null or event.observedAt > :changedAfter)
        and (:changedBefore is null or event.observedAt < :changedBefore)
      order by event.observedAt desc, event.eventId desc
      """)
  List<PluginRuntimeEvent> findEvents(
      @Param("tenantId") String tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("pluginId") String pluginId,
      @Param("pluginState") String pluginState,
      @Param("activePluginVersionId") String activePluginVersionId,
      @Param("changedAfter") Instant changedAfter,
      @Param("changedBefore") Instant changedBefore,
      Pageable pageable);
}
