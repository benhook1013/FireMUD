package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
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
  void rejectsInvalidOperatorDefaultsDuringConstruction() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                new EffectiveReconnectionSettingsResolver(
                    new FiremudReconnectionProperties(
                        new FiremudReconnectionProperties.Policy(45_000L, true),
                        new FiremudReconnectionProperties.Buffer(
                            60_000L, 256, 8, 24, 70_000, 65_536)),
                    authorityReader))
        .withMessage("Operator reconnection buffer hardMaxBytes must be at least softMaxBytes");
  }

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
                new FiremudReconnectionProperties.Buffer(60_000L, 256, 8, 24, 16_384, 65_536)),
            authorityReader);

    FiremudReconnectionProperties effective =
        resolver.reconnection(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 911L, "Ember", 7L, "R-1", null));

    assertThat(effective.policy().resumeWindowMs()).isEqualTo(45_000L);
    assertThat(effective.policy().staleResumeFallsThroughToFreshEntry()).isTrue();
    assertThat(effective.buffer().ttlMs()).isEqualTo(60_000L);
    assertThat(effective.buffer().minMessages()).isEqualTo(8);
  }

  @Test
  void persistedReconnectEntryLimitOverridesOperatorDefault() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                ScopedSettingsOverrides.empty(),
                new ScopedSettingsOverrides(
                    new ScopedSettingsOverrides.ReconnectionOverride(
                        null,
                        new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                            null, 12, null, null, null, null)),
                    null,
                    null,
                    null,
                    null)));
    EffectiveReconnectionSettingsResolver resolver =
        new EffectiveReconnectionSettingsResolver(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(45_000L, true),
                new FiremudReconnectionProperties.Buffer(60_000L, 256, 8, 24, 16_384, 65_536)),
            authorityReader);

    assertThat(resolver.resolve(22L, 7L).buffer().maxEntries()).isEqualTo(12);
  }

  @Test
  void disregardsInvalidSparseOverrideAndFallsBackToOperatorDefaultsWithDiagnostic() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                ScopedSettingsOverrides.empty(),
                new ScopedSettingsOverrides(
                    new ScopedSettingsOverrides.ReconnectionOverride(
                        null,
                        new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                            null, null, null, null, 70_000, null)),
                    null,
                    null,
                    null,
                    null)));
    EffectiveReconnectionSettingsResolver resolver =
        new EffectiveReconnectionSettingsResolver(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(45_000L, true),
                new FiremudReconnectionProperties.Buffer(60_000L, 256, 8, 24, 16_384, 65_536)),
            authorityReader);

    EffectiveReconnectionSettingsResolver.ResolvedValue<FiremudReconnectionProperties> resolved =
        resolver.resolvedReconnection(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 911L, "Ember", 7L, "R-1", null));

    assertThat(resolved.effective().buffer().softMaxBytes()).isEqualTo(16_384);
    assertThat(resolved.effective().buffer().hardMaxBytes()).isEqualTo(65_536);
    assertThat(resolved.sources()).containsExactly("operatorDefaults");
    assertThat(resolved.diagnostics())
        .containsExactly(
            "Ignored gameInstancePersistedOverride:7 override: "
                + "effective buffer hardMaxBytes must be at least softMaxBytes");
  }

  @Test
  void invalidGameInstanceOverrideFallsBackToValidTenantLayer() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    new ScopedSettingsOverrides.ReconnectionOverride(
                        null,
                        new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                            null, null, null, null, null, 80_000)),
                    null,
                    null,
                    null,
                    null),
                new ScopedSettingsOverrides(
                    new ScopedSettingsOverrides.ReconnectionOverride(
                        null,
                        new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                            null, null, null, null, 90_000, null)),
                    null,
                    null,
                    null,
                    null)));
    EffectiveReconnectionSettingsResolver resolver =
        new EffectiveReconnectionSettingsResolver(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(45_000L, true),
                new FiremudReconnectionProperties.Buffer(60_000L, 256, 8, 24, 16_384, 65_536)),
            authorityReader);

    EffectiveReconnectionSettingsResolver.ResolvedValue<FiremudReconnectionProperties> resolved =
        resolver.resolvedReconnection(
            new SessionContext(1L, 22L, 123L, "demo@example.com", 911L, "Ember", 7L, "R-1", null));

    assertThat(resolved.effective().buffer().softMaxBytes()).isEqualTo(16_384);
    assertThat(resolved.effective().buffer().hardMaxBytes()).isEqualTo(80_000);
    assertThat(resolved.sources())
        .containsExactly("operatorDefaults", "tenantPersistedOverride:22");
    assertThat(resolved.diagnostics())
        .containsExactly(
            "Ignored gameInstancePersistedOverride:7 override: "
                + "effective buffer hardMaxBytes must be at least softMaxBytes");
  }
}
