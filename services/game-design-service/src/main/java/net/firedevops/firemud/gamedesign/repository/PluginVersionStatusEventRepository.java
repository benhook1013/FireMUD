package net.firedevops.firemud.gamedesign.repository;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamedesign.entity.PluginVersionStatusEvent;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PluginVersionStatusEventRepository
    extends JpaRepository<PluginVersionStatusEvent, Long> {
  @Query(
      """
      select event from PluginVersionStatusEvent event
      where event.tenantId = :tenantId
        and (:pluginId = '' or event.pluginId = :pluginId)
        and (:pluginVersionId = '' or event.pluginVersionId = :pluginVersionId)
        and (:newPublicationState is null or event.newPublicationState = :newPublicationState)
        and (:changedAfter is null or event.observedAt > :changedAfter)
        and (:changedBefore is null or event.observedAt < :changedBefore)
      order by event.observedAt desc, event.eventId desc
      """)
  List<PluginVersionStatusEvent> findEvents(
      @Param("tenantId") String tenantId,
      @Param("pluginId") String pluginId,
      @Param("pluginVersionId") String pluginVersionId,
      @Param("newPublicationState") VersionLifecycleState newPublicationState,
      @Param("changedAfter") Instant changedAfter,
      @Param("changedBefore") Instant changedBefore,
      Pageable pageable);
}
