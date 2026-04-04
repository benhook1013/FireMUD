package net.firedevops.firemud.common.settings;

/** Scoped persisted override layers returned by the first shared settings authority. */
public record ScopedSettingsSnapshot(
    ScopedSettingsOverrides tenantOverrides, ScopedSettingsOverrides gameInstanceOverrides) {

  public ScopedSettingsSnapshot {
    tenantOverrides = tenantOverrides == null ? ScopedSettingsOverrides.empty() : tenantOverrides;
    gameInstanceOverrides =
        gameInstanceOverrides == null ? ScopedSettingsOverrides.empty() : gameInstanceOverrides;
  }

  public static ScopedSettingsSnapshot empty() {
    return new ScopedSettingsSnapshot(
        ScopedSettingsOverrides.empty(), ScopedSettingsOverrides.empty());
  }
}
