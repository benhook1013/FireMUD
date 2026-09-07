package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionRequestHistory;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionRequestHistoryRepository;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionStateRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class AutomationAdmissionStateServiceImplTest {
  @Test
  void exactRequestRetryReturnsDurableResultAndChangedInputConflicts() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AtomicReference<AutomationAdmissionRequestHistory> durableResult = new AtomicReference<>();
    when(historyRepository.find(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(durableResult.get()));
    when(historyRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenAnswer(
            invocation -> {
              AutomationAdmissionRequestHistory history = invocation.getArgument(0);
              durableResult.set(history);
              return history;
            });
    AutomationAdmissionStateService service = service(repository, historyRepository);
    AutomationAdmissionStateService.SetAdmissionModeCommand command =
        command("actor-1", "rollback");

    AutomationAdmissionStateService.AdmissionStateSummary applied = service.setMode(command);
    AutomationAdmissionStateService.AdmissionStateSummary retry = service.setMode(command);

    assertThat(applied.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
    assertThat(retry).isEqualTo(applied);
    assertThat(state.getAdmissionEpoch()).isEqualTo(2L);
    verify(repository).save(state);
    verify(historyRepository).insertOrGet(any(AutomationAdmissionRequestHistory.class));
    assertThatThrownBy(() -> service.setMode(command("different-actor", "rollback")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different admission-mode request");
  }

  @Test
  void workflowRequestIdCanApplyDistinctModeKeys() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(state)).thenReturn(state);
    when(historyRepository.find(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AutomationAdmissionStateService service = service(repository, historyRepository);

    AutomationAdmissionStateService.AdmissionStateSummary paused =
        service.setMode(command("actor-1", "pause"));
    AutomationAdmissionStateService.AdmissionStateSummary resumed =
        service.setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                "tenant-1", "game-1", "region-1", "NORMAL", "request-1", "actor-1", "resume"));

    assertThat(paused.targetMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(paused.admissionEpoch()).isEqualTo(2L);
    assertThat(resumed.targetMode()).isEqualTo("NORMAL");
    assertThat(resumed.admissionEpoch()).isEqualTo(2L);
    assertThat(resumed.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
    verify(repository, times(2)).save(state);
    verify(historyRepository, times(2)).insertOrGet(any(AutomationAdmissionRequestHistory.class));
  }

  @Test
  void rejectsStateOnlyRequestEvidenceInsteadOfReconstructingAcknowledgement() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    state.setMode("PAUSED_FOR_ROLLBACK");
    state.setAdmissionEpoch(2L);
    state.setControlPlaneRequestId("request-1");
    state.setControlPlaneRequestFingerprint("different-fingerprint");
    when(historyRepository.find(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    AutomationAdmissionStateService service = service(repository, historyRepository);

    assertThatThrownBy(() -> service.setMode(command("actor-1", "rollback")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different admission-mode request");
    verify(repository, never()).save(any());
    verify(historyRepository, never()).insertOrGet(any());
  }

  @Test
  void matchingStateOnlyRequestStillFailsClosedWithoutDurableHistory() {
    AutomationAdmissionStateRepository initialRepository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository initialHistoryRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState initialState = state("tenant-1", "game-1", "region-1");
    when(initialRepository.findByTenantIdAndGameInstanceIdAndRegionId(
            "tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(initialState));
    when(initialRepository.save(initialState)).thenReturn(initialState);
    when(initialHistoryRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-1"))
        .thenReturn(Optional.empty());
    when(initialHistoryRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AutomationAdmissionStateService.AdmissionStateSummary applied =
        service(initialRepository, initialHistoryRepository)
            .setMode(command("actor-1", "rollback"));

    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState orphanedState = state("tenant-1", "game-1", "region-1");
    orphanedState.setMode("PAUSED_FOR_ROLLBACK");
    orphanedState.setAdmissionEpoch(2L);
    orphanedState.setControlPlaneRequestId("request-1");
    orphanedState.setControlPlaneRequestFingerprint(applied.requestFingerprint());
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(orphanedState));
    when(historyRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-1"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service(repository, historyRepository).setMode(command("actor-1", "rollback")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without a durable acknowledgement");
    verify(repository, never()).save(any());
    verify(historyRepository, never()).insertOrGet(any());
  }

  @Test
  void readOnlyLookupReportsMissingAndUnavailableAcknowledgementWithoutCreatingState() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.empty(), Optional.of(state));
    AutomationAdmissionStateService service = service(repository, historyRepository);

    Optional<AutomationAdmissionStateService.AdmissionStateSummary> missing =
        service.findState(" tenant-1 ", " game-1 ", " region-1 ");
    Optional<AutomationAdmissionStateService.AdmissionStateSummary> unavailable =
        service.findState("tenant-1", "game-1", "region-1");

    assertThat(missing).isEmpty();
    assertThat(unavailable).isPresent();
    assertThat(unavailable.orElseThrow().outcome())
        .isEqualTo(AutomationAdmissionStateService.OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE);
    assertThat(unavailable.orElseThrow().controlPlaneRequestId()).isEmpty();
    verify(repository, never()).save(any());
    verifyNoInteractions(historyRepository);
  }

  @Test
  void readOnlyLookupReturnsOnlyMatchingCurrentSuccessfulAcknowledgement() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    state.setMode("PAUSED_FOR_ROLLBACK");
    state.setAdmissionEpoch(2L);
    state.setControlPlaneRequestId("request-1");
    state.setControlPlaneRequestFingerprint("fingerprint-1");
    AutomationAdmissionRequestHistory history =
        history(
            state,
            "PAUSED_FOR_ROLLBACK",
            "request-1",
            "fingerprint-1",
            AutomationAdmissionStateService.OUTCOME_APPLIED);
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(historyRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-1"))
        .thenReturn(Optional.of(history));
    AutomationAdmissionStateService service = service(repository, historyRepository);

    AutomationAdmissionStateService.AdmissionStateSummary summary =
        service.findState("tenant-1", "game-1", "region-1").orElseThrow();

    assertThat(summary.controlPlaneRequestId()).isEqualTo("request-1");
    assertThat(summary.targetMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(summary.outcome()).isEqualTo(AutomationAdmissionStateService.OUTCOME_APPLIED);
    assertThat(summary.requestFingerprint()).isEqualTo("fingerprint-1");
    assertThat(summary.acknowledgedAtMs()).isEqualTo(300L);
  }

  @Test
  void normalizesPaddedSetAndCanonicalGetToSameAdmissionBarrier() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(historyRepository.find(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AutomationAdmissionStateService service = service(repository, historyRepository);

    AutomationAdmissionStateService.AdmissionStateSummary setSummary =
        service.setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                " tenant-1 ",
                " game-1 ",
                " region-1 ",
                "PAUSED_FOR_ROLLBACK",
                " request-1 ",
                " actor-1 ",
                " rollback "));
    AutomationAdmissionStateService.AdmissionStateSummary getSummary =
        service.getState("tenant-1", "game-1", "region-1");

    assertThat(setSummary.tenantId()).isEqualTo("tenant-1");
    assertThat(setSummary.gameInstanceId()).isEqualTo("game-1");
    assertThat(setSummary.regionId()).isEqualTo("region-1");
    assertThat(setSummary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(setSummary.controlPlaneRequestId()).isEqualTo("request-1");
    assertThat(getSummary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    verify(repository, times(2))
        .findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetTenantBeforeAnyLookup(String tenantId) {
    assertBlankSetField(tenantId, "game-1", "request-1", "actor-1", "reason", "tenant_id");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetGameInstanceBeforeAnyLookup(String gameInstanceId) {
    assertBlankSetField(
        "tenant-1", gameInstanceId, "request-1", "actor-1", "reason", "game_instance_id");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetRequestIdBeforeAnyLookup(String requestId) {
    assertBlankSetField(
        "tenant-1", "game-1", requestId, "actor-1", "reason", "control_plane_request_id");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetActorBeforeAnyLookup(String actor) {
    assertBlankSetField("tenant-1", "game-1", "request-1", actor, "reason", "actor");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankSetReasonBeforeAnyLookup(String reason) {
    assertBlankSetField("tenant-1", "game-1", "request-1", "actor-1", reason, "reason");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u2003"})
  void rejectsBlankGetScopeBeforeLookup(String tenantId) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionStateService service = service(repository, historyRepository);

    assertThatThrownBy(() -> service.findState(tenantId, "game-1", "region-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenant_id is required");
    verifyNoInteractions(repository, historyRepository);
  }

  private static void assertBlankSetField(
      String tenantId,
      String gameInstanceId,
      String requestId,
      String actor,
      String reason,
      String expectedMessageField) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionStateService service = service(repository, historyRepository);

    assertThatThrownBy(
            () ->
                service.setMode(
                    new AutomationAdmissionStateService.SetAdmissionModeCommand(
                        tenantId, gameInstanceId, "region-1", "NORMAL", requestId, actor, reason)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expectedMessageField + " is required");
    verifyNoInteractions(repository, historyRepository);
  }

  private static AutomationAdmissionStateService service(
      AutomationAdmissionStateRepository repository,
      AutomationAdmissionRequestHistoryRepository historyRepository) {
    return new AutomationAdmissionStateServiceImpl(repository, historyRepository);
  }

  private static AutomationAdmissionStateService.SetAdmissionModeCommand command(
      String actor, String reason) {
    return new AutomationAdmissionStateService.SetAdmissionModeCommand(
        "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-1", actor, reason);
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

  private static AutomationAdmissionRequestHistory history(
      AutomationAdmissionState state,
      String mode,
      String requestId,
      String fingerprint,
      String outcome) {
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setTenantId(state.getTenantId());
    history.setGameInstanceId(state.getGameInstanceId());
    history.setRegionId(state.getRegionId());
    history.setMode(mode);
    history.setControlPlaneRequestId(requestId);
    history.setRequestFingerprint(fingerprint);
    history.setAdmissionEpoch(state.getAdmissionEpoch());
    history.setOutcome(outcome);
    history.setActorPrincipal("actor-1");
    history.setReason("rollback");
    history.setCreatedAt(Instant.ofEpochMilli(300L));
    return history;
  }
}
