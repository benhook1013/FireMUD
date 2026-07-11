package net.firedevops.firemud.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SharedEffectiveSettingsResolverTest {

  @Test
  void mergesTenantThenGameInstanceOverridesIntoOneEffectiveLayer() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.readOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    null,
                    new ScopedSettingsOverrides.CommunicationOverride(
                        640,
                        new ScopedSettingsOverrides.CommunicationOverride.DefaultsOverride(
                            true, true, true, true)),
                    new ScopedSettingsOverrides.PresentationOverride(
                        null,
                        ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC,
                        true,
                        new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
                            true, null, 300L)),
                    null,
                    null,
                    new ScopedSettingsOverrides.CommandHistoryOverride(true, 10)),
                new ScopedSettingsOverrides(
                    null,
                    new ScopedSettingsOverrides.CommunicationOverride(
                        null,
                        new ScopedSettingsOverrides.CommunicationOverride.DefaultsOverride(
                            false, null, null, null)),
                    new ScopedSettingsOverrides.PresentationOverride("fr", null, null, null),
                    null,
                    null,
                    new ScopedSettingsOverrides.CommandHistoryOverride(null, 20))));

    SharedEffectiveSettingsResolver resolver = new SharedEffectiveSettingsResolver(authorityReader);

    SharedEffectiveSettingsResolver.ResolvedScopedSettings resolved = resolver.resolve(22L, 7L);

    assertThat(resolved.effectiveOverrides().communication().maxMessageLength()).isEqualTo(640);
    assertThat(resolved.effectiveOverrides().communication().defaults().sayEnabled()).isFalse();
    assertThat(resolved.effectiveOverrides().presentation().defaultLocaleTag()).isEqualTo("fr");
    assertThat(resolved.effectiveOverrides().presentation().defaultColorMode())
        .isEqualTo(ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC);
    assertThat(resolved.effectiveOverrides().commandHistory())
        .isEqualTo(new ScopedSettingsOverrides.CommandHistoryOverride(true, 20));
    assertThat(resolved.sourcesFor(ScopedSettingsOverrides.SettingsDomain.COMMAND_HISTORY, 22L, 7L))
        .containsExactly("tenantPersistedOverride:22", "gameInstancePersistedOverride:7");
    assertThat(resolved.sourcesFor(ScopedSettingsOverrides.SettingsDomain.COMMUNICATION, 22L, 7L))
        .containsExactly("tenantPersistedOverride:22", "gameInstancePersistedOverride:7");
  }

  @Test
  void refreshAndInvalidateDelegateToReaderForExplicitCacheControl() {
    SharedSettingsAuthorityReader authorityReader =
        Mockito.mock(SharedSettingsAuthorityReader.class);
    when(authorityReader.refreshOverrides(22L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                ScopedSettingsOverrides.empty(),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    null,
                    new ScopedSettingsOverrides.MovementOverride(false),
                    null,
                    null)));

    SharedEffectiveSettingsResolver resolver = new SharedEffectiveSettingsResolver(authorityReader);

    SharedEffectiveSettingsResolver.ResolvedScopedSettings refreshed = resolver.refresh(22L, 7L);
    resolver.invalidate(22L, 7L);

    assertThat(refreshed.effectiveOverrides().movement().postMoveLookEnabled()).isFalse();
    assertThat(refreshed.sourcesFor(ScopedSettingsOverrides.SettingsDomain.MOVEMENT, 22L, 7L))
        .containsExactly("gameInstancePersistedOverride:7");
    verify(authorityReader).refreshOverrides(22L, 7L);
    verify(authorityReader).invalidateOverrides(22L, 7L);
  }
}
