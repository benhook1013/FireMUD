package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class EffectiveSettingsResolverTest {
  @Test
  void presentationOverridesMergeTenantThenGameInstance() {
    EffectiveSettingsResolver resolver =
        new EffectiveSettingsResolver(
            new PresentationProperties(
                "en-NZ",
                PresentationProperties.ColorMode.NONE,
                false,
                new PresentationProperties.Prompt(true, true, 150L)),
            new MovementProperties(true),
            new WorldTopologyProperties(),
            new GameSessionSettingsOverridesProperties(
                Map.of(
                    "22",
                    new GameSessionSettingsOverridesProperties.PresentationOverride(
                        null,
                        PresentationProperties.ColorMode.BASIC,
                        true,
                        new GameSessionSettingsOverridesProperties.PresentationOverride
                            .PromptOverride(null, null, 500L))),
                Map.of(
                    "7",
                    new GameSessionSettingsOverridesProperties.PresentationOverride(
                        "fr",
                        null,
                        null,
                        new GameSessionSettingsOverridesProperties.PresentationOverride
                            .PromptOverride(false, null, null))),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()));

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
    EffectiveSettingsResolver resolver =
        new EffectiveSettingsResolver(
            new PresentationProperties(),
            new MovementProperties(true),
            new WorldTopologyProperties(WorldTopologyProperties.ScopeModel.MAP_ONLY, false),
            new GameSessionSettingsOverridesProperties(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("41", new GameSessionSettingsOverridesProperties.MovementOverride(false)),
                Map.of(),
                Map.of(
                    "41",
                    new GameSessionSettingsOverridesProperties.WorldTopologyOverride(
                        WorldTopologyProperties.ScopeModel.REGION_AREA_AND_MAP, true))));

    SessionContext prePlay =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, null, null, 41L);

    assertThat(resolver.movement(prePlay).postMoveLookEnabled()).isFalse();
    assertThat(resolver.worldTopology(prePlay).scopeModel())
        .isEqualTo(WorldTopologyProperties.ScopeModel.REGION_AREA_AND_MAP);
    assertThat(resolver.worldTopology(prePlay).regionsEnabled()).isTrue();
  }
}
