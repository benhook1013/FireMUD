package net.firedevops.firemud.gamedesign.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.GameSettingsOverride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameSettingsOverrideRepository extends JpaRepository<GameSettingsOverride, Long> {
  List<GameSettingsOverride> findByTenantIdAndGameInstanceIdIsNull(String tenantId);

  List<GameSettingsOverride> findByTenantIdAndGameInstanceId(String tenantId, Long gameInstanceId);

  Optional<GameSettingsOverride> findByTenantIdAndGameInstanceIdIsNullAndDomain(
      String tenantId, String domain);

  Optional<GameSettingsOverride> findByTenantIdAndGameInstanceIdAndDomain(
      String tenantId, Long gameInstanceId, String domain);
}
