package net.firedevops.firemud.automationscripting.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PluginRuntimeStateRepository extends JpaRepository<PluginRuntimeState, Long> {
  Optional<PluginRuntimeState> findByTenantIdAndGameInstanceIdAndPluginId(
      String tenantId, String gameInstanceId, String pluginId);

  List<PluginRuntimeState> findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
      String pluginState, String activePluginVersionId, Pageable pageable);
}
