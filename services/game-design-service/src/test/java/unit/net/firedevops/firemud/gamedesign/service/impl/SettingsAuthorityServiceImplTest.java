package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.gamedesign.entity.GameSettingsOverride;
import net.firedevops.firemud.gamedesign.repository.GameSettingsOverrideRepository;
import org.junit.jupiter.api.Test;
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
            new ScopedSettingsOverrides.CommandHistoryOverride(maxEntries));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putDomainOverride(
                    "demo", 7L, ScopedSettingsOverrides.SettingsDomain.COMMAND_HISTORY, overrides))
        .withMessage("Command history maxEntries must be between 1 and 20");

    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAnEmptyCommandCapabilitiesOverrideBeforePersistence() {
    ScopedSettingsOverrides overrides =
        new ScopedSettingsOverrides(
            null,
            null,
            null,
            null,
            null,
            null,
            new ScopedSettingsOverrides.CommandCapabilitiesOverride(null, null, null, null));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putDomainOverride(
                    "demo",
                    7L,
                    ScopedSettingsOverrides.SettingsDomain.COMMAND_CAPABILITIES,
                    overrides))
        .withMessage("Command capabilities override must set at least one capability");

    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0})
  void rejectsNonPositiveReconnectTranscriptEntryLimitsBeforePersistence(int maxEntries) {
    ScopedSettingsOverrides overrides =
        new ScopedSettingsOverrides(
            new ScopedSettingsOverrides.ReconnectionOverride(
                null,
                new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                    null, maxEntries, null, null, null, null)),
            null,
            null,
            null,
            null);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putDomainOverride(
                    "demo", 7L, ScopedSettingsOverrides.SettingsDomain.RECONNECTION, overrides))
        .withMessage("Reconnection buffer maxEntries must be positive");

    verifyNoInteractions(repository);
  }

  @Test
  void rejectsExplicitReconnectByteBoundsBeforePersistence() {
    ScopedSettingsOverrides overrides =
        new ScopedSettingsOverrides(
            new ScopedSettingsOverrides.ReconnectionOverride(
                null,
                new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                    null, null, null, null, 70_000, 65_536)),
            null,
            null,
            null,
            null);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putDomainOverride(
                    "demo", null, ScopedSettingsOverrides.SettingsDomain.RECONNECTION, overrides))
        .withMessage("Reconnection buffer hardMaxBytes must be at least softMaxBytes");

    verifyNoInteractions(repository);
  }

  @Test
  void rejectsSparseGameInstanceByteBoundsAgainstTenantInheritedValuesBeforePersistence() {
    ObjectMapper objectMapper = Mockito.mock(ObjectMapper.class);
    ScopedSettingsOverrides.ReconnectionOverride tenantOverride =
        new ScopedSettingsOverrides.ReconnectionOverride(
            null,
            new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                null, null, null, null, 60_000, null));
    GameSettingsOverride tenantRow = new GameSettingsOverride();
    tenantRow.setPayload("tenant-reconnection");
    Mockito.when(repository.findByTenantIdAndGameInstanceIdIsNullAndDomain("demo", "RECONNECTION"))
        .thenReturn(Optional.of(tenantRow));
    Mockito.when(
            objectMapper.readValue(
                "tenant-reconnection", ScopedSettingsOverrides.ReconnectionOverride.class))
        .thenReturn(tenantOverride);
    SettingsAuthorityServiceImpl gameInstanceService =
        new SettingsAuthorityServiceImpl(repository, objectMapper);
    ScopedSettingsOverrides overrides =
        new ScopedSettingsOverrides(
            new ScopedSettingsOverrides.ReconnectionOverride(
                null,
                new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                    null, null, null, null, null, 50_000)),
            null,
            null,
            null,
            null);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                gameInstanceService.putDomainOverride(
                    "demo", 7L, ScopedSettingsOverrides.SettingsDomain.RECONNECTION, overrides))
        .withMessage("Reconnection buffer hardMaxBytes must be at least softMaxBytes");

    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
  }

  @Test
  void rejectsSemanticallyEmptyNestedReconnectionOverridesBeforePersistence() {
    var emptyPolicy =
        new ScopedSettingsOverrides.ReconnectionOverride(
            new ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride(null, null), null);
    var emptyBuffer =
        new ScopedSettingsOverrides.ReconnectionOverride(
            null,
            new ScopedSettingsOverrides.ReconnectionOverride.BufferOverride(
                null, null, null, null, null, null));

    for (var reconnection : java.util.List.of(emptyPolicy, emptyBuffer)) {
      ScopedSettingsOverrides overrides =
          new ScopedSettingsOverrides(reconnection, null, null, null, null);
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  service.putDomainOverride(
                      "demo", 7L, ScopedSettingsOverrides.SettingsDomain.RECONNECTION, overrides))
          .withMessage("Reconnection override must set policy or buffer values");
    }

    verifyNoInteractions(repository);
  }
}
