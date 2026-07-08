package net.firedevops.firemud.gamesession.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EffectiveSettingsControllerTest {
  private final EffectiveSettingsResolver settingsResolver =
      Mockito.mock(EffectiveSettingsResolver.class);
  private final EffectiveReconnectionSettingsResolver reconnectionSettingsResolver =
      Mockito.mock(EffectiveReconnectionSettingsResolver.class);
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver =
      Mockito.mock(SharedEffectiveSettingsResolver.class);
  private final SessionAuthenticationService sessionAuthenticationService =
      Mockito.mock(SessionAuthenticationService.class);

  private EffectiveSettingsController controller;

  @BeforeEach
  void setUp() {
    controller =
        new EffectiveSettingsController(
            settingsResolver,
            reconnectionSettingsResolver,
            sharedEffectiveSettingsResolver,
            sessionAuthenticationService);
  }

  @Test
  void effectiveSettingsUsesNormalizedPersistedSessionContext() {
    SessionContext cleared =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "jwt", "en-NZ", 1L);
    PresentationProperties presentation = new PresentationProperties();
    MovementProperties movement = new MovementProperties();
    WorldTopologyProperties worldTopology = new WorldTopologyProperties();
    FiremudReconnectionProperties reconnection = new FiremudReconnectionProperties(null, null);

    when(sessionAuthenticationService.resolveUnverifiedSessionContext("41"))
        .thenReturn(java.util.Optional.of(cleared));
    when(settingsResolver.resolvedPresentation(cleared))
        .thenReturn(
            new EffectiveSettingsResolver.ResolvedValue<>(presentation, List.of("default")));
    when(settingsResolver.resolvedMovement(cleared))
        .thenReturn(new EffectiveSettingsResolver.ResolvedValue<>(movement, List.of("default")));
    when(settingsResolver.resolvedWorldTopology(cleared))
        .thenReturn(
            new EffectiveSettingsResolver.ResolvedValue<>(worldTopology, List.of("default")));
    when(reconnectionSettingsResolver.resolvedReconnection(cleared))
        .thenReturn(
            new EffectiveReconnectionSettingsResolver.ResolvedValue<>(
                reconnection, List.of("default")));
    when(sharedEffectiveSettingsResolver.resolve(22L, 1L))
        .thenReturn(
            new SharedEffectiveSettingsResolver.ResolvedScopedSettings(
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty()));

    EffectiveSettingsController.EffectiveSettingsResponse response =
        Objects.requireNonNull(controller.effectiveSettings("41", null, null, null).getBody())
            .data();

    assertThat(response.scope().persistedSession()).isTrue();
    assertThat(response.scope().tenantId()).isEqualTo(22L);
    assertThat(response.scope().gameInstanceId()).isZero();
    assertThat(response.scope().bootstrapGameInstanceId()).isEqualTo(1L);
    assertThat(response.scope().localeTag()).isEqualTo("en-NZ");
  }
}
