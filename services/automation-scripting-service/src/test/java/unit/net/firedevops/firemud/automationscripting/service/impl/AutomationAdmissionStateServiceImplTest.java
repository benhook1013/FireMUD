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
                "\u2003region-1\u2003",
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
    assertThat(setSummary.outcome()).isEqualTo("APPLIED");
    assertThat(getSummary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(getSummary.outcome())
        .isEqualTo(AutomationAdmissionStateService.OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE);
    assertThat(state.getTenantId()).isEqualTo("tenant-1");
    assertThat(state.getGameInstanceId()).isEqualTo("game-1");
    verify(repository, times(2))
        .findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1");
  }

  @Test
  void persistsNormalizedLongActorInMutableStateAndImmutableHistory() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    String actorPrincipal = "x".repeat(129);
    AtomicReference<AutomationAdmissionState> savedState = new AtomicReference<>();
    AtomicReference<AutomationAdmissionRequestHistory> savedHistory = new AtomicReference<>();
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(
            invocation -> {
              AutomationAdmissionState saved = invocation.getArgument(0);
              savedState.set(saved);
              return saved;
            });
    when(historyRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-long-actor"))
        .thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenAnswer(
            invocation -> {
              AutomationAdmissionRequestHistory history = invocation.getArgument(0);
              savedHistory.set(history);
              return history;
            });

    new AutomationAdmissionStateServiceImpl(repository, historyRepository)
        .setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                "tenant-1",
                "game-1",
                "region-1",
                "PAUSED_FOR_ROLLBACK",
                "request-long-actor",
                "  " + actorPrincipal + "  ",
                "rollback"));

    assertThat(savedState.get()).isNotNull();
    assertThat(savedState.get().getActorPrincipal()).isEqualTo(actorPrincipal);
    assertThat(savedHistory.get()).isNotNull();
    assertThat(savedHistory.get().getActorPrincipal()).isEqualTo(actorPrincipal);
  }

  @Test
  void retriesWithUnicodeWhitespaceUseTheSameRequestFingerprint() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    service.setMode(
        new AutomationAdmissionStateService.SetAdmissionModeCommand(
            "tenant-1",
            "game-1",
            "region-1",
            "NORMAL",
            "\u2003request-1\u2003",
            "\u2003actor-1\u2003",
            "\u2003retry\u2003"));
    AutomationAdmissionStateService.AdmissionStateSummary retry =
        service.setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                "tenant-1", "game-1", "region-1", "NORMAL", "request-1", "actor-1", "retry"));

    assertThat(retry.mode()).isEqualTo("NORMAL");
    assertThat(retry.admissionEpoch()).isEqualTo(1L);
    assertThat(retry.controlPlaneRequestId()).isEqualTo("request-1");
    verify(repository, times(1)).save(any(AutomationAdmissionState.class));
  }

  @Test
  void requestFingerprintUsesLengthFramingAndBindsRequestFields() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    AtomicReference<String> firstFingerprint = new AtomicReference<>();
    AtomicReference<String> secondFingerprint = new AtomicReference<>();
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(
            invocation -> {
              AutomationAdmissionState saved = invocation.getArgument(0);
              if (firstFingerprint.get() == null) {
                firstFingerprint.set(saved.getControlPlaneRequestFingerprint());
              } else {
                secondFingerprint.set(saved.getControlPlaneRequestFingerprint());
              }
              return saved;
            });
    AutomationAdmissionStateService service = new AutomationAdmissionStateServiceImpl(repository);

    service.setMode(
        new AutomationAdmissionStateService.SetAdmissionModeCommand(
            "tenant-1", "game-1", "region-1", "NORMAL", "request-1", "a\u0000b", "c"));
    service.setMode(
        new AutomationAdmissionStateService.SetAdmissionModeCommand(
            "tenant-1", "game-1", "region-1", "NORMAL", "request-1\u0000a", "b", "c"));

    assertThat(firstFingerprint.get()).isNotBlank();
    assertThat(secondFingerprint.get()).isNotBlank();
    assertThat(firstFingerprint.get()).isNotEqualTo(secondFingerprint.get());
  }

  @Test
  void normalizesNullableDurableHistoryIdentityForMutationReadback() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    AutomationAdmissionRequestHistory durableWinner = new AutomationAdmissionRequestHistory();
    durableWinner.setTenantId("tenant-1");
    durableWinner.setGameInstanceId("game-1");
    durableWinner.setRegionId("region-1");
    durableWinner.setMode("NORMAL");
    durableWinner.setAdmissionEpoch(1L);
    durableWinner.setOutcome("APPLIED");
    durableWinner.setCreatedAt(Instant.ofEpochMilli(100L));
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(historyRepository.find("tenant-1", "game-1", "region-1", "NORMAL", "request-1"))
        .thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenReturn(durableWinner);

    AutomationAdmissionStateService.AdmissionStateSummary summary =
        new AutomationAdmissionStateServiceImpl(repository, historyRepository)
            .setMode(
                new AutomationAdmissionStateService.SetAdmissionModeCommand(
                    "tenant-1", "game-1", "region-1", "NORMAL", "request-1", "actor", "reason"));

    assertThat(summary.controlPlaneRequestId()).isEmpty();
    assertThat(summary.requestFingerprint()).isEmpty();
  }

  @Test
  void olderAcceptedRequestReplaysItsResultAfterLaterModeTransition() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(repository.save(any(AutomationAdmissionState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AtomicReference<AutomationAdmissionRequestHistory> pauseHistory = new AtomicReference<>();
    AtomicReference<AutomationAdmissionRequestHistory> normalHistory = new AtomicReference<>();
    when(historyRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-pause"))
        .thenAnswer(
            invocation ->
                pauseHistory.get() == null ? Optional.empty() : Optional.of(pauseHistory.get()));
    when(historyRepository.find("tenant-1", "game-1", "region-1", "NORMAL", "request-resume"))
        .thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(any(AutomationAdmissionRequestHistory.class)))
        .thenAnswer(
            invocation -> {
              AutomationAdmissionRequestHistory history = invocation.getArgument(0);
              if ("PAUSED_FOR_ROLLBACK".equals(history.getMode())) {
                pauseHistory.set(history);
              } else {
                normalHistory.set(history);
              }
              return history;
            });
    AutomationAdmissionStateService service =
        new AutomationAdmissionStateServiceImpl(repository, historyRepository);

    service.setMode(
        new AutomationAdmissionStateService.SetAdmissionModeCommand(
            "tenant-1",
            "game-1",
            "region-1",
            "PAUSED_FOR_ROLLBACK",
            "request-pause",
            "actor-1",
            "rollback"));
    state.setMode("NORMAL");
    state.setAdmissionEpoch(2L);
    state.setControlPlaneRequestId("request-resume-latest");
    service.setMode(
        new AutomationAdmissionStateService.SetAdmissionModeCommand(
            "tenant-1", "game-1", "region-1", "NORMAL", "request-resume", "actor-2", "resume"));

    AutomationAdmissionStateService.AdmissionStateSummary replay =
        service.setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                "tenant-1",
                "game-1",
                "region-1",
                "PAUSED_FOR_ROLLBACK",
                "request-pause",
                "actor-1",
                "rollback"));

    assertThat(replay.mode()).isEqualTo("NORMAL");
    assertThat(replay.admissionEpoch()).isEqualTo(2L);
    assertThat(replay.outcome()).isEqualTo("APPLIED");
    assertThat(replay.controlPlaneRequestId()).isEqualTo("request-resume");
    assertThat(replay.actorPrincipal()).isEqualTo("actor-2");
    assertThat(replay.reason()).isEqualTo("resume");
    assertThat(replay.updatedAtMs()).isEqualTo(state.getUpdatedAt().toEpochMilli());
    assertThat(replay.targetMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(replay.requestFingerprint()).isEqualTo(pauseHistory.get().getRequestFingerprint());
    assertThat(replay.acknowledgedAtMs())
        .isEqualTo(pauseHistory.get().getCreatedAt().toEpochMilli());
    verify(repository, times(2)).save(any(AutomationAdmissionState.class));
  }

  @Test
  void requestFingerprintConflictDoesNotMutateAdmissionEpoch() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setRequestFingerprint("different-fingerprint");
    when(historyRepository.find("tenant-1", "game-1", "region-1", "NORMAL", "request-1"))
        .thenReturn(Optional.of(history));
    AutomationAdmissionStateService service =
        new AutomationAdmissionStateServiceImpl(repository, historyRepository);

    assertThatThrownBy(
            () ->
                service.setMode(
                    new AutomationAdmissionStateService.SetAdmissionModeCommand(
                        "tenant-1",
                        "game-1",
                        "region-1",
                        "NORMAL",
                        "request-1",
                        "actor",
                        "reason")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id already records a different admission-mode request");
    verifyNoInteractions(repository);
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
        service.getState(" tenant-1 ", " game-1 ", "\u2003region-1\u2003");

    assertThat(summary.tenantId()).isEqualTo("tenant-1");
    assertThat(summary.gameInstanceId()).isEqualTo("game-1");
    assertThat(summary.regionId()).isEqualTo("region-1");
    verify(repository).findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1");
  }

  @Test
  void findStateIncludesDurableAcknowledgementWhenHistoryMatchesCurrentState() {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    state.setMode("PAUSED_FOR_ROLLBACK");
    state.setAdmissionEpoch(4L);
    state.setControlPlaneRequestId("request-4");
    state.setControlPlaneRequestFingerprint("fingerprint-4");
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setTenantId("tenant-1");
    history.setGameInstanceId("game-1");
    history.setRegionId("region-1");
    history.setMode("PAUSED_FOR_ROLLBACK");
    history.setControlPlaneRequestId("request-4");
    history.setRequestFingerprint("fingerprint-4");
    history.setAdmissionEpoch(4L);
    history.setOutcome("APPLIED");
    history.setActorPrincipal("operator");
    history.setReason("rollback");
    history.setCreatedAt(Instant.ofEpochMilli(400L));
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(historyRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-4"))
        .thenReturn(Optional.of(history));

    AutomationAdmissionStateService.AdmissionStateSummary summary =
        new AutomationAdmissionStateServiceImpl(repository, historyRepository)
            .findState("tenant-1", "game-1", "region-1")
            .orElseThrow();

    assertThat(summary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(summary.admissionEpoch()).isEqualTo(4L);
    assertThat(summary.controlPlaneRequestId()).isEqualTo("request-4");
    assertThat(summary.targetMode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(summary.outcome()).isEqualTo("APPLIED");
    assertThat(summary.requestFingerprint()).isEqualTo("fingerprint-4");
    assertThat(summary.acknowledgedAtMs()).isEqualTo(400L);
  }

  @Test
  void findStateDoesNotExposeAcknowledgementWhenHistoryEpochDiffers() {
    AutomationAdmissionStateService.AdmissionStateSummary summary =
        findStateWithHistory(3L, "fingerprint-4", "APPLIED");

    assertDiagnosticAdmissionState(summary);
  }

  @Test
  void findStateDoesNotExposeAcknowledgementWhenHistoryFingerprintDiffers() {
    AutomationAdmissionStateService.AdmissionStateSummary summary =
        findStateWithHistory(4L, "different-fingerprint", "APPLIED");

    assertDiagnosticAdmissionState(summary);
  }

  @Test
  void findStateDoesNotExposeAcknowledgementForNonSuccessHistoryOutcome() {
    AutomationAdmissionStateService.AdmissionStateSummary summary =
        findStateWithHistory(4L, "fingerprint-4", "REJECTED");

    assertDiagnosticAdmissionState(summary);
  }

  private static AutomationAdmissionStateService.AdmissionStateSummary findStateWithHistory(
      long historyEpoch, String historyFingerprint, String historyOutcome) {
    AutomationAdmissionStateRepository repository =
        Mockito.mock(AutomationAdmissionStateRepository.class);
    AutomationAdmissionRequestHistoryRepository historyRepository =
        Mockito.mock(AutomationAdmissionRequestHistoryRepository.class);
    AutomationAdmissionState state = state("tenant-1", "game-1", "region-1");
    state.setMode("PAUSED_FOR_ROLLBACK");
    state.setAdmissionEpoch(4L);
    state.setControlPlaneRequestId("request-4");
    state.setControlPlaneRequestFingerprint("fingerprint-4");
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setTenantId("tenant-1");
    history.setGameInstanceId("game-1");
    history.setRegionId("region-1");
    history.setMode("PAUSED_FOR_ROLLBACK");
    history.setControlPlaneRequestId("request-4");
    history.setRequestFingerprint(historyFingerprint);
    history.setAdmissionEpoch(historyEpoch);
    history.setOutcome(historyOutcome);
    history.setCreatedAt(Instant.ofEpochMilli(400L));
    when(repository.findByTenantIdAndGameInstanceIdAndRegionId("tenant-1", "game-1", "region-1"))
        .thenReturn(Optional.of(state));
    when(historyRepository.find(
            "tenant-1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", "request-4"))
        .thenReturn(Optional.of(history));

    return new AutomationAdmissionStateServiceImpl(repository, historyRepository)
        .findState("tenant-1", "game-1", "region-1")
        .orElseThrow();
  }

  private static void assertDiagnosticAdmissionState(
      AutomationAdmissionStateService.AdmissionStateSummary summary) {
    assertThat(summary.mode()).isEqualTo("PAUSED_FOR_ROLLBACK");
    assertThat(summary.admissionEpoch()).isEqualTo(4L);
    assertThat(summary.controlPlaneRequestId()).isEmpty();
    assertThat(summary.targetMode()).isEmpty();
    assertThat(summary.outcome())
        .isEqualTo(AutomationAdmissionStateService.OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE);
    assertThat(summary.requestFingerprint()).isEmpty();
    assertThat(summary.acknowledgedAtMs()).isZero();
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
