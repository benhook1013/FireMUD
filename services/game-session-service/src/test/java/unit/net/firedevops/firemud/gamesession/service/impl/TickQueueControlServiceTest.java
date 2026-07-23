package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TickQueueControlServiceTest {
  private RedisTemplate<String, Object> redisTemplate;
  private StringRedisTemplate lockRedisTemplate;
  private ListOperations<String, Object> listOps;
  private ValueOperations<String, Object> valueOps;
  private ValueOperations<String, String> lockValueOps;
  private GameInstanceRepository gameInstanceRepository;
  private GameplayCommandRepository gameplayCommandRepository;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private SessionAuthenticationService sessionAuthenticationService;
  private TickQueueControlService service;
  private Logger logger;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    lockRedisTemplate = mock(StringRedisTemplate.class);
    listOps = mock(ListOperations.class);
    valueOps = mock(ValueOperations.class);
    lockValueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(lockRedisTemplate.opsForValue()).thenReturn(lockValueOps);
    when(lockRedisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(1L);
    when(lockValueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
        .thenReturn(true);
    gameInstanceRepository = mock(GameInstanceRepository.class);
    gameplayCommandRepository = mock(GameplayCommandRepository.class);
    AtomicLong commandIds = new AtomicLong();
    when(gameplayCommandRepository.save(any()))
        .thenAnswer(
            invocation -> {
              GameplayCommand command = invocation.getArgument(0);
              if (command.getId() == null) {
                command.setId(commandIds.incrementAndGet());
              }
              return command;
            });
    when(gameplayCommandRepository.markAcceptedCommandStaged(any(), any())).thenReturn(true);
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    when(runtimeRegionStatusRepository.ensureBaseline(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtimeRegionStatusRepository.claimObservedOwnership(
            any(), any(String.class), any(String.class), any(String.class), any(Instant.class)))
        .thenAnswer(
            invocation -> {
              RuntimeRegionStatus status = invocation.getArgument(0);
              status.setOwnerService(invocation.getArgument(1));
              status.setOwnerInstanceId(invocation.getArgument(2));
              status.setExecutorFence(invocation.getArgument(3));
              status.setUpdatedAt(invocation.getArgument(4));
              return status;
            });
    when(runtimeRegionStatusRepository.advanceOwnershipEpoch(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    sessionAuthenticationService = mock(SessionAuthenticationService.class);
    RuntimeIdentity runtimeIdentity =
        new RuntimeIdentity(
            "game-session-service",
            "test-instance",
            "test-host",
            Instant.parse("2026-04-19T00:00:00Z"),
            null,
            null,
            null);
    service =
        new TickQueueControlService(
            redisTemplate,
            lockRedisTemplate,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            runtimeIdentity,
            sessionAuthenticationService,
            mock(java.util.concurrent.ScheduledExecutorService.class));
    logger = mock(Logger.class);
  }

  @Test
  void enqueueCommandPushesToQueue() {
    service.enqueueCommand(1L, 2L, "cmd-123", "look", false);

    verify(listOps).rightPush("gamesession:tick:queue:1:2", "N|cmd-123|look");
    verify(gameplayCommandRepository).markAcceptedCommandStaged(any(), any());
  }

  @Test
  void leaseRenewalUsesJavaTtlValue() {
    service.enqueueCommand(1L, 2L, "cmd-ttl", "look", false);

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(lockRedisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(any(), org.mockito.ArgumentMatchers.<String>anyList(), arguments.capture());
    assertTrue(
        arguments.getAllValues().stream()
            .anyMatch(values -> values.length == 2 && "30000".equals(values[1])));
  }

  @Test
  void enqueueCommandRemovesPayloadWhenDurableTransitionIsRejected() {
    when(gameplayCommandRepository.markAcceptedCommandStaged(eq("cmd-terminal"), any()))
        .thenReturn(false);

    assertThrows(
        TickQueueControlService.QueueUnavailableException.class,
        () -> service.enqueueCommand(1L, 2L, "cmd-terminal", "look", false));

    verify(listOps).remove("gamesession:tick:queue:1:2", -1, "N|cmd-terminal|look");
  }

  @Test
  void enqueueCommandRemovesPayloadWhenTransactionRollsBack() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.enqueueCommand(1L, 2L, "cmd-rollback", "look", false);
      verify(listOps, never()).remove(any(), anyLong(), any());

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

      verify(listOps).remove("gamesession:tick:queue:1:2", -1, "N|cmd-rollback|look");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void enqueueCommandFailsClosedWhenQueueLeaseIsLostBeforeCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.enqueueCommand(1L, 2L, "cmd-lease-loss", "look", false);
      when(lockRedisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(0L);

      assertThrows(
          TickQueueControlService.QueueUnavailableException.class,
          () ->
              TransactionSynchronizationManager.getSynchronizations()
                  .forEach(synchronization -> synchronization.beforeCommit(false)));
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

      verify(listOps).remove("gamesession:tick:queue:1:2", -1, "N|cmd-lease-loss|look");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void purgeQueuedAutomationCommandsForScriptPatchRejectsBlankReasonBeforeRepositoryRead() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.purgeQueuedAutomationCommandsForScriptPatch(
                    1L, 2L, "region-1", "patch-1", "   ", logger));

    assertEquals("reason is required", exception.getMessage());
    verifyNoInteractions(gameplayCommandRepository, listOps);
  }

  @Test
  void purgeQueuedAutomationCommandsForScriptPatchRemovesRedisPayloadAndMarksTerminal() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say hello");
    command.setRequiresSoloTick(false);
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));
    when(listOps.remove("gamesession:tick:queue:1:2", 0, "N|cmd-1|say hello")).thenReturn(1L);

    long purged =
        service.purgeQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1", "rollback", logger);

    assertEquals(1L, purged);
    verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-1|say hello");
    assertEquals("LOST_BEFORE_STAGING", command.getExecutionOutcome());
    assertEquals("NOT_APPLIED", command.getGameplayResult());
    assertEquals(TickQueueControlService.PURGED_FAILURE_CODE, command.getFailureCode());
    assertEquals("rollback", command.getFailureMessage());
    assertNotNull(command.getCompletedAt());
    verify(gameplayCommandRepository).saveAll(List.of(command));
  }

  @Test
  void purgeQueuedAutomationCommandsForPluginVersionRejectsBlankReasonBeforeRepositoryRead() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.purgeQueuedAutomationCommandsForPluginVersion(
                    1L, 2L, "region-1", "plugin-1", "plugin-v1", "   ", logger));

    assertEquals("reason is required", exception.getMessage());
    verifyNoInteractions(gameplayCommandRepository, listOps);
  }

  @Test
  void purgeQueuedAutomationCommandsForPluginVersionUsesPluginProvenance() {
    GameplayCommand command = gameplayCommand("cmd-2");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setCommandText("emote waves");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForPluginVersion(
            1L, 2L, "", "plugin-1", "plugin-v1"))
        .thenReturn(List.of(command));
    when(listOps.remove("gamesession:tick:queue:1:2", 0, "N|cmd-2|emote waves")).thenReturn(1L);

    long purged =
        service.purgeQueuedAutomationCommandsForPluginVersion(
            1L, 2L, "", "plugin-1", "plugin-v1", "plugin rollback", logger);

    assertEquals(1L, purged);
    verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-2|emote waves");
    assertEquals("LOST_BEFORE_STAGING", command.getExecutionOutcome());
    assertEquals("NOT_APPLIED", command.getGameplayResult());
    assertEquals(TickQueueControlService.PURGED_FAILURE_CODE, command.getFailureCode());
    assertEquals("plugin rollback", command.getFailureMessage());
  }

  @Test
  void purgeTerminalizesDurableCommandEvenWhenRedisPayloadIsAlreadyAbsent() {
    GameplayCommand command = gameplayCommand("cmd-race");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say race");
    command.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));
    long purged =
        service.purgeQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1", "rollback", logger);

    assertEquals(1L, purged);
    assertEquals("LOST_BEFORE_STAGING", command.getExecutionOutcome());
    verify(gameplayCommandRepository).saveAll(List.of(command));
  }

  @Test
  void purgeMapsDurablyBatchBoundCommandToAbandoned() {
    GameplayCommand command = gameplayCommand("cmd-retry");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say retry");
    command.setExecutionOutcome("RETRY_QUEUED");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));
    when(gameplayCommandRepository.hasDurableTickEffect("cmd-retry")).thenReturn(true);
    when(listOps.remove("gamesession:tick:queue:1:2", 0, "N|cmd-retry|say retry")).thenReturn(1L);

    long purged =
        service.purgeQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1", "retry rollback", logger);

    assertEquals(1L, purged);
    assertEquals("ABANDONED", command.getExecutionOutcome());
  }

  @Test
  void purgeSkipsBatchBoundCommandThatIsNotExplicitlyQueuedForRetry() {
    GameplayCommand command = gameplayCommand("cmd-batch-active");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say active");
    command.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));
    when(gameplayCommandRepository.hasDurableTickEffect("cmd-batch-active")).thenReturn(true);

    long purged =
        service.purgeQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1", "rollback", logger);

    assertEquals(0L, purged);
    assertEquals("STAGED", command.getExecutionOutcome());
    verify(gameplayCommandRepository, never()).saveAll(any());
    verify(listOps, never()).remove(any(), anyLong(), any());
  }

  @Test
  void purgeFailsWhenQueueLockCannotBeAcquired() {
    when(lockValueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
        .thenReturn(false);

    Thread.currentThread().interrupt();
    try {
      assertThrows(
          TickQueueControlService.QueueUnavailableException.class,
          () ->
              service.purgeQueuedAutomationCommandsForScriptPatch(
                  1L, 2L, "region-1", "patch-1", "rollback", logger));
      verify(gameplayCommandRepository, never())
          .findQueuedAutomationCommandsForScriptPatch(any(), any(), any(), any());
      verify(listOps, never()).remove(any(), anyLong(), any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void purgeDoesNotRemoveQueuePayloadWhenDurableTerminalizationFails() {
    GameplayCommand command = gameplayCommand("cmd-save-failure");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say recover");
    command.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));
    doThrow(new IllegalStateException("database unavailable"))
        .when(gameplayCommandRepository)
        .saveAll(any());

    assertThrows(
        IllegalStateException.class,
        () ->
            service.purgeQueuedAutomationCommandsForScriptPatch(
                1L, 2L, "region-1", "patch-1", "rollback", logger));

    verify(listOps, never()).remove(any(), anyLong(), any());
    verify(listOps, never()).leftPush(any(), any());
  }

  @Test
  void purgeRemovesQueuePayloadOnlyAfterTransactionCommit() {
    GameplayCommand command = gameplayCommand("cmd-rollback");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say recover");
    command.setExecutionOutcome("STAGED");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));
    TransactionSynchronizationManager.initSynchronization();
    try {
      long purged =
          service.purgeQueuedAutomationCommandsForScriptPatch(
              1L, 2L, "region-1", "patch-1", "rollback", logger);

      assertEquals(1L, purged);
      assertEquals("LOST_BEFORE_STAGING", command.getExecutionOutcome());
      verify(listOps, never()).remove(any(), anyLong(), any());

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);

      verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-rollback|say recover");
      verify(listOps).remove("gamesession:tick:pending:1:2", 0, "N|cmd-rollback|say recover");
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void pauseAndResumeTicksUpdateGlobalTickStatus() {
    assertEquals(TickStatus.TICK_STATUS_RUNNING, service.getTickStatus());

    service.pauseTicks("maintenance", logger);
    assertEquals(TickStatus.TICK_STATUS_PAUSED, service.getTickStatus());

    service.resumeTicks("resume", logger);
    assertEquals(TickStatus.TICK_STATUS_RUNNING, service.getTickStatus());
  }

  @Test
  void pauseAndResumeTicksForGameInstanceBumpOwnershipEpochAndPauseFlag() {
    GameInstance instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(9L);
    when(gameInstanceRepository.findById(2L)).thenReturn(Optional.of(instance));
    RuntimeRegionStatus existingForPause = runtimeOwnership(9L, 2L, 4L, "fence-a", false);
    RuntimeRegionStatus existingForResume = runtimeOwnership(9L, 2L, 5L, "fence-b", true);
    when(runtimeRegionStatusRepository.advanceOwnershipEpoch(any()))
        .thenReturn(existingForPause, existingForResume);

    service.pauseTicksForGameInstance(2L, "maintenance", logger);
    assertTrue(service.isPaused(2L, false));

    service.resumeTicksForGameInstance(2L, "resume", logger);
    assertFalse(service.isPaused(2L, false));

    ArgumentCaptor<RuntimeRegionStatus> statusCaptor =
        ArgumentCaptor.forClass(RuntimeRegionStatus.class);
    verify(runtimeRegionStatusRepository, org.mockito.Mockito.atLeast(2))
        .advanceOwnershipEpoch(statusCaptor.capture());
    List<RuntimeRegionStatus> savedStatuses = statusCaptor.getAllValues();
    assertTrue(savedStatuses.stream().anyMatch(RuntimeRegionStatus::isPaused));
    assertTrue(savedStatuses.stream().anyMatch(status -> !status.isPaused()));
    assertTrue(
        savedStatuses.stream()
            .allMatch(status -> "test-instance".equals(status.getOwnerInstanceId())));
  }

  @Test
  void queryStateUsesSessionContextTenantFromSessionAuthority() {
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("7"))
        .thenReturn(Optional.of(new SessionContext(7L, 11L, 0L, 0L, 0L, null)));
    when(valueOps.get("session:11:7")).thenReturn("{\"status\":\"ready\"}");

    String state = service.queryState(7L);

    assertEquals("{\"status\":\"ready\"}", state);
  }

  @Test
  void queryStateFailsClosedWhenSessionContextMissing() {
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("7"))
        .thenReturn(Optional.empty());

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.queryState(7L));

    assertEquals("No session context found for sessionId=7", exception.getMessage());
  }

  @Test
  void queryStateFailsClosedWhenSessionContextHasNoTenantAuthority() {
    when(sessionAuthenticationService.resolveUnverifiedSessionContext("7"))
        .thenReturn(Optional.of(new SessionContext(7L, 0L, 0L, 0L, 0L, null)));

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> service.queryState(7L));

    assertEquals("No session context found for sessionId=7", exception.getMessage());
  }

  @Test
  void claimOwnershipCreatesDefaultRuntimeRowWithRuntimeIdentityAfterLeaseAcquisition() {
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.empty());

    TickQueueControlService.OwnershipSnapshot snapshot;
    String leaseToken;
    try (TickQueueControlService.QueueLockLease lease =
        service.tryAcquireTickLease(1L, 2L, "test ownership claim", logger).orElseThrow()) {
      leaseToken = lease.token();
      snapshot = service.claimOwnership(1L, 2L, lease);
    }

    assertEquals("2", snapshot.regionId());
    assertEquals(1L, snapshot.regionEpoch());
    assertEquals(leaseToken, snapshot.executorFence());
    ArgumentCaptor<RuntimeRegionStatus> statusCaptor =
        ArgumentCaptor.forClass(RuntimeRegionStatus.class);
    verify(runtimeRegionStatusRepository).ensureBaseline(statusCaptor.capture());
    verify(runtimeRegionStatusRepository)
        .claimObservedOwnership(
            statusCaptor.getValue(),
            "game-session-service",
            "test-instance",
            statusCaptor.getValue().getExecutorFence(),
            statusCaptor.getValue().getUpdatedAt());
    assertEquals("game-session-service", statusCaptor.getValue().getOwnerService());
    assertEquals("test-instance", statusCaptor.getValue().getOwnerInstanceId());
  }

  @Test
  void claimOwnershipRotatesExecutorFenceForEveryNewLeaseGeneration() {
    RuntimeRegionStatus previous = runtimeOwnership(1L, 2L, 3L, "fence-previous", false);
    previous.setOwnerService("game-session-service");
    previous.setOwnerInstanceId("test-instance");
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(previous));

    TickQueueControlService.OwnershipSnapshot snapshot;
    try (TickQueueControlService.QueueLockLease lease =
        service.tryAcquireTickLease(1L, 2L, "test ownership takeover", logger).orElseThrow()) {
      snapshot = service.claimOwnership(1L, 2L, lease);
    }

    assertNotEquals("fence-previous", snapshot.executorFence());
    verify(runtimeRegionStatusRepository)
        .claimObservedOwnership(
            previous,
            "game-session-service",
            "test-instance",
            snapshot.executorFence(),
            previous.getUpdatedAt());
  }

  @Test
  void requireRuntimeOwnershipPrefersRegionScopedRowWhenPresent() {
    RuntimeRegionStatus regionScoped = runtimeOwnership(1L, 2L, 3L, "fence-a", false);
    regionScoped.setRegionId("region-alpha");
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(regionScoped));

    RuntimeRegionStatus resolved = service.requireRuntimeOwnership(1L, 2L, "region-alpha");

    assertEquals("region-alpha", resolved.getRegionId());
    verify(runtimeRegionStatusRepository).findByTenantIdAndRegionId(1L, "region-alpha");
  }

  @Test
  void requireRuntimeOwnershipRejectsRegionScopedRowForDifferentGameInstance() {
    RuntimeRegionStatus regionScoped = runtimeOwnership(1L, 99L, 3L, "fence-a", false);
    regionScoped.setRegionId("region-alpha");
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.of(regionScoped));

    TickQueueControlService.StaleOwnershipException exception =
        assertThrows(
            TickQueueControlService.StaleOwnershipException.class,
            () -> service.requireRuntimeOwnership(1L, 2L, "region-alpha"));

    assertEquals("regionId region-alpha does not match gameInstanceId 2", exception.getMessage());
  }

  @Test
  void requireRuntimeOwnershipThrowsWhenNoOwnershipRowExists() {
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-alpha"))
        .thenReturn(Optional.empty());
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.empty());

    TickQueueControlService.StaleOwnershipException exception =
        assertThrows(
            TickQueueControlService.StaleOwnershipException.class,
            () -> service.requireRuntimeOwnership(1L, 2L, "region-alpha"));

    assertTrue(exception.getMessage().contains("tenantId=1"));
    assertTrue(exception.getMessage().contains("gameInstanceId=2"));
  }

  private GameplayCommand gameplayCommand(String commandId) {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setCommandText("look");
    command.setRequiresSoloTick(false);
    return command;
  }

  private RuntimeRegionStatus runtimeOwnership(
      long tenantId, long gameInstanceId, long regionEpoch, String executorFence, boolean paused) {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(tenantId);
    status.setGameInstanceId(gameInstanceId);
    status.setRegionId(Long.toString(gameInstanceId));
    status.setRegionEpoch(regionEpoch);
    status.setExecutorFence(executorFence != null ? executorFence : "fence-" + UUID.randomUUID());
    status.setPaused(paused);
    return status;
  }
}
