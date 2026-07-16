package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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

class TickBatchExecutionServiceTest {
  private SimpleMeterRegistry meterRegistry;
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private GameplayCommandRepository gameplayCommandRepository;
  private TickBatchRepository tickBatchRepository;
  private TickEffectRepository tickEffectRepository;
  private RemoteFollowupDrainService remoteFollowupDrainService;
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
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    net.firedevops.firemud.gamesession.service.SessionAuthenticationService
        sessionAuthenticationService =
            mock(net.firedevops.firemud.gamesession.service.SessionAuthenticationService.class);
    RuntimeIdentity runtimeIdentity =
        new RuntimeIdentity(
            "game-session-service",
            "test-instance",
            "test-host",
            Instant.parse("2026-04-19T00:00:00Z"),
            null,
            null,
            null);
    TickQueueControlService tickQueueControlService =
        new TickQueueControlService(
            redisTemplate,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            runtimeIdentity,
            sessionAuthenticationService);
    remoteFollowupDrainService = mock(RemoteFollowupDrainService.class);
    service =
        new TickBatchExecutionService(
            meterRegistry,
            redisTemplate,
            gameplayCommandRepository,
            tickBatchRepository,
            tickEffectRepository,
            mock(DurableGameplayCommandExecutionService.class),
            mock(DurableRemoteFollowupExecutionService.class),
            remoteFollowupDrainService,
            tickQueueControlService);

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
  void markBatchManifestMismatchMarksRetryAndIncrementsMetric() {
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-1");
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
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setAttemptCount(1);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    return command;
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
