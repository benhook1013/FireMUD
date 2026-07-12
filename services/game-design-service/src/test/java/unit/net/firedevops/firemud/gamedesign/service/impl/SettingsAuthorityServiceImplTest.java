package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.gamedesign.repository.GameSettingsOverrideRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class SettingsAuthorityServiceImplTest {
  private final GameSettingsOverrideRepository repository =
      Mockito.mock(GameSettingsOverrideRepository.class);
  private final SettingsAuthorityServiceImpl service =
      new SettingsAuthorityServiceImpl(repository, Mockito.mock(ObjectMapper.class));

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 21})
  void rejectsOutOfRangeCommandHistoryRetentionBeforePersistence(int maxEntries) {
    ScopedSettingsOverrides overrides =
        new ScopedSettingsOverrides(
            null,
            null,
            null,
            null,
            null,
            new ScopedSettingsOverrides.CommandHistoryOverride(null, maxEntries));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putDomainOverride(
                    "demo", 7L, ScopedSettingsOverrides.SettingsDomain.COMMAND_HISTORY, overrides))
        .withMessage("Command history maxEntries must be between 1 and 20");

    verifyNoInteractions(repository);
  }
}
