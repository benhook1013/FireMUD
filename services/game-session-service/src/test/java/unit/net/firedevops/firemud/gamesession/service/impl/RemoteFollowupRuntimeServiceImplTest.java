package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupResultRepository;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

class RemoteFollowupRuntimeServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");

  private RemoteCommandCoordinatorRepository coordinatorRepository;
  private RemoteFollowupRepository followupRepository;
  private RemoteFollowupResultRepository resultRepository;
  private GameplayCommandRepository gameplayCommandRepository;
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ValueOperations<String, Object> valueOperations;
  private RemoteFollowupRuntimeService service;

  @BeforeEach
  void setup() {
    coordinatorRepository = mock(RemoteCommandCoordinatorRepository.class);
    followupRepository = mock(RemoteFollowupRepository.class);
    resultRepository = mock(RemoteFollowupResultRepository.class);
    gameplayCommandRepository = mock(GameplayCommandRepository.class);
    redisTemplate = mock(RedisTemplate.class);
    valueOperations = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(coordinatorRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(followupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(gameplayCommandRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    service =
        new RemoteFollowupRuntimeServiceImpl(
            coordinatorRepository,
            followupRepository,
            resultRepository,
            gameplayCommandRepository,
            redisTemplate,
            meterRegistry.counter("gamesession_remote_followup_scheduled_total"),
            meterRegistry.counter("gamesession_remote_followup_timeout_total"),
            meterRegistry.counter(
                "gamesession_remote_followup_late_result_total", "policy", "ignored"),
            meterRegistry.counter(
                "gamesession_remote_followup_late_result_total", "policy", "reconciled"),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void scheduleFollowupCreatesCoordinatorAndFollowup() {
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.empty());
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.empty());
    GameplayCommand command = gameplayCommand();
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        service.scheduleFollowup(scheduleRequest());

    assertTrue(outcome.coordinatorCreated());
    assertTrue(outcome.followupCreated());
    assertEquals("coord-1", outcome.coordinatorId());
    assertEquals("followup-1", outcome.followupId());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COMMAND_PENDING_REMOTE, command.getExecutionOutcome());
    assertEquals("PENDING", command.getGameplayResult());
    verify(valueOperations).set("remote:1:entity-9", "1", java.time.Duration.ofMillis(60_000L));
  }

  @Test
  void scheduleFollowupRejectsConflictingCoordinatorIdentityReuse() {
    RemoteCommandCoordinator existing = coordinator();
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.of(existing));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
                        1L,
                        "cmd-1",
                        "coord-2",
                        7L,
                        "region-a",
                        4L,
                        8L,
                        "region-b",
                        8L,
                        22L,
                        4L,
                        25L,
                        "late_result_safe_to_ignore",
                        "followup-1",
                        "effect-1",
                        "entity-9",
                        "{\"type\":\"remote\"}")));

    assertEquals("command_id already maps to a different coordinator_id", ex.getMessage());
    verify(followupRepository, never())
        .findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            anyLong(), anyString(), anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsConflictingFollowupScopeReuse() {
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.empty());
    RemoteFollowup existing = followup();
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.of(existing));
    GameplayCommand command = gameplayCommand();
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
                        1L,
                        "cmd-1",
                        "coord-1",
                        99L,
                        "region-a",
                        4L,
                        8L,
                        "region-b",
                        8L,
                        22L,
                        4L,
                        25L,
                        "late_result_safe_to_ignore",
                        "followup-1",
                        "effect-1",
                        "entity-9",
                        "{\"type\":\"remote\"}")));

    assertEquals("effect_key already maps to a different remote execution scope", ex.getMessage());
  }

  @Test
  void recordResultPersistsTerminalTargetOutcomeWithoutMutatingPendingCoordinator() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowup followup = followup();
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ResultOutcome outcome =
        service.recordResult(resultRequest("APPLIED"));

    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, outcome.coordinatorState());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED, outcome.followupStatus());
    assertFalse(outcome.lateResult());
    assertFalse(outcome.reconciledLateResult());
    verify(coordinatorRepository, never()).save(any());
  }

  @Test
  void recordLateResultLeavesTimedOutCoordinatorForLaterOriginReconciliation() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED);
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    RemoteFollowup followup = followup();
    followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED);
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ResultOutcome outcome =
        service.recordResult(resultRequest("APPLIED"));

    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED,
        outcome.coordinatorState());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED, outcome.followupStatus());
    assertTrue(outcome.lateResult());
    assertFalse(outcome.reconciledLateResult());
    verify(coordinatorRepository, never()).save(any());
  }

  @Test
  void recordResultRejectsMismatchedTargetScope() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowup followup = followup();
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.recordResult(
                    new RemoteFollowupRuntimeService.ResultRequest(
                        1L,
                        "result-1",
                        "coord-1",
                        "followup-1",
                        "region-a",
                        4L,
                        "region-wrong",
                        8L,
                        "APPLIED",
                        "{\"status\":\"done\"}")));

    assertEquals("target scope does not match remote coordinator", ex.getMessage());
    verify(resultRepository, never()).save(any());
  }

  @Test
  void recordResultRejectsConflictingExistingResultIdReplay() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowup followup = followup();
    RemoteFollowupResult existing = new RemoteFollowupResult();
    existing.setId(99L);
    existing.setResultId("result-1");
    existing.setTenantId(1L);
    existing.setCoordinatorId("coord-1");
    existing.setFollowupId("followup-1");
    existing.setOriginRegionId("region-a");
    existing.setOriginRegionEpoch(4L);
    existing.setTargetRegionId("region-b");
    existing.setTargetRegionEpoch(8L);
    existing.setOutcome("APPLIED");
    existing.setResultPayloadJson("{\"status\":\"done\"}");
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1"))
        .thenReturn(Optional.of(existing));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.recordResult(
                    new RemoteFollowupRuntimeService.ResultRequest(
                        1L,
                        "result-1",
                        "coord-1",
                        "followup-1",
                        "region-a",
                        4L,
                        "region-b",
                        8L,
                        "ABANDONED",
                        "{\"status\":\"failed\"}")));

    assertEquals("result_id already records a different remote outcome", ex.getMessage());
  }

  @Test
  void recordResultReusesExistingResultIdWithoutRewritingObservedAt() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowup followup = followup();
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    RemoteFollowupResult existing = new RemoteFollowupResult();
    existing.setId(99L);
    existing.setResultId("result-1");
    existing.setTenantId(1L);
    existing.setCoordinatorId("coord-1");
    existing.setFollowupId("followup-1");
    existing.setOriginRegionId("region-a");
    existing.setOriginRegionEpoch(4L);
    existing.setTargetRegionId("region-b");
    existing.setTargetRegionEpoch(8L);
    existing.setOutcome("APPLIED");
    existing.setResultPayloadJson("{\"status\":\"done\"}");
    existing.setObservedAt(Instant.parse("2026-05-01T00:00:05Z"));
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1"))
        .thenReturn(Optional.of(existing));

    RemoteFollowupRuntimeService.ResultOutcome outcome =
        service.recordResult(resultRequest("APPLIED"));

    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, outcome.coordinatorState());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED, outcome.followupStatus());
    assertEquals(Instant.parse("2026-05-01T00:00:05Z"), existing.getObservedAt());
    verify(resultRepository, never()).save(any());
  }

  @Test
  void reconcileResultsAppliesPendingCoordinatorFromDurableInbox() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.COMMAND_PENDING_REMOTE);
    coordinator.setGameplayResult("PENDING");
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    GameplayCommand command = gameplayCommand();
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(1, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_APPLIED, coordinator.getState());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED, coordinator.getExecutionOutcome());
    assertEquals("APPLIED", coordinator.getGameplayResult());
    assertEquals("APPLIED", command.getExecutionOutcome());
    assertEquals("APPLIED", command.getGameplayResult());
  }

  @Test
  void reconcileResultsMarksLateResultIgnoredAfterTimeoutByDefault() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED);
    coordinator.setGameplayResult("TIMEOUT");
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    GameplayCommand command = gameplayCommand();
    command.setFailureCode(RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED);
    command.setFailureMessage("Cross-region remote followup did not apply successfully");
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of());
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of(coordinator));
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(1, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_LATE_RESULT_IGNORED, coordinator.getState());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED, command.getExecutionOutcome());
    assertEquals("TIMEOUT", command.getGameplayResult());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED,
        command.getFailureCode());
  }

  @Test
  void reconcileResultsMarksLateResultReconciledAsPartialCommandSuccess() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED);
    coordinator.setGameplayResult("TIMEOUT");
    coordinator.setLateResultPolicy(
        RemoteFollowupRuntimeServiceImpl.LATE_RESULT_REQUIRES_RECONCILIATION);
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    GameplayCommand command = gameplayCommand();
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of());
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of(coordinator));
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(1, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_LATE_RESULT_RECONCILED,
        coordinator.getState());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED, command.getExecutionOutcome());
    assertEquals("PARTIAL", command.getGameplayResult());
  }

  @Test
  void reconcileResultsSkipsPendingCoordinatorFromStaleOriginEpoch() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setOriginRegionEpoch(3L);
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(0, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, coordinator.getState());
  }

  @Test
  void reconcileTimeoutsMarksOverduePendingCoordinatorWithoutMutatingTargetFollowup() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setOriginDeadlineRegionEpoch(4L);
    coordinator.setOriginDeadlineTickId(12L);
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.COMMAND_PENDING_REMOTE);
    coordinator.setGameplayResult("PENDING");
    GameplayCommand command = gameplayCommand();
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));

    int updated = service.reconcileTimeouts(1L, "region-a", 4L, 12L);

    assertEquals(1, updated);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED,
        coordinator.getState());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED, command.getExecutionOutcome());
    assertEquals("TIMEOUT", command.getGameplayResult());
    verify(followupRepository, never()).findByTenantIdAndFollowupId(any(), any());
  }

  @Test
  void abandonFollowupClearsClaimAndMarksTerminalFailure() {
    RemoteFollowup followup = followup();
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));

    service.abandonFollowup(1L, "followup-1", "REMOTE_COORDINATOR_NOT_FOUND", "missing");

    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED, followup.getStatus());
    assertEquals(null, followup.getClaimedTickBatchId());
    assertEquals("REMOTE_COORDINATOR_NOT_FOUND", followup.getFailureCode());
    assertEquals("missing", followup.getFailureMessage());
  }

  private static RemoteFollowupRuntimeService.ScheduleRequest scheduleRequest() {
    return new RemoteFollowupRuntimeService.ScheduleRequest(
        1L,
        "cmd-1",
        "coord-1",
        7L,
        "region-a",
        4L,
        8L,
        "region-b",
        8L,
        22L,
        4L,
        25L,
        "late_result_safe_to_ignore",
        "followup-1",
        "effect-1",
        "entity-9",
        "{\"type\":\"remote\"}");
  }

  private static RemoteFollowupRuntimeService.ResultRequest resultRequest(String outcome) {
    return new RemoteFollowupRuntimeService.ResultRequest(
        1L,
        "result-1",
        "coord-1",
        "followup-1",
        "region-a",
        4L,
        "region-b",
        8L,
        outcome,
        "{\"status\":\"done\"}");
  }

  private static RemoteCommandCoordinator coordinator() {
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setCommandId("cmd-1");
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setTargetGameInstanceId(8L);
    coordinator.setTargetRegionId("region-b");
    coordinator.setTargetRegionEpoch(8L);
    coordinator.setTargetDueTickId(22L);
    coordinator.setOriginDeadlineRegionEpoch(4L);
    coordinator.setOriginDeadlineTickId(25L);
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    coordinator.setUpdatedAt(NOW);
    return coordinator;
  }

  private static RemoteFollowup followup() {
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setOriginGameInstanceId(7L);
    followup.setOriginRegionId("region-a");
    followup.setOriginRegionEpoch(4L);
    followup.setTargetGameInstanceId(8L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setEffectKey("effect-1");
    followup.setDueTickId(22L);
    followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED);
    followup.setCreatedAt(NOW);
    followup.setUpdatedAt(NOW);
    return followup;
  }

  private static GameplayCommand gameplayCommand() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-1");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(0L);
    command.setCommandName("LOOK");
    command.setCommandText("LOOK");
    command.setSanitizedCommandText("LOOK");
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(NOW);
    command.setLastAttemptAt(NOW);
    command.setAttemptCount(1);
    return command;
  }
}
