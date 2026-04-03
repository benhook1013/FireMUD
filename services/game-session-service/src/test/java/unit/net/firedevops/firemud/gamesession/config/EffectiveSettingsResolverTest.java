package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EffectiveSettingsResolverTest {
  @Test
  void presentationOverridesMergeTenantThenGameInstance() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    null,
                    null,
                    new ScopedSettingsOverrides.PresentationOverride(
                        null,
                        ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC,
                        true,
                        new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
                            null, null, 500L)),
                    null,
                    null),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    new ScopedSettingsOverrides.PresentationOverride(
                        "fr",
                        null,
                        null,
                        new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
                            false, null, null)),
                    null,
                    null)));
    EffectiveSettingsResolver resolver =
        new EffectiveSettingsResolver(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)),
            new MovementProperties(true),
            new WorldTopologyProperties(),
            authorityReader);

    PresentationProperties effective =
        resolver.presentation(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 911L, "Ember", 7L, "R-1", null));

    assertThat(effective.defaultLocaleTag()).isEqualTo("fr");
    assertThat(effective.defaultColorMode()).isEqualTo(PresentationProperties.ColorMode.BASIC);
    assertThat(effective.briefEnabledByDefault()).isTrue();
    assertThat(effective.prompt().enabled()).isFalse();
    assertThat(effective.prompt().coalesceWindowMs()).isEqualTo(500L);
  }

  @Test
  void movementAndTopologyOverridesResolveAgainstBootstrapGameInstanceBeforePlay() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 41L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                ScopedSettingsOverrides.empty(),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    null,
                    new ScopedSettingsOverrides.MovementOverride(false),
                    new ScopedSettingsOverrides.WorldTopologyOverride(
                        ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel
                            .REGION_AREA_AND_MAP,
                        true))));
    EffectiveSettingsResolver resolver =
        new EffectiveSettingsResolver(
            new PresentationProperties(),
            new MovementProperties(true),
            new WorldTopologyProperties(WorldTopologyProperties.ScopeModel.MAP_ONLY, false),
            authorityReader);

    SessionContext prePlay =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, null, null, 41L);

    assertThat(resolver.movement(prePlay).postMoveLookEnabled()).isFalse();
    assertThat(resolver.worldTopology(prePlay).scopeModel())
        .isEqualTo(WorldTopologyProperties.ScopeModel.REGION_AREA_AND_MAP);
    assertThat(resolver.worldTopology(prePlay).regionsEnabled()).isTrue();
  }
}
