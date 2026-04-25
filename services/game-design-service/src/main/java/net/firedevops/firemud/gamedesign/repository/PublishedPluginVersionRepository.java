package net.firedevops.firemud.gamedesign.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PublishedPluginVersionRepository
    extends JpaRepository<PublishedPluginVersion, Long> {
  Optional<PublishedPluginVersion> findByTenantIdAndPluginIdAndPluginVersionId(
      String tenantId, String pluginId, String pluginVersionId);

  @Query(
      """
      select publication
      from PublishedPluginVersion publication
      where publication.tenantId = :tenantId
        and (:pluginId = '' or publication.pluginId = :pluginId)
        and (:publicationState is null or publication.publicationState = :publicationState)
        and (:changedAfter is null or publication.lastChangedAt >= :changedAfter)
        and (:changedBefore is null or publication.lastChangedAt <= :changedBefore)
      order by publication.lastChangedAt desc, publication.id desc
      """)
  List<PublishedPluginVersion> listPublishedPluginVersions(
      @Param("tenantId") String tenantId,
      @Param("pluginId") String pluginId,
      @Param("publicationState") VersionLifecycleState publicationState,
      @Param("changedAfter") LocalDateTime changedAfter,
      @Param("changedBefore") LocalDateTime changedBefore,
      Pageable pageable);
}
