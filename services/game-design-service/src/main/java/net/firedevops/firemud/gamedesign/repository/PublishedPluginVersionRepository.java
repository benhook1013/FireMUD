package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishedPluginVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishedPluginVersionRepository
    extends JpaRepository<PublishedPluginVersion, Long> {
  Optional<PublishedPluginVersion> findByTenantIdAndPluginIdAndPluginVersionId(
      String tenantId, String pluginId, String pluginVersionId);
}
