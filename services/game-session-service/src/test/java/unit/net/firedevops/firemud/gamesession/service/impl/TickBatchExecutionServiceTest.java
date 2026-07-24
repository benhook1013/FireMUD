package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.repository.TickBatchRepository;
import net.firedevops.firemud.gamesession.repository.TickEffectRepository;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class TickBatchExecutionServiceTest {
  private SimpleMeterRegistry meterRegistry;
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private GameplayCommandRepository gameplayCommandRepository;
  private TickBatchRepository tickBatchRepository;
  private TickEffectRepository tickEffectRepository;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private RemoteFollowupDrainService remoteFollowupDrainService;
  private DurableGameplayCommandExecutionService durableGameplayCommandExecutionService;
  private DurableRemoteFollowupExecutionService durableRemoteFollowupExecutionService;
  private GameplayCommandExecutionFenceService gameplayCommandExecutionFenceService;
  private TickBatchExecutionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    meterRegistry = new SimpleMeterRegistry();
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(ListOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
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
    tickBatchRepository = mock(TickBatchRepository.class);
    when(tickBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    tickEffectRepository = mock(TickEffectRepository.class);
    when(tickEffectRepository.findByTickBatchId(any())).thenReturn(List.of());
    when(tickEffectRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    when(runtimeRegionStatusRepository.commitDrainedBatch(any(), any()))
        .thenAnswer(
            invocation -> {
              RuntimeRegionStatus expected = invocation.getArgument(0);
              expected.setLastCommittedTickBatchId(invocation.getArgument(1));
              return Optional.of(expected);
            });
    remoteFollowupDrainService = mock(RemoteFollowupDrainService.class);
    durableGameplayCommandExecutionService = mock(DurableGameplayCommandExecutionService.class);
    durableRemoteFollowupExecutionService = mock(DurableRemoteFollowupExecutionService.class);
    gameplayCommandExecutionFenceService = mock(GameplayCommandExecutionFenceService.class);
    when(gameplayCommandExecutionFenceService.validate(any(), any())).thenReturn(Optional.empty());
    service = newService(new ImmediateTransactionOperations());

    RuntimeRegionStatus currentOwnership = runtimeOwnership(1L, 2L, 1L, "fence-a", false);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(currentOwnership));
  }

  @Test
  void markBatchDrainedPersistsOwnershipAndGameplayCommandState() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-1");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setExecutorFence("fence-a");
    batch.setCommandCount(1);
    TickQueuedCommandEnvelope entry = new TickQueuedCommandEnvelope(false, "cmd-1", "look");
    GameplayCommand command = gameplayCommand("cmd-1");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    service.markBatchDrained(batch, List.of(entry));

    assertEquals("DRAINED", batch.getStatus());
    assertEquals("DRAINED", command.getExecutionOutcome());
    assertEquals("PENDING", command.getGameplayResult());
    assertEquals("GAMEPLAY_COMMAND", command.getQueueSourceKind());
    assertEquals("REDIS_PENDING_CLAIMED", command.getQueueSourceState());
    assertEquals(1L, command.getQueueSourceOrdinal());
  }

  @Test
  void markBatchDrainedPreservesTimerSourceTupleOnGameplayCommand() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-1");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setExecutorFence("fence-a");
    batch.setCommandCount(1);
    TickQueuedCommandEnvelope entry = new TickQueuedCommandEnvelope(false, "cmd-1", "look");
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setDueTickId(14L);
    command.setOriginSourceKind("SCHEDULE_TIMER");
    command.setOriginSourceState("SCHEDULE_DUE_CLAIMED");
    command.setOriginSourceOrdinal(5000L);
    command.setOriginSourceDueAtMs(9000L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    service.markBatchDrained(batch, List.of(entry));

    assertEquals("DRAINED", command.getExecutionOutcome());
    assertEquals("SCHEDULE_TIMER", command.getQueueSourceKind());
    assertEquals("SCHEDULE_DUE_CLAIMED", command.getQueueSourceState());
    assertEquals(5000L, command.getQueueSourceOrdinal());
    assertEquals(14L, command.getQueueSourceDueTickId());
    assertEquals(9000L, command.getQueueSourceDueAtMs());
  }

  @Test
  void markBatchDrainedKeepsAllDurableWritesInsideTransactionBoundary() {
    List<String> events = new ArrayList<>();
    service =
        newService(
            new TransactionOperations() {
              @Override
              public <T> T execute(TransactionCallback<T> action) {
                events.add("transaction-begin");
                T result = action.doInTransaction(new SimpleTransactionStatus());
                events.add("transaction-commit");
                return result;
              }
            });
    TickBatch batch = drainedBatch("tb-atomic", "fence-a");
    TickEffect effect = drainedEffect("tb-atomic", "cmd-atomic");
    GameplayCommand command = gameplayCommand("cmd-atomic");
    TickQueuedCommandEnvelope entry = new TickQueuedCommandEnvelope(false, "cmd-atomic", "look");
    when(tickEffectRepository.findByTickBatchId("tb-atomic")).thenReturn(List.of(effect));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-atomic")))
        .thenReturn(List.of(command));
    doAnswer(
            invocation -> {
              events.add("batch");
              return invocation.getArgument(0);
            })
        .when(tickBatchRepository)
        .save(any());
    doAnswer(
            invocation -> {
              events.add("effects");
              return invocation.getArgument(0);
            })
        .when(tickEffectRepository)
        .saveAll(any());
    doAnswer(
            invocation -> {
              events.add("commands");
              return invocation.getArgument(0);
            })
        .when(gameplayCommandRepository)
        .saveAll(any());
    doAnswer(
            invocation -> {
              events.add("ownership-cas");
              RuntimeRegionStatus expected = invocation.getArgument(0);
              expected.setLastCommittedTickBatchId(invocation.getArgument(1));
              return Optional.of(expected);
            })
        .when(runtimeRegionStatusRepository)
        .commitDrainedBatch(any(), any());

    service.markBatchDrained(batch, List.of(entry));

    assertEquals(
        List.of(
            "transaction-begin",
            "batch",
            "effects",
            "commands",
            "ownership-cas",
            "transaction-commit"),
        events);
  }

  @Test
  void markBatchDrainedStopsBeforeEffectWriteFailureAndOwnershipCas() {
    TickBatch batch = drainedBatch("tb-effect-failure", "fence-a");
    TickEffect effect = drainedEffect("tb-effect-failure", "cmd-effect-failure");
    when(tickEffectRepository.findByTickBatchId("tb-effect-failure")).thenReturn(List.of(effect));
    org.mockito.Mockito.doThrow(new IllegalStateException("effect write failed"))
        .when(tickEffectRepository)
        .saveAll(any());

    assertThrows(IllegalStateException.class, () -> service.markBatchDrained(batch, List.of()));

    verify(runtimeRegionStatusRepository, never()).commitDrainedBatch(any(), any());
  }

  @Test
  void markBatchDrainedRestoresCallerStateWhenTransactionFails() {
    TickBatch batch = drainedBatch("tb-state-restore", "fence-a");
    batch.setStatus("STAGED");
    Instant originalCompletedAt = Instant.parse("2026-04-19T00:00:00Z");
    batch.setCompletedAt(originalCompletedAt);
    batch.setFailureCode("ORIGINAL_FAILURE");
    batch.setFailureMessage("original failure");
    TickEffect effect = drainedEffect("tb-state-restore", "cmd-state-restore");
    when(tickEffectRepository.findByTickBatchId("tb-state-restore")).thenReturn(List.of(effect));
    doThrow(new IllegalStateException("effect write failed"))
        .when(tickEffectRepository)
        .saveAll(any());

    assertThrows(IllegalStateException.class, () -> service.markBatchDrained(batch, List.of()));

    assertEquals("STAGED", batch.getStatus());
    assertEquals(originalCompletedAt, batch.getCompletedAt());
    assertEquals("ORIGINAL_FAILURE", batch.getFailureCode());
    assertEquals("original failure", batch.getFailureMessage());
  }

  @Test
  void markBatchDrainedStopsBeforeLaterWritesWhenBatchWriteFails() {
    TickBatch batch = drainedBatch("tb-batch-failure", "fence-a");
    org.mockito.Mockito.doThrow(new IllegalStateException("batch write failed"))
        .when(tickBatchRepository)
        .save(any());

    assertThrows(IllegalStateException.class, () -> service.markBatchDrained(batch, List.of()));

    verify(tickEffectRepository, never()).saveAll(any());
    verify(gameplayCommandRepository, never()).saveAll(any());
    verify(runtimeRegionStatusRepository, never()).commitDrainedBatch(any(), any());
  }

  @Test
  void markBatchDrainedStopsBeforeCommandWriteFailureAndOwnershipCas() {
    TickBatch batch = drainedBatch("tb-command-failure", "fence-a");
    GameplayCommand command = gameplayCommand("cmd-command-failure");
    TickQueuedCommandEnvelope entry =
        new TickQueuedCommandEnvelope(false, "cmd-command-failure", "look");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-command-failure")))
        .thenReturn(List.of(command));
    org.mockito.Mockito.doThrow(new IllegalStateException("command write failed"))
        .when(gameplayCommandRepository)
        .saveAll(any());

    assertThrows(
        IllegalStateException.class, () -> service.markBatchDrained(batch, List.of(entry)));

    verify(runtimeRegionStatusRepository, never()).commitDrainedBatch(any(), any());
  }

  @Test
  void markBatchDrainedPropagatesOwnershipCasFailureAfterDurableWrites() {
    TickBatch batch = drainedBatch("tb-ownership-failure", "fence-a");
    org.mockito.Mockito.doThrow(new IllegalStateException("ownership CAS failed"))
        .when(runtimeRegionStatusRepository)
        .commitDrainedBatch(any(), any());

    assertThrows(IllegalStateException.class, () -> service.markBatchDrained(batch, List.of()));

    verify(tickBatchRepository).save(batch);
  }

  @Test
  void markBatchDrainedFailsClosedWhenPauseWinsBeforeOwnershipCas() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-stale-drain");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setExecutorFence("fence-a");
    batch.setCommandCount(0);
    org.mockito.Mockito.doReturn(Optional.empty())
        .when(runtimeRegionStatusRepository)
        .commitDrainedBatch(any(), any());

    org.junit.jupiter.api.Assertions.assertThrows(
        TickQueueControlService.StaleOwnershipException.class,
        () -> service.markBatchDrained(batch, List.of()));
  }

  @Test
  void markBatchManifestMismatchMarksRetryAndIncrementsMetric() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-1");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setSelectedWorkManifestDigest("expected");
    TickQueuedCommandEnvelope entry =
        new TickQueuedCommandEnvelope(
            false,
            "cmd-1",
            "look",
            new TickQueuedCommandEnvelope.SealedQueueSource(
                "SCHEDULE_TIMER", "SCHEDULE_DUE_CLAIMED", 5000L, 14L, 9000L));
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setSourceType("AUTOMATION");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    service.markBatchManifestMismatch(batch, List.of(entry), "actual");

    assertEquals("ABANDONED", batch.getStatus());
    assertEquals("MANIFEST_MISMATCH", batch.getFailureCode());
    assertEquals("RETRY_QUEUED", command.getExecutionOutcome());
    assertEquals("GAMEPLAY_RETRY", command.getQueueSourceKind());
    assertEquals("REDIS_RETRY_QUEUED", command.getQueueSourceState());
    assertEquals(5000L, command.getQueueSourceOrdinal());
    assertEquals(14L, command.getQueueSourceDueTickId());
    assertEquals(9000L, command.getQueueSourceDueAtMs());
    assertEquals(1.0, meterRegistry.get("tick_manifest_mismatch_total").counter().count());
  }

  @Test
  void executeDurableEffectsPreservesClaimedDueTupleWhenStaleFenceRequeues() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-stale");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(0L);
    batch.setExecutorFence("fence-old");
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-stale");
    effect.setCommandId("cmd-1");
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setRegionEpoch(0L);
    command.setQueueSourceOrdinal(5000L);
    command.setQueueSourceDueTickId(14L);
    command.setQueueSourceDueAtMs(9000L);
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc("tb-stale", "DRAINED"))
        .thenReturn(List.of(effect));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    service.executeDurableEffects(1L, 2L);

    assertEquals("RETRY_QUEUED", command.getExecutionOutcome());
    assertEquals("GAMEPLAY_RETRY", command.getQueueSourceKind());
    assertEquals("REDIS_RETRY_QUEUED", command.getQueueSourceState());
    assertEquals(5000L, command.getQueueSourceOrdinal());
    assertEquals(14L, command.getQueueSourceDueTickId());
    assertEquals(9000L, command.getQueueSourceDueAtMs());
  }

  @Test
  void executeDurableEffectsTerminalizesCommandsWithNoDurableExecutionRoute() {
    TickBatch batch = drainedBatch("tb-no-durable-route", "fence-a");
    TickEffect effect = drainedEffect("tb-no-durable-route", "cmd-look");
    GameplayCommand command = gameplayCommand("cmd-look");
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(
            "tb-no-durable-route", "DRAINED"))
        .thenAnswer(
            invocation -> "DRAINED".equals(effect.getStatus()) ? List.of(effect) : List.of());
    when(gameplayCommandRepository.findByTenantIdAndGameInstanceIdAndCommandId(1L, 2L, "cmd-look"))
        .thenReturn(Optional.of(command));
    when(durableGameplayCommandExecutionService.execute(effect, command))
        .thenReturn(Optional.empty());

    service.executeDurableEffects(1L, 2L);

    assertEquals("APPLIED", effect.getStatus());
    assertEquals("APPLIED", batch.getStatus());
    assertEquals("COMPLETED", command.getExecutionOutcome());
    assertEquals("NOT_APPLIED", command.getGameplayResult());
    assertTrue(effect.getCompletedAt() != null);
    assertTrue(command.getCompletedAt() != null);
    verify(tickEffectRepository).save(effect);
    verify(gameplayCommandRepository).save(command);
  }

  @Test
  void executeDurableEffectsRequeuesAfterMidBatchStaleFenceAndContinuesNextBatch() {
    TickBatch staleBatch = drainedBatch("tb-stale-mid-batch", "fence-a");
    TickBatch subsequentBatch = drainedBatch("tb-subsequent", "fence-a");
    TickEffect appliedEffect = drainedEffect("tb-stale-mid-batch", "cmd-applied");
    TickEffect staleEffect = drainedEffect("tb-stale-mid-batch", "cmd-retry");
    TickEffect subsequentEffect = drainedEffect("tb-subsequent", "cmd-subsequent");
    GameplayCommand appliedCommand = gameplayCommand("cmd-applied");
    GameplayCommand retryCommand = gameplayCommand("cmd-retry");
    retryCommand.setCommandText("north");
    retryCommand.setQueueSourceOrdinal(5000L);
    retryCommand.setQueueSourceDueTickId(14L);
    retryCommand.setQueueSourceDueAtMs(9000L);
    GameplayCommand subsequentCommand = gameplayCommand("cmd-subsequent");
    RuntimeRegionStatus currentOwnership = runtimeOwnership(1L, 2L, 1L, "fence-a", false);
    RuntimeRegionStatus staleOwnership = runtimeOwnership(1L, 2L, 2L, "fence-b", false);

    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(staleBatch, subsequentBatch));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(
            Optional.of(currentOwnership),
            Optional.of(currentOwnership),
            Optional.of(staleOwnership),
            Optional.of(currentOwnership),
            Optional.of(currentOwnership));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(
            "tb-stale-mid-batch", "DRAINED"))
        .thenAnswer(
            invocation ->
                List.of(appliedEffect, staleEffect).stream()
                    .filter(effect -> "DRAINED".equals(effect.getStatus()))
                    .toList());
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc("tb-subsequent", "DRAINED"))
        .thenAnswer(
            invocation ->
                List.of(subsequentEffect).stream()
                    .filter(effect -> "DRAINED".equals(effect.getStatus()))
                    .toList());
    when(gameplayCommandRepository.findByTenantIdAndGameInstanceIdAndCommandId(
            1L, 2L, "cmd-applied"))
        .thenReturn(Optional.of(appliedCommand));
    when(gameplayCommandRepository.findByTenantIdAndGameInstanceIdAndCommandId(
            1L, 2L, "cmd-subsequent"))
        .thenReturn(Optional.of(subsequentCommand));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-retry")))
        .thenReturn(List.of(retryCommand));
    when(durableGameplayCommandExecutionService.execute(any(), any()))
        .thenReturn(
            Optional.of(
                new DurableGameplayCommandExecutionService.DurableGameplayCommandExecutionResult(
                    "APPLIED", "COMPLETED", "APPLIED", null, null)));

    service.executeDurableEffects(1L, 2L);

    assertEquals("ABANDONED", staleBatch.getStatus());
    assertEquals("STALE_EXECUTOR_FENCE", staleBatch.getFailureCode());
    assertEquals("APPLIED", appliedEffect.getStatus());
    assertEquals("ABANDONED", staleEffect.getStatus());
    assertEquals("STALE_EXECUTOR_FENCE", staleEffect.getFailureCode());
    assertEquals("RETRY_QUEUED", retryCommand.getExecutionOutcome());
    assertEquals("GAMEPLAY_RETRY", retryCommand.getQueueSourceKind());
    assertEquals("REDIS_RETRY_QUEUED", retryCommand.getQueueSourceState());
    assertEquals(5000L, retryCommand.getQueueSourceOrdinal());
    assertEquals(14L, retryCommand.getQueueSourceDueTickId());
    assertEquals(9000L, retryCommand.getQueueSourceDueAtMs());
    assertEquals("APPLIED", subsequentEffect.getStatus());
    assertEquals("APPLIED", subsequentBatch.getStatus());
    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-retry|north");
    verify(durableGameplayCommandExecutionService).execute(subsequentEffect, subsequentCommand);
  }

  @Test
  void executeDurableEffectsRejectsCommandWhenExecutionFenceFails() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-current");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setExecutorFence("fence-a");
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-current");
    effect.setCommandId("cmd-1");
    effect.setStatus("DRAINED");
    GameplayCommand command = gameplayCommand("cmd-1");
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc("tb-current", "DRAINED"))
        .thenReturn(List.of(effect), List.of());
    when(gameplayCommandRepository.findByTenantIdAndGameInstanceIdAndCommandId(1L, 2L, "cmd-1"))
        .thenReturn(Optional.of(command));
    when(gameplayCommandExecutionFenceService.validate(batch, command))
        .thenReturn(
            Optional.of(
                new GameplayCommandExecutionFenceService.FenceFailure(
                    "STALE_COMMAND_TIMELINE", "Command belongs to an old runtime timeline")));

    service.executeDurableEffects(1L, 2L);

    assertEquals("REJECTED", effect.getStatus());
    assertEquals("STALE_COMMAND_TIMELINE", effect.getFailureCode());
    assertEquals("COMPLETED", command.getExecutionOutcome());
    assertEquals("NOT_APPLIED", command.getGameplayResult());
    assertEquals("STALE_COMMAND_TIMELINE", command.getFailureCode());
    verify(durableGameplayCommandExecutionService, never()).execute(any(), any());
  }

  @Test
  void executeDurableEffectsNeverLoadsOrMutatesCommandOutsideBatchScope() {
    TickBatch batch = drainedBatch("tb-cross-scope", "fence-a");
    batch.setExpectedEffectCount(1);
    TickEffect effect = drainedEffect("tb-cross-scope", "cmd-foreign");
    GameplayCommand foreignCommand = gameplayCommand("cmd-foreign");
    foreignCommand.setTenantId(99L);
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    when(tickEffectRepository.findByTickBatchId("tb-cross-scope")).thenReturn(List.of(effect));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc("tb-cross-scope", "DRAINED"))
        .thenAnswer(
            invocation -> "DRAINED".equals(effect.getStatus()) ? List.of(effect) : List.of());
    when(gameplayCommandRepository.findByCommandId("cmd-foreign"))
        .thenReturn(Optional.of(foreignCommand));

    service.executeDurableEffects(1L, 2L);

    assertEquals("REJECTED", effect.getStatus());
    assertEquals("COMMAND_NOT_FOUND", effect.getFailureCode());
    assertEquals("STAGED", foreignCommand.getExecutionOutcome());
    verify(gameplayCommandRepository, times(2))
        .findByTenantIdAndGameInstanceIdAndCommandId(1L, 2L, "cmd-foreign");
    verify(gameplayCommandRepository, never()).save(foreignCommand);
    verify(durableGameplayCommandExecutionService, never()).execute(any(), any());
  }

  @Test
  void executeDurableEffectsRejectsPartialEffectSetBeforeExecutingAnything() {
    TickBatch batch = drainedBatch("tb-partial-execution", "fence-a");
    batch.setExpectedEffectCount(2);
    TickEffect effect = remoteDrainedEffect("tb-partial-execution", "followup-1");
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    when(tickEffectRepository.findByTickBatchId("tb-partial-execution"))
        .thenReturn(List.of(effect));

    assertThrows(IllegalStateException.class, () -> service.executeDurableEffects(1L, 2L));

    assertEquals("DRAINED", batch.getStatus());
    verify(durableRemoteFollowupExecutionService, never()).execute(any());
  }

  @Test
  void remoteFailureAbandonsOnlyUnfinishedEffectsAfterLaterFailure() {
    TickBatch batch = drainedBatch("tb-remote-partial", "fence-a");
    batch.setBatchSource("REMOTE_FOLLOWUP_DRAIN");
    batch.setExpectedEffectCount(2);
    TickEffect appliedEffect = remoteDrainedEffect("tb-remote-partial", "followup-1");
    TickEffect remainingEffect = remoteDrainedEffect("tb-remote-partial", "followup-2");
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    when(tickEffectRepository.findByTickBatchId("tb-remote-partial"))
        .thenReturn(List.of(appliedEffect, remainingEffect));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(
            "tb-remote-partial", "DRAINED"))
        .thenAnswer(
            invocation ->
                List.of(appliedEffect, remainingEffect).stream()
                    .filter(effect -> "DRAINED".equals(effect.getStatus()))
                    .toList());
    when(durableRemoteFollowupExecutionService.execute(appliedEffect))
        .thenReturn(
            new DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult(
                "APPLIED", null, null));
    doThrow(new IllegalStateException("later remote effect failed"))
        .when(durableRemoteFollowupExecutionService)
        .execute(remainingEffect);

    assertThrows(IllegalStateException.class, () -> service.executeDurableEffects(1L, 2L));
    service.markRemoteFollowupBatchAbandoned(batch, "REMOTE_FAILURE", "later remote effect failed");

    assertEquals("ABANDONED", batch.getStatus());
    assertEquals("APPLIED", appliedEffect.getStatus());
    assertEquals("ABANDONED", remainingEffect.getStatus());
    verify(remoteFollowupDrainService)
        .releaseClaimedFollowups(
            "tb-remote-partial", "REMOTE_FAILURE", "later remote effect failed");
  }

  @Test
  void markRemoteFollowupBatchAbandonedPreservesTerminalEffects() {
    TickBatch batch = drainedBatch("tb-remote-terminal", "fence-a");
    batch.setBatchSource("REMOTE_FOLLOWUP_DRAIN");
    TickEffect appliedEffect = remoteDrainedEffect("tb-remote-terminal", "followup-1");
    appliedEffect.setStatus("APPLIED");
    TickEffect remainingEffect = remoteDrainedEffect("tb-remote-terminal", "followup-2");
    when(tickEffectRepository.findByTickBatchId("tb-remote-terminal"))
        .thenReturn(List.of(appliedEffect, remainingEffect));

    service.markRemoteFollowupBatchAbandoned(batch, "REMOTE_FAILURE", "remote failed");

    assertEquals("ABANDONED", batch.getStatus());
    assertEquals("APPLIED", appliedEffect.getStatus());
    assertEquals("ABANDONED", remainingEffect.getStatus());
    verify(tickBatchRepository).save(batch);
    verify(remoteFollowupDrainService)
        .releaseClaimedFollowups("tb-remote-terminal", "REMOTE_FAILURE", "remote failed");
  }

  @Test
  void markRemoteFollowupBatchAbandonedRestoresBatchStateWhenTransactionRollsBack() {
    List<String> events = new ArrayList<>();
    service =
        newService(
            new TransactionOperations() {
              @Override
              public <T> T execute(TransactionCallback<T> action) {
                events.add("transaction-begin");
                action.doInTransaction(new SimpleTransactionStatus());
                events.add("transaction-rollback");
                throw new IllegalStateException("transaction rolled back");
              }
            });
    TickBatch batch = drainedBatch("tb-remote-rollback", "fence-a");
    batch.setBatchSource("REMOTE_FOLLOWUP_DRAIN");
    batch.setStatus("STAGED");
    Instant originalCompletedAt = Instant.parse("2026-04-19T00:00:00Z");
    batch.setCompletedAt(originalCompletedAt);
    batch.setFailureCode("ORIGINAL_FAILURE");
    batch.setFailureMessage("original failure");
    TickEffect terminalEffect = remoteDrainedEffect("tb-remote-rollback", "followup-terminal");
    terminalEffect.setStatus("APPLIED");
    TickEffect stagedEffect = remoteDrainedEffect("tb-remote-rollback", "followup-staged");
    stagedEffect.setStatus("STAGED");
    TickEffect drainedEffect = remoteDrainedEffect("tb-remote-rollback", "followup-drained");
    when(tickEffectRepository.findByTickBatchId("tb-remote-rollback"))
        .thenReturn(List.of(terminalEffect, stagedEffect, drainedEffect));
    doAnswer(
            invocation -> {
              events.add("batch");
              return invocation.getArgument(0);
            })
        .when(tickBatchRepository)
        .save(any());
    doAnswer(
            invocation -> {
              events.add("effects");
              assertEquals(List.of(stagedEffect, drainedEffect), invocation.getArgument(0));
              return invocation.getArgument(0);
            })
        .when(tickEffectRepository)
        .saveAll(any());
    doAnswer(
            invocation -> {
              events.add("claims");
              return 2;
            })
        .when(remoteFollowupDrainService)
        .releaseClaimedFollowups("tb-remote-rollback", "REMOTE_FAILURE", "remote failure");

    assertThrows(
        IllegalStateException.class,
        () -> service.markRemoteFollowupBatchAbandoned(batch, "REMOTE_FAILURE", "remote failure"));

    assertEquals("STAGED", batch.getStatus());
    assertEquals(originalCompletedAt, batch.getCompletedAt());
    assertEquals("ORIGINAL_FAILURE", batch.getFailureCode());
    assertEquals("original failure", batch.getFailureMessage());
    assertEquals("APPLIED", terminalEffect.getStatus());
    assertEquals(
        List.of("transaction-begin", "batch", "effects", "claims", "transaction-rollback"), events);
  }

  @Test
  void remoteEffectSaveFailureRollsBackFollowupMutationInsideTransaction() {
    List<String> events = new ArrayList<>();
    service =
        newService(
            new TransactionOperations() {
              @Override
              public <T> T execute(TransactionCallback<T> action) {
                events.add("transaction-begin");
                try {
                  T result = action.doInTransaction(new SimpleTransactionStatus());
                  events.add("transaction-commit");
                  return result;
                } catch (RuntimeException | Error ex) {
                  events.add("transaction-rollback");
                  throw ex;
                }
              }
            });
    TickBatch batch = drainedBatch("tb-remote-effect-failure", "fence-a");
    batch.setBatchSource("REMOTE_FOLLOWUP_DRAIN");
    batch.setExpectedEffectCount(1);
    TickEffect effect = remoteDrainedEffect("tb-remote-effect-failure", "followup-1");
    when(tickEffectRepository.findByTickBatchId("tb-remote-effect-failure"))
        .thenReturn(List.of(effect));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(
            "tb-remote-effect-failure", "DRAINED"))
        .thenReturn(List.of(effect));
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(batch));
    doAnswer(
            invocation -> {
              events.add("remote-followup");
              return new DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult(
                  "APPLIED", null, null);
            })
        .when(durableRemoteFollowupExecutionService)
        .execute(effect);
    doAnswer(
            invocation -> {
              events.add("effect-save");
              throw new IllegalStateException("effect write failed");
            })
        .when(tickEffectRepository)
        .save(any());

    assertThrows(IllegalStateException.class, () -> service.executeDurableEffects(1L, 2L));

    assertEquals(
        List.of("transaction-begin", "remote-followup", "effect-save", "transaction-rollback"),
        events);
    verify(durableRemoteFollowupExecutionService).execute(effect);
    verify(tickEffectRepository).save(effect);
    verify(tickBatchRepository, never()).save(batch);
  }

  @Test
  void abandonDrainedBatchRequeuesOnlyEffectsThatRemainUnapplied() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-partial");
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setStatus("DRAINED");
    TickEffect remaining = new TickEffect();
    remaining.setTickBatchId("tb-partial");
    remaining.setCommandId("cmd-retry");
    remaining.setStatus("DRAINED");
    GameplayCommand retryCommand = gameplayCommand("cmd-retry");
    retryCommand.setCommandText("north");
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc("tb-partial", "DRAINED"))
        .thenReturn(List.of(remaining));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-retry")))
        .thenReturn(List.of(retryCommand));

    service.markBatchAbandoned(
        batch,
        List.of(
            new TickQueuedCommandEnvelope(false, "cmd-applied", "look"),
            new TickQueuedCommandEnvelope(false, "cmd-retry", "north")),
        "ROLLBACK_REQUEUED",
        "Plugin authority unavailable");

    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-retry|north");
    verify(listOps, never()).leftPush("gamesession:tick:queue:1:2", "N|cmd-applied|look");
    assertEquals("ABANDONED", remaining.getStatus());
    assertEquals("RETRY_QUEUED", retryCommand.getExecutionOutcome());
    assertEquals("GAMEPLAY_RETRY", retryCommand.getQueueSourceKind());
  }

  @Test
  void restorePendingProjectionRequeuesRedisOnlyEntriesAndRestoresSealedPendingList() {
    TickQueuedCommandEnvelope sealed = new TickQueuedCommandEnvelope(false, "cmd-1", "look");
    TickQueuedCommandEnvelope redisOnly = new TickQueuedCommandEnvelope(false, "cmd-2", "wave");
    GameplayCommand command = gameplayCommand("cmd-2");
    command.setSourceType("AUTOMATION");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-2")))
        .thenReturn(List.of(command));

    service.restorePendingProjection(1L, 2L, List.of(sealed, redisOnly), List.of(sealed));

    verify(redisTemplate).delete("gamesession:tick:pending:1:2");
    verify(listOps).rightPush("gamesession:tick:pending:1:2", "N|cmd-1|look");
    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-2|wave");
    assertEquals("RETRY_QUEUED", command.getExecutionOutcome());
    assertEquals("GAMEPLAY_RETRY", command.getQueueSourceKind());
    assertTrue(
        meterRegistry
                .get("tick_requeued_action_total")
                .tag("source", "automation")
                .counter()
                .count()
            > 0.0);
  }

  private static GameplayCommand gameplayCommand(String commandId) {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setRegionId("2");
    command.setRegionEpoch(1L);
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setAttemptCount(1);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    return command;
  }

  private TickBatchExecutionService newService(TransactionOperations transactionOperations) {
    return new TickBatchExecutionService(
        meterRegistry,
        redisTemplate,
        gameplayCommandRepository,
        tickBatchRepository,
        tickEffectRepository,
        durableGameplayCommandExecutionService,
        durableRemoteFollowupExecutionService,
        remoteFollowupDrainService,
        newTickQueueControlService(),
        gameplayCommandExecutionFenceService,
        transactionOperations);
  }

  private TickQueueControlService newTickQueueControlService() {
    return new TickQueueControlService(
        redisTemplate,
        mock(StringRedisTemplate.class),
        mock(GameInstanceRepository.class),
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        new RuntimeIdentity(
            "game-session-service",
            "test-instance",
            "test-host",
            Instant.parse("2026-04-19T00:00:00Z"),
            null,
            null,
            null),
        mock(net.firedevops.firemud.gamesession.service.SessionAuthenticationService.class),
        mock(java.util.concurrent.ScheduledExecutorService.class));
  }

  private static TickBatch drainedBatch(String tickBatchId, String executorFence) {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId(tickBatchId);
    batch.setTenantId(1L);
    batch.setGameInstanceId(2L);
    batch.setRegionId("2");
    batch.setRegionEpoch(1L);
    batch.setExecutorFence(executorFence);
    batch.setStatus("DRAINED");
    return batch;
  }

  private static TickEffect drainedEffect(String tickBatchId, String commandId) {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId(tickBatchId);
    effect.setCommandId(commandId);
    effect.setStatus("DRAINED");
    return effect;
  }

  private static TickEffect remoteDrainedEffect(String tickBatchId, String followupId) {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId(tickBatchId);
    effect.setEffectKey(followupId);
    effect.setEffectType("REMOTE_FOLLOWUP");
    effect.setStatus("DRAINED");
    return effect;
  }

  private static RuntimeRegionStatus runtimeOwnership(
      long tenantId, long gameInstanceId, long regionEpoch, String executorFence, boolean paused) {
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setTenantId(tenantId);
    status.setGameInstanceId(gameInstanceId);
    status.setRegionId(Long.toString(gameInstanceId));
    status.setRegionEpoch(regionEpoch);
    status.setExecutorFence(executorFence);
    status.setOwnerService("game-session-service");
    status.setOwnerInstanceId("test-instance");
    status.setPaused(paused);
    status.setUpdatedAt(Instant.parse("2026-04-19T00:00:00Z"));
    return status;
  }
}
