package net.firedevops.firemud.common.settings;

/** Read surface for the first shared persisted settings authority. */
public interface SharedSettingsAuthorityReader {
  ScopedSettingsSnapshot readOverrides(long tenantId, Long gameInstanceId);

  default ScopedSettingsSnapshot refreshOverrides(long tenantId, Long gameInstanceId) {
    invalidateOverrides(tenantId, gameInstanceId);
    return readOverrides(tenantId, gameInstanceId);
  }

  default void invalidateOverrides(long tenantId, Long gameInstanceId) {}
}
