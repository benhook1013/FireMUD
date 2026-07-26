package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.repository.TickBatchRepository;
import net.firedevops.firemud.gamesession.repository.TickEffectRepository;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class TickStagingServiceTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private GameplayCommandRepository gameplayCommandRepository;
  private RemoteFollowupRepository remoteFollowupRepository;
  private TickBatchRepository tickBatchRepository;
  private TickEffectRepository tickEffectRepository;
  private RemoteFollowupDrainService remoteFollowupDrainService;
  private DurableRemoteFollowupExecutionService durableRemoteFollowupExecutionService;
  private TickQueueControlService tickQueueControlService;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private TickBatchExecutionService tickBatchExecutionService;
  private TickStagingService service;
  private List<TickEffect> savedEffects;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
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
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    tickBatchRepository = mock(TickBatchRepository.class);
    when(tickBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    tickEffectRepository = mock(TickEffectRepository.class);
    savedEffects = new ArrayList<>();
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<net.firedevops.firemud.gamesession.entity.TickEffect> effects =
                  invocation.getArgument(0);
              for (TickEffect effect : effects) {
                savedEffects.removeIf(
                    existing -> existing.getEffectId().equals(effect.getEffectId()));
                savedEffects.add(effect);
              }
              return effects;
            })
        .when(tickEffectRepository)
        .saveAll(any());
    remoteFollowupDrainService = mock(RemoteFollowupDrainService.class);
    durableRemoteFollowupExecutionService = mock(DurableRemoteFollowupExecutionService.class);
    GameplayCommandExecutionFenceService gameplayCommandExecutionFenceService =
        mock(GameplayCommandExecutionFenceService.class);
    when(gameplayCommandExecutionFenceService.validate(any(), any())).thenReturn(Optional.empty());
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    tickQueueControlService =
        new TickQueueControlService(
            redisTemplate,
            mock(StringRedisTemplate.class),
            mock(GameInstanceRepository.class),
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            new net.firedevops.firemud.common.runtime.RuntimeIdentity(
                "game-session-service",
                "test-instance",
                "test-host",
                Instant.parse("2026-04-19T00:00:00Z"),
                null,
                null,
                null),
            mock(net.firedevops.firemud.gamesession.service.SessionAuthenticationService.class),
            mock(java.util.concurrent.ScheduledExecutorService.class));
    tickBatchExecutionService =
        new TickBatchExecutionService(
            new SimpleMeterRegistry(),
            redisTemplate,
            gameplayCommandRepository,
            tickBatchRepository,
            tickEffectRepository,
            mock(DurableGameplayCommandExecutionService.class),
            durableRemoteFollowupExecutionService,
            remoteFollowupDrainService,
            tickQueueControlService,
            gameplayCommandExecutionFenceService,
            new ImmediateTransactionOperations());
    service =
        new TickStagingService(
            redisTemplate,
            gameplayCommandRepository,
            remoteFollowupRepository,
            tickBatchRepository,
            tickEffectRepository,
            remoteFollowupDrainService,
            tickQueueControlService,
            tickBatchExecutionService,
            new ImmediateTransactionOperations());
    setField(service, "maxRemoteFollowupsPerTick", 16);
    RuntimeRegionStatus currentOwnership =
        runtimeOwnership(1L, 2L, "region-a", 1L, "fence-a", false);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(currentOwnership));
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-a"))
        .thenReturn(Optional.of(currentOwnership));
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtimeRegionStatusRepository.commitDrainedBatch(any(), any()))
        .thenAnswer(
            invocation -> {
              RuntimeRegionStatus expected = invocation.getArgument(0);
              expected.setLastCommittedTickBatchId(invocation.getArgument(1));
              return Optional.of(expected);
            });
    when(tickEffectRepository.findByTickBatchId(anyString()))
        .thenAnswer(
            invocation ->
                savedEffects.stream()
                    .filter(effect -> invocation.getArgument(0).equals(effect.getTickBatchId()))
                    .toList());
  }

  @Test
  void readExecutablePendingEntriesDropsMissingAcceptedAndTerminalCommands() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(
            List.of(
                "N|cmd-staged|look",
                "N|cmd-accepted|say wait",
                "N|cmd-terminal|wave",
                "N|cmd-missing|north"));
    GameplayCommand staged = gameplayCommand("cmd-staged");
    staged.setExecutionOutcome("STAGED");
    GameplayCommand accepted = gameplayCommand("cmd-accepted");
    accepted.setExecutionOutcome("ACCEPTED");
    GameplayCommand terminal = gameplayCommand("cmd-terminal");
    terminal.setExecutionOutcome("LOST_BEFORE_STAGING");
    when(gameplayCommandRepository.findByCommandIdIn(
            List.of("cmd-staged", "cmd-accepted", "cmd-terminal", "cmd-missing")))
        .thenReturn(List.of(staged, accepted, terminal));

    List<TickQueuedCommandEnvelope> executable = service.readExecutablePendingEntries(1L, 2L);

    assertEquals(
        List.of("cmd-staged"),
        executable.stream().map(TickQueuedCommandEnvelope::commandId).toList());
  }

  @Test
  void readExecutablePendingEntriesDropsCrossTenantResidue() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-cross-tenant|look"));
    GameplayCommand command = gameplayCommand("cmd-cross-tenant");
    command.setTenantId(99L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-cross-tenant")))
        .thenReturn(List.of(command));

    assertTrue(service.readExecutablePendingEntries(1L, 2L).isEmpty());
  }

  @Test
  void readExecutablePendingEntriesDropsCrossGameInstanceResidue() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-cross-game|look"));
    GameplayCommand command = gameplayCommand("cmd-cross-game");
    command.setGameInstanceId(99L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-cross-game")))
        .thenReturn(List.of(command));

    assertTrue(service.readExecutablePendingEntries(1L, 2L).isEmpty());
  }

  @Test
  void readExecutablePendingEntriesDropsStaleRegionAndEpochResidue() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-stale-region|look", "N|cmd-stale-epoch|look"));
    GameplayCommand staleRegion = gameplayCommand("cmd-stale-region");
    staleRegion.setRegionId("region-old");
    GameplayCommand staleEpoch = gameplayCommand("cmd-stale-epoch");
    staleEpoch.setRegionEpoch(2L);
    when(gameplayCommandRepository.findByCommandIdIn(
            List.of("cmd-stale-region", "cmd-stale-epoch")))
        .thenReturn(List.of(staleRegion, staleEpoch));

    assertTrue(service.readExecutablePendingEntries(1L, 2L).isEmpty());
  }

  @Test
  void createBatchRejectsStaleExecutorFenceBeforeAnyDurableWrite() {
    GameplayCommand command = gameplayCommand("cmd-stale-fence");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-stale-fence")))
        .thenReturn(List.of(command));

    assertThrows(
        TickQueueControlService.StaleOwnershipException.class,
        () ->
            service.createBatch(
                "FRESH_STAGE",
                1L,
                2L,
                false,
                new TickQueueControlService.OwnershipSnapshot(
                    "region-a", 1L, "fence-stale", false, 0L),
                List.of(new TickQueuedCommandEnvelope(false, "cmd-stale-fence", "look"))));

    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
    verify(gameplayCommandRepository, never()).saveAll(any());
  }

  @Test
  void createBatchRejectsCommandScopeDriftInsideTransactionBeforeAnyDurableWrite() {
    GameplayCommand initial = gameplayCommand("cmd-scope-drift");
    GameplayCommand drifted = gameplayCommand("cmd-scope-drift");
    drifted.setRegionEpoch(2L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-scope-drift")))
        .thenReturn(List.of(initial), List.of(drifted));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createBatch(
                "FRESH_STAGE",
                1L,
                2L,
                false,
                new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
                List.of(new TickQueuedCommandEnvelope(false, "cmd-scope-drift", "look"))));

    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
    verify(gameplayCommandRepository, never()).saveAll(any());
  }

  @Test
  void createBatchRejectsDuplicatePendingCommandIdsBeforeAnyDurableWrite() {
    GameplayCommand command = gameplayCommand("cmd-duplicate");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-duplicate")))
        .thenReturn(List.of(command));
    List<TickQueuedCommandEnvelope> duplicateEntries =
        List.of(
            new TickQueuedCommandEnvelope(false, "cmd-duplicate", "look"),
            new TickQueuedCommandEnvelope(false, "cmd-duplicate", "look"));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.createBatch(
                    "FRESH_STAGE",
                    1L,
                    2L,
                    false,
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L),
                    duplicateEntries));

    assertEquals(
        "Durable tick staging requires unique pending Redis command ids", exception.getMessage());
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
    verify(gameplayCommandRepository, never()).saveAll(any());
  }

  @Test
  void createBatchPersistsComparableOrderingAndCanonicalRoutingManifest() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId("dispatch-1");
    command.setAutomationWorkItemId("work-1");
    command.setScriptId("script-1");
    command.setScriptPatchVersion("patch-1");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    command.setTargetEntityId("entity-1");
    command.setRegionId("region-a");
    command.setRegionEpoch(1L);
    command.setPlayableStateScope("SHARED");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(17L);
    command.setDueTickId(14L);
    command.setEnqueueSeq(77L);
    command.setOriginSourceKind("SCHEDULE_TIMER");
    command.setOriginSourceState("SCHEDULE_DUE_CLAIMED");
    command.setOriginSourceOrdinal(5000L);
    command.setOriginSourceDueAtMs(5000L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    TickBatch batch =
        service.createBatch(
            "FRESH_STAGE",
            1L,
            2L,
            false,
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "say hello")));

    org.junit.jupiter.api.Assertions.assertEquals("region-a", batch.getRegionId());
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceType\":\"AUTOMATION\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceKind\":\"SCHEDULE_TIMER\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":5000"));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceState\":\"SCHEDULE_DUE_CLAIMED\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"worldSlug\":\"demo\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"pointerVersion\":17"));
  }

  @Test
  void firstCommandFallbackOrdinalIsOneBasedAndSurvivesSealedReplayMismatch() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setEnqueueSeq(null);
    command.setOriginSourceKind(null);
    command.setOriginSourceOrdinal(null);
    command.setQueueSourceOrdinal(null);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    TickBatch initialBatch =
        service.createBatch(
            "FRESH_STAGE",
            1L,
            2L,
            false,
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "look")));

    org.junit.jupiter.api.Assertions.assertTrue(
        initialBatch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":1"));
    initialBatch.setSelectedWorkManifestDigest("stale-digest");
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(initialBatch));

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "look")),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    org.junit.jupiter.api.Assertions.assertEquals("PENDING_REPLAY", replayBatch.getBatchSource());
    org.junit.jupiter.api.Assertions.assertTrue(
        replayBatch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":1"));
    org.junit.jupiter.api.Assertions.assertEquals(1L, command.getQueueSourceOrdinal());
  }

  @Test
  void createBatchDropsPartialRoutingBundleFromGameplayManifest() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(null);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    TickBatch batch =
        service.createBatch(
            "FRESH_STAGE",
            1L,
            2L,
            false,
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "say hello")));

    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"worldSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"realmSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"pointerVersion\""));
  }

  @Test
  void createBatchDropsPartialRoutingBundleFromGameplayManifestWhenOnlyPointerVersionIsProvided() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setWorldSlug(null);
    command.setRealmSlug(null);
    command.setPointerVersion(17L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    TickBatch batch =
        service.createBatch(
            "FRESH_STAGE",
            1L,
            2L,
            false,
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "say hello")));

    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"worldSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"realmSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"pointerVersion\""));
  }

  @Test
  void createBatchKeepsDurableWritesInsideOneTransactionBoundary() {
    List<String> events = new ArrayList<>();
    service =
        newStagingService(
            new TransactionOperations() {
              @Override
              public <T> T execute(TransactionCallback<T> action) {
                events.add("transaction-begin");
                T result = action.doInTransaction(new SimpleTransactionStatus());
                events.add("transaction-commit");
                return result;
              }
            });
    GameplayCommand command = gameplayCommand("cmd-atomic");
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

    service.createBatch(
        "FRESH_STAGE",
        1L,
        2L,
        false,
        new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
        List.of(new TickQueuedCommandEnvelope(false, "cmd-atomic", "look")));

    assertEquals(
        List.of("transaction-begin", "batch", "effects", "commands", "transaction-commit"), events);
  }

  @Test
  void createBatchPropagatesCommandAttemptWriteFailure() {
    GameplayCommand command = gameplayCommand("cmd-rollback");
    Instant originalLastAttemptAt = Instant.parse("2026-04-19T00:00:00Z");
    command.setAttemptCount(7);
    command.setLastAttemptAt(originalLastAttemptAt);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-rollback")))
        .thenReturn(List.of(command));
    doThrow(new IllegalStateException("command attempt write failed"))
        .when(gameplayCommandRepository)
        .saveAll(any());

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createBatch(
                "FRESH_STAGE",
                1L,
                2L,
                false,
                new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
                List.of(new TickQueuedCommandEnvelope(false, "cmd-rollback", "look"))));

    verify(gameplayCommandRepository).saveAll(any());
  }

  @Test
  void resolveReplayBatchRejectsPartialDurableEffectSet() {
    GameplayCommand command = gameplayCommand("cmd-partial");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-partial")))
        .thenReturn(List.of(command));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-partial");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setExpectedEffectCount(2);
    existingBatch.setSelectedWorkManifestJson(
        replayManifestJson(service, List.of("N|cmd-partial|look")));
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-partial|look")));
    TickEffect partialEffect = new TickEffect();
    partialEffect.setTickBatchId("tb-partial");
    when(tickEffectRepository.findByTickBatchId("tb-partial")).thenReturn(List.of(partialEffect));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.resolveReplayBatch(
                    1L,
                    2L,
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-partial", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(failure.getMessage().contains("expected=2 actual=1"));
    verify(tickBatchRepository, never()).save(any());
  }

  @Test
  void resolveReplayBatchPreservesTheSealedComparableOrderingTuple() {
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setBatchSource("FRESH_STAGE");
    existingBatch.setSelectedWorkManifestJson(
        "{\"version\":1,\"source\":\"GAMEPLAY_COMMAND_QUEUE\",\"regionId\":\"region-a\",\"items\":[{\"sourceKind\":\"SCHEDULE_TIMER\",\"sourceOrdinal\":5000,\"sourceState\":\"SCHEDULE_DUE_CLAIMED\",\"effectKey\":\"command:cmd-1\",\"commandId\":\"cmd-1\",\"queueSourceDueTickId\":14,\"queueSourceDueAtMs\":9000,\"requiresSoloTick\":false,\"commandDigest\":\"digest\"}]}");
    existingBatch.setSelectedWorkManifestDigest("stale-digest");
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setEnqueueSeq(null);
    command.setOriginSourceKind(null);
    command.setOriginSourceState(null);
    command.setOriginSourceOrdinal(null);
    command.setQueueSourceOrdinal(null);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "look")),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    org.junit.jupiter.api.Assertions.assertTrue(
        replayBatch.getSelectedWorkManifestJson().contains("\"sourceKind\":\"GAMEPLAY_RETRY\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        replayBatch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":5000"));
    org.junit.jupiter.api.Assertions.assertTrue(
        replayBatch.getSelectedWorkManifestJson().contains("\"queueSourceDueAtMs\":9000"));
    org.junit.jupiter.api.Assertions.assertEquals(5000L, command.getQueueSourceOrdinal());
  }

  @Test
  void resolveReplayBatchRestoresSealedManifestAndRequeuesRedisOnlyEntries() {
    List<Object> pendingRawEntries = List.of("N|cmd-1|look", "N|cmd-2|wave");
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setBatchSource("FRESH_STAGE");
    GameplayCommand first = gameplayCommand("cmd-1");
    first.setEnqueueSeq(5L);
    GameplayCommand second = gameplayCommand("cmd-2");
    second.setCommandText("wave");
    second.setSanitizedCommandText("wave");
    second.setEnqueueSeq(6L);
    second.setSourceType("AUTOMATION");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1"))).thenReturn(List.of(first));
    String sealedManifest = replayManifestJson(service, List.of("N|cmd-1|look"));
    existingBatch.setSelectedWorkManifestJson(sealedManifest);
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-1|look")));
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    List<GameplayCommand> savedSnapshots = new ArrayList<>();
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<GameplayCommand> saved = (List<GameplayCommand>) invocation.getArgument(0);
              saved.stream()
                  .map(TickStagingServiceTest::copyGameplayCommand)
                  .forEach(savedSnapshots::add);
              return saved;
            })
        .when(gameplayCommandRepository)
        .saveAll(any());
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1", "cmd-2")))
        .thenReturn(List.of(first, second));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-2"))).thenReturn(List.of(second));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            parseEntries(service, pendingRawEntries),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    org.junit.jupiter.api.Assertions.assertEquals("PENDING_REPLAY", replayBatch.getBatchSource());
    verify(redisTemplate).delete("gamesession:tick:pending:1:2");
    verify(listOps).rightPush("gamesession:tick:pending:1:2", "N|cmd-1|look");
    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-2|wave");
    org.junit.jupiter.api.Assertions.assertTrue(
        savedSnapshots.stream()
            .anyMatch(
                saved ->
                    "cmd-2".equals(saved.getCommandId())
                        && "RETRY_QUEUED".equals(saved.getExecutionOutcome())
                        && "GAMEPLAY_RETRY".equals(saved.getQueueSourceKind())));
  }

  @Test
  void drainRemoteFollowupsDropsPartialRoutingBundleFromManifest() {
    net.firedevops.firemud.gamesession.entity.RemoteFollowup followup =
        new net.firedevops.firemud.gamesession.entity.RemoteFollowup();
    followup.setTenantId(1L);
    followup.setFollowupId("followup-1");
    followup.setTargetGameInstanceId(2L);
    followup.setOriginRegionId("origin-region");
    followup.setOriginRegionEpoch(4L);
    followup.setTargetRegionId("region-a");
    followup.setTargetRegionEpoch(8L);
    followup.setDueTickId(10L);
    followup.setQueueSourceKind("REMOTE_FOLLOWUP");
    followup.setQueueSourceState("REDIS_PENDING_CLAIMED");
    followup.setQueueSourceOrdinal(1L);
    followup.setTargetEntityId("entity-1");
    followup.setClaimTargetAggregate("entity:entity-1");
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(null);
    followup.setPayloadKind("noop");
    followup.setRequestedCommand("LOOK");
    followup.setRequiresSoloTick(true);
    followup.setPayloadJson("{\"kind\":\"noop\"}");
    when(remoteFollowupDrainService.claimDueFollowups(
            eq(1L), eq(2L), eq("region-a"), eq(1L), anyString(), eq(16)))
        .thenReturn(new RemoteFollowupDrainService.ClaimOutcome(List.of("followup-1"), 1));
    when(remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(anyString()))
        .thenReturn(List.of(followup));

    service.drainRemoteFollowups(
        1L,
        2L,
        new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    ArgumentCaptor<TickBatch> batchCaptor = ArgumentCaptor.forClass(TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    TickBatch stagedBatch =
        batchCaptor.getAllValues().stream()
            .filter(batch -> "REMOTE_FOLLOWUP_DRAIN".equals(batch.getBatchSource()))
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("\"worldSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("\"realmSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("\"pointerVersion\""));
  }

  @Test
  void postDrainRemoteEffectFailurePreservesTerminalEffectsAndReleasesRemainingClaims() {
    net.firedevops.firemud.gamesession.entity.RemoteFollowup first =
        remoteFollowup("followup-1", "entity-1");
    net.firedevops.firemud.gamesession.entity.RemoteFollowup second =
        remoteFollowup("followup-2", "entity-2");
    when(remoteFollowupDrainService.claimDueFollowups(
            eq(1L), eq(2L), eq("region-a"), eq(1L), anyString(), eq(16)))
        .thenReturn(
            new RemoteFollowupDrainService.ClaimOutcome(List.of("followup-1", "followup-2"), 2));
    when(remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(anyString()))
        .thenReturn(List.of(first, second));
    List<TickBatch> savedBatches = new ArrayList<>();
    doAnswer(
            invocation -> {
              TickBatch batch = invocation.getArgument(0);
              savedBatches.add(batch);
              return batch;
            })
        .when(tickBatchRepository)
        .save(any());
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenAnswer(
            invocation ->
                savedBatches.isEmpty()
                    ? List.of()
                    : List.of(savedBatches.get(savedBatches.size() - 1)));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(anyString(), eq("DRAINED")))
        .thenAnswer(
            invocation ->
                savedEffects.stream()
                    .filter(
                        effect ->
                            invocation.getArgument(0, String.class).equals(effect.getTickBatchId()))
                    .filter(
                        effect ->
                            invocation.getArgument(1, String.class).equals(effect.getStatus()))
                    .toList());
    when(durableRemoteFollowupExecutionService.execute(any()))
        .thenAnswer(
            invocation -> {
              TickEffect effect = invocation.getArgument(0);
              if ("followup-1".equals(effect.getEffectKey())) {
                return new DurableRemoteFollowupExecutionService
                    .DurableRemoteFollowupExecutionResult("APPLIED", null, null);
              }
              throw new IllegalStateException("remote effect failed");
            });

    assertThrows(
        IllegalStateException.class,
        () ->
            service.drainRemoteFollowups(
                1L,
                2L,
                new TickQueueControlService.OwnershipSnapshot(
                    "region-a", 1L, "fence-a", false, 0L)));

    verify(remoteFollowupDrainService)
        .releaseClaimedFollowups(anyString(), eq("ROLLBACK_REQUEUED"), eq("remote effect failed"));
    assertEquals("ABANDONED", savedBatches.get(savedBatches.size() - 1).getStatus());
    assertEquals("APPLIED", savedEffects.get(0).getStatus());
    assertEquals("ABANDONED", savedEffects.get(1).getStatus());
  }

  private static net.firedevops.firemud.gamesession.entity.RemoteFollowup remoteFollowup(
      String followupId, String targetEntityId) {
    var followup = new net.firedevops.firemud.gamesession.entity.RemoteFollowup();
    followup.setTenantId(1L);
    followup.setFollowupId(followupId);
    followup.setTargetGameInstanceId(2L);
    followup.setTargetRegionId("region-a");
    followup.setTargetRegionEpoch(1L);
    followup.setDueTickId(1L);
    followup.setTargetEntityId(targetEntityId);
    followup.setClaimTargetAggregate("entity:" + targetEntityId);
    return followup;
  }

  private TickStagingService newStagingService(TransactionOperations transactionOperations) {
    return new TickStagingService(
        redisTemplate,
        gameplayCommandRepository,
        remoteFollowupRepository,
        tickBatchRepository,
        tickEffectRepository,
        remoteFollowupDrainService,
        tickQueueControlService,
        tickBatchExecutionService,
        transactionOperations);
  }

  private static GameplayCommand gameplayCommand(String commandId) {
    var command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setAttemptCount(1);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    command.setRegionId("region-a");
    command.setRegionEpoch(1L);
    return command;
  }

  private static GameplayCommand copyGameplayCommand(GameplayCommand source) {
    var copy = new GameplayCommand();
    copy.setCommandId(source.getCommandId());
    copy.setExecutionOutcome(source.getExecutionOutcome());
    copy.setQueueSourceKind(source.getQueueSourceKind());
    copy.setQueueSourceState(source.getQueueSourceState());
    return copy;
  }

  private static RuntimeRegionStatus runtimeOwnership(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      long regionEpoch,
      String executorFence,
      boolean paused) {
    var status = new RuntimeRegionStatus();
    status.setTenantId(tenantId);
    status.setGameInstanceId(gameInstanceId);
    status.setRegionId(regionId);
    status.setRegionEpoch(regionEpoch);
    status.setExecutorFence(executorFence);
    status.setPaused(paused);
    status.setUpdatedAt(Instant.parse("2026-04-19T00:00:00Z"));
    return status;
  }

  @SuppressWarnings("unchecked")
  private static List<TickQueuedCommandEnvelope> parseEntries(
      TickStagingService service, List<Object> rawEntries) {
    try {
      var parseMethod =
          TickStagingService.class.getDeclaredMethod("parseQueuedCommand", String.class);
      parseMethod.setAccessible(true);
      List<TickQueuedCommandEnvelope> entries = new ArrayList<>();
      for (Object rawEntry : rawEntries) {
        entries.add((TickQueuedCommandEnvelope) parseMethod.invoke(service, rawEntry.toString()));
      }
      return List.copyOf(entries);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to parse queued entries", e);
    }
  }

  private static String replayManifestDigest(TickStagingService service, List<Object> rawEntries) {
    String manifest = replayManifestJson(service, rawEntries);
    return shortHash(service, manifest);
  }

  private static String replayManifestJson(TickStagingService service, List<Object> rawEntries) {
    try {
      var selectionsMethod =
          TickStagingService.class.getDeclaredMethod("commandSelections", List.class);
      selectionsMethod.setAccessible(true);
      Object selections = selectionsMethod.invoke(service, parseEntries(service, rawEntries));
      var manifestMethod =
          TickStagingService.class.getDeclaredMethod(
              "selectedWorkManifest", String.class, List.class);
      manifestMethod.setAccessible(true);
      return (String) manifestMethod.invoke(service, "region-a", selections);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to compute replay manifest json", e);
    }
  }

  private static String shortHash(TickStagingService service, String value) {
    try {
      var hashMethod = TickStagingService.class.getDeclaredMethod("shortHash", String.class);
      hashMethod.setAccessible(true);
      return (String) hashMethod.invoke(service, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to compute short hash", e);
    }
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to set field " + fieldName, e);
    }
  }
}
