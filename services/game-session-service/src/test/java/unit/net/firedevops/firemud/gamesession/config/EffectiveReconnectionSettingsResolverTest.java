package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EffectiveReconnectionSettingsResolverTest {
  @Test
  void absentPersistedReconnectionOverrideFallsBackToOperatorDefaults() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                ScopedSettingsOverrides.empty(),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    new ScopedSettingsOverrides.PresentationOverride("fr", null, null, null),
                    null,
                    null)));
    EffectiveReconnectionSettingsResolver resolver =
        new EffectiveReconnectionSettingsResolver(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(45_000L, true),
                new FiremudReconnectionProperties.Buffer(60_000L, 8, 24, 16_384, 65_536)),
            authorityReader);

    FiremudReconnectionProperties effective =
        resolver.reconnection(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 911L, "Ember", 7L, "R-1", null));

    assertThat(effective.policy().resumeWindowMs()).isEqualTo(45_000L);
    assertThat(effective.policy().staleResumeFallsThroughToFreshEntry()).isTrue();
    assertThat(effective.buffer().ttlMs()).isEqualTo(60_000L);
    assertThat(effective.buffer().minMessages()).isEqualTo(8);
  }
}
