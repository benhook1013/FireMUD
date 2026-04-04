package net.firedevops.firemud.gamedesign.service;

import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;

public interface SettingsAuthorityService {
  ScopedSettingsSnapshot getScopedOverrides(String tenantId, Long gameInstanceId);

  void putDomainOverride(
      String tenantId,
      Long gameInstanceId,
      ScopedSettingsOverrides.SettingsDomain domain,
      ScopedSettingsOverrides overrides);

  void deleteDomainOverride(
      String tenantId, Long gameInstanceId, ScopedSettingsOverrides.SettingsDomain domain);
}
