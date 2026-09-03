package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionStateRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class AutomationAdmissionStateServiceImplTest {
  @Test
  void normalizesPaddedSetAndCanonicalGetToSameAdmissionBarrier() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    AutomationAdmissionStateService.AdmissionStateSummary setSummary =
        service.setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                " tenant-1 ",
                " game-1 ",
                " region-1 ",
                "PAUSED_FOR_ROLLBACK",
                "request-1",
                "actor-1",
                "rollback"));
    AutomationAdmissionStateService.AdmissionStateSummary getSummary =
        service.getState("tenant-1", "game-1", "region-1");

    assertThat(setSummary.tenantId()).isEqualTo("tenant-1");
    assertThat(setSummary.gameInstanceId()).isEqualTo("game-1");
    assertThat(setSummary.regionId()).isEqualTo("region-1");
    assertThat(setSummary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(getSummary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(state.getTenantId()).isEqualTo("tenant-1");
    assertThat(state.getGameInstanceId()).isEqualTo("game-1");
    verify(repository, times(2))
        .findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1");
  }

  @Test
  void normalizesPaddedGetScopeBeforeLookupAndSummary() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    AutomationAdmissionStateService.AdmissionStateSummary summary =
        service.getState(" tenant-1 ", " game-1 ", " region-1 ");

    assertThat(summary.tenantId()).isEqualTo("tenant-1");
    assertThat(summary.gameInstanceId()).isEqualTo("game-1");
    assertThat(summary.regionId()).isEqualTo("region-1");
    verify(repository).findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetTenantAfterNormalization(String tenantId) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    assertThatThrownBy(
            () ->
                service.setMode(
                    new AutomationAdmissionStateService.SetAdmissionModeCommand(
                        tenantId,
                        "game-1",
                        "region-1",
                        "NORMAL",
                        "request-1",
                        "actor-1",
                        "reason")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenant_id is required");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetGameInstanceAfterNormalization(String gameInstanceId) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    assertThatThrownBy(
            () ->
                service.setMode(
                    new AutomationAdmissionStateService.SetAdmissionModeCommand(
                        "tenant-1",
                        gameInstanceId,
                        "region-1",
                        "NORMAL",
                        "request-1",
                        "actor-1",
                        "reason")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("game_instance_id is required");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankGetGameInstanceAfterNormalization(String gameInstanceId) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    assertThatThrownBy(() -> service.getState("tenant-1", gameInstanceId, "region-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("game_instance_id is required");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankGetTenantAfterNormalization(String tenantId) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    assertThatThrownBy(() -> service.getState(tenantId, "game-1", "region-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenant_id is required");
    verifyNoInteractions(repository);
  }

  private static AutomationAdmissionState state(
      String tenantId, String gameInstanceId, String regionId) {
    AutomationAdmissionState state = new AutomationAdmissionState();
    state.setTenantId(tenantId);
    state.setGameInstanceId(gameInstanceId);
    state.setRegionId(regionId);
    state.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return state;
  }
}
