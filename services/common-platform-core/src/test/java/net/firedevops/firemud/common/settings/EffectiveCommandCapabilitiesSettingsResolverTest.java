package net.firedevops.firemud.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.config.FiremudCommandCapabilitiesProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EffectiveCommandCapabilitiesSettingsResolverTest {

  @Test
  void mergesTenantThenGameInstanceCapabilities() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new ScopedSettingsOverrides.CommandCapabilitiesOverride(
                        false, false, null, false)),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new ScopedSettingsOverrides.CommandCapabilitiesOverride(
                        null, true, false, null))));
    EffectiveCommandCapabilitiesSettingsResolver resolver =
        new EffectiveCommandCapabilitiesSettingsResolver(
            new FiremudCommandCapabilitiesProperties(true, true, true, true), authorityReader);

    EffectiveCommandCapabilitiesSettingsResolver.ResolvedValue<FiremudCommandCapabilitiesProperties>
        resolved = resolver.resolvedCapabilities(22L, 7L);

    assertThat(resolved.effective())
        .isEqualTo(new FiremudCommandCapabilitiesProperties(false, true, false, false));
    assertThat(resolved.sources())
        .containsExactly(
            "operatorDefaults", "tenantPersistedOverride:22", "gameInstancePersistedOverride:7");
    assertThat(resolver.isEnabled(PlayerCommandCapability.SOCIAL, 22L, 7L)).isFalse();
    assertThat(resolver.isEnabled(PlayerCommandCapability.PRESENCE, 22L, 7L)).isTrue();
    assertThat(resolver.isEnabled(PlayerCommandCapability.INVENTORY, 22L, 7L)).isFalse();
    assertThat(resolver.isEnabled(PlayerCommandCapability.COMMAND_HISTORY, 22L, 7L)).isFalse();
  }

  @Test
  void mandatoryCapabilityDoesNotReadPersistedSettings() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    EffectiveCommandCapabilitiesSettingsResolver resolver =
        new EffectiveCommandCapabilitiesSettingsResolver(
            new FiremudCommandCapabilitiesProperties(false, false, false, false), authorityReader);

    assertThat(resolver.isEnabled(PlayerCommandCapability.MANDATORY, 22L, 7L)).isTrue();

    verifyNoInteractions(authorityReader);
  }
}
