package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.common.config.FiremudCommandCapabilitiesProperties;
import net.firedevops.firemud.common.settings.EffectiveCommandCapabilitiesSettingsResolver;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;

final class CommandCapabilitiesTestSupport {
  private CommandCapabilitiesTestSupport() {}

  static EffectiveCommandCapabilitiesSettingsResolver resolver(
      boolean socialEnabled,
      boolean presenceEnabled,
      boolean inventoryEnabled,
      boolean commandHistoryEnabled) {
    return new EffectiveCommandCapabilitiesSettingsResolver(
        new FiremudCommandCapabilitiesProperties(
            socialEnabled, presenceEnabled, inventoryEnabled, commandHistoryEnabled),
        (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty());
  }

  static EffectiveCommandCapabilitiesSettingsResolver allEnabled() {
    return resolver(true, true, true, true);
  }
}
