package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EffectiveCommandHistorySettingsResolverTest {
  @Test
  void resolvesTenantThenGameInstanceCommandHistoryOverrides() {
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
                    new ScopedSettingsOverrides.CommandHistoryOverride(12)),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    null,
                    null,
                    null,
                    new ScopedSettingsOverrides.CommandHistoryOverride(20))));
    EffectiveCommandHistorySettingsResolver resolver =
        new EffectiveCommandHistorySettingsResolver(
            new FiremudCommandHistoryProperties(10), authorityReader);

    EffectiveCommandHistorySettingsResolver.ResolvedValue<FiremudCommandHistoryProperties>
        resolved =
            resolver.resolvedCommandHistory(
                new SessionContext(
                    1L, 22L, 123L, "demo@example.com", 911L, "Ember", 7L, "R-1", null));

    assertThat(resolved.effective()).isEqualTo(new FiremudCommandHistoryProperties(20));
    assertThat(resolved.sources())
        .containsExactly(
            "operatorDefaults", "tenantPersistedOverride:22", "gameInstancePersistedOverride:7");
  }

  @Test
  void clampsConfiguredHistoryToThePlatformMaximum() {
    assertThat(new FiremudCommandHistoryProperties(99).maxEntries()).isEqualTo(20);
  }

  @Test
  void normalizesNonPositiveGameInstanceIdsToTheTenantScope() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, null)).thenReturn(ScopedSettingsSnapshot.empty());
    EffectiveCommandHistorySettingsResolver resolver =
        new EffectiveCommandHistorySettingsResolver(
            new FiremudCommandHistoryProperties(10), authorityReader);

    assertThat(resolver.commandHistory(22L, 0L)).isEqualTo(new FiremudCommandHistoryProperties(10));

    verify(authorityReader).readOverrides(22L, null);
  }
}
