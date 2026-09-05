package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.firedevops.firemud.gamesession.entity.GameInstance;
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
  private GameInstanceRepository gameInstanceRepository;
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
    when(redisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(1L);
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
    gameInstanceRepository = mock(GameInstanceRepository.class);
    GameInstance pinnedInstance = new GameInstance();
    pinnedInstance.setId(2L);
    pinnedInstance.setTenantId(1L);
    pinnedInstance.setScriptPatchVersion("patch-1");
    pinnedInstance.setScriptPinEpoch(1L);
    pinnedInstance.setScriptPatchPinnedControlPlaneRequestId("request-1");
    when(gameInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(1L, 2L))
        .thenReturn(Optional.of(pinnedInstance));
    GameplayCommandExecutionFenceService gameplayCommandExecutionFenceService =
        mock(GameplayCommandExecutionFenceService.class);
    when(gameplayCommandExecutionFenceService.validate(any(), any())).thenReturn(Optional.empty());
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    tickQueueControlService =
        new TickQueueControlService(
            redisTemplate,
            mock(StringRedisTemplate.class),
            gameInstanceRepository,
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
            gameInstanceRepository,
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
  void readPendingEntriesForReplayDropsMissingAcceptedAndTerminalCommands() {
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

    TickStagingService.PendingEntriesReadResult result =
        service.readPendingEntriesForReplay(1L, 2L);

    assertEquals(TickStagingService.PendingEntriesReadStatus.ORPHANED_OR_STALE, result.status());
    assertEquals(
        List.of("cmd-staged"),
        result.entries().stream().map(TickQueuedCommandEnvelope::commandId).toList());
  }

  @Test
  void readPendingEntriesForReplayDropsCrossTenantResidue() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-cross-tenant|look"));
    GameplayCommand command = gameplayCommand("cmd-cross-tenant");
    command.setTenantId(99L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-cross-tenant")))
        .thenReturn(List.of(command));

    TickStagingService.PendingEntriesReadResult result =
        service.readPendingEntriesForReplay(1L, 2L);

    assertEquals(TickStagingService.PendingEntriesReadStatus.ORPHANED_OR_STALE, result.status());
    assertTrue(result.entries().isEmpty());
  }

  @Test
  void readPendingEntriesForReplayDropsCrossGameInstanceResidue() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-cross-game|look"));
    GameplayCommand command = gameplayCommand("cmd-cross-game");
    command.setGameInstanceId(99L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-cross-game")))
        .thenReturn(List.of(command));

    TickStagingService.PendingEntriesReadResult result =
        service.readPendingEntriesForReplay(1L, 2L);

    assertEquals(TickStagingService.PendingEntriesReadStatus.ORPHANED_OR_STALE, result.status());
    assertTrue(result.entries().isEmpty());
  }

  @Test
  void readPendingEntriesForReplayDropsStaleRegionAndEpochResidue() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-stale-region|look", "N|cmd-stale-epoch|look"));
    GameplayCommand staleRegion = gameplayCommand("cmd-stale-region");
    staleRegion.setRegionId("region-old");
    GameplayCommand staleEpoch = gameplayCommand("cmd-stale-epoch");
    staleEpoch.setRegionEpoch(2L);
    when(gameplayCommandRepository.findByCommandIdIn(
            List.of("cmd-stale-region", "cmd-stale-epoch")))
        .thenReturn(List.of(staleRegion, staleEpoch));

    TickStagingService.PendingEntriesReadResult result =
        service.readPendingEntriesForReplay(1L, 2L);

    assertEquals(TickStagingService.PendingEntriesReadStatus.ORPHANED_OR_STALE, result.status());
    assertTrue(result.entries().isEmpty());
  }

  @Test
  void readPendingEntriesForReplayReportsUnavailableOwnershipSeparately() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-staged|look"));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.empty());

    TickStagingService.PendingEntriesReadResult result =
        service.readPendingEntriesForReplay(1L, 2L);

    assertEquals(
        TickStagingService.PendingEntriesReadStatus.AUTHORITY_UNAVAILABLE, result.status());
    assertTrue(result.entries().isEmpty());
  }

  @Test
  void readPendingEntriesForReplayReportsMixedExecutableAndStaleEvidence() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-staged|look", "N|cmd-stale|wave"));
    GameplayCommand staged = gameplayCommand("cmd-staged");
    staged.setExecutionOutcome("STAGED");
    GameplayCommand stale = gameplayCommand("cmd-stale");
    stale.setExecutionOutcome("DRAINED");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-staged", "cmd-stale")))
        .thenReturn(List.of(staged, stale));

    TickStagingService.PendingEntriesReadResult result =
        service.readPendingEntriesForReplay(1L, 2L);

    assertEquals(TickStagingService.PendingEntriesReadStatus.ORPHANED_OR_STALE, result.status());
    assertEquals(
        List.of("cmd-staged"),
        result.entries().stream().map(TickQueuedCommandEnvelope::commandId).toList());
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
  void createBatchRejectsUnsafeCommandIdBeforeAnyDurableWrite() {
    TickQueuedCommandEnvelope unsafe = new TickQueuedCommandEnvelope(false, "cmd|unsafe", "look");

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
                    List.of(unsafe)));

    assertTrue(exception.getMessage().contains("queue-safe command ids"));
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
    verify(gameplayCommandRepository, never()).saveAll(any());
  }

  @Test
  void createBatchRejectsMixedNormalAndSoloEntriesBeforeAnyDurableWrite() {
    GameplayCommand normal = gameplayCommand("cmd-normal");
    GameplayCommand solo = gameplayCommand("cmd-solo");
    solo.setRequiresSoloTick(true);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-normal", "cmd-solo")))
        .thenReturn(List.of(normal, solo));

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
                    List.of(
                        new TickQueuedCommandEnvelope(false, "cmd-normal", "look"),
                        new TickQueuedCommandEnvelope(true, "cmd-solo", "generate"))));

    assertEquals("Mixed normal/solo entries in tick batch", exception.getMessage());
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
    verify(gameplayCommandRepository, never()).saveAll(any());
  }

  @Test
  void readPendingEntriesRejectsMalformedEntryWithoutDroppingIt() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("not-a-queue-entry"));

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.readPendingEntries(1L, 2L));

    assertTrue(exception.getMessage().contains("Malformed tick queue entry"));
    verify(listOps, never()).remove(anyString(), eq(0L), any());
    verify(listOps, never()).trim(anyString(), eq(0L), eq(-1L));
  }

  @Test
  void readPendingEntriesRoundTripsDelimiterInCommandText() {
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-pipe|look|east"));

    List<TickQueuedCommandEnvelope> entries = service.readPendingEntries(1L, 2L);

    assertEquals(1, entries.size());
    assertEquals("cmd-pipe", entries.getFirst().commandId());
    assertEquals("look|east", entries.getFirst().command());
  }

  @Test
  void pendingReplayCreatesSoloBatchWhenNoStagedBatchExists() {
    GameplayCommand command = gameplayCommand("cmd-solo-replay");
    command.setRequiresSoloTick(true);
    command.setCommandText("generate");
    command.setSanitizedCommandText("generate");
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-solo-replay")))
        .thenReturn(List.of(command));

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            List.of(new TickQueuedCommandEnvelope(true, "cmd-solo-replay", "generate")),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    assertEquals("PENDING_REPLAY", replayBatch.getBatchSource());
    org.junit.jupiter.api.Assertions.assertTrue(replayBatch.isRequiresSoloTick());
    assertTrue(replayBatch.getSelectedWorkManifestJson().contains("\"requiresSoloTick\":true"));
  }

  @Test
  void equalReplayDigestStillRequiresStoredBatchModeToMatch() {
    GameplayCommand command = gameplayCommand("cmd-mode-mismatch");
    command.setRequiresSoloTick(false);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-mode-mismatch")))
        .thenReturn(List.of(command));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-mode-mismatch");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setRequiresSoloTick(true);
    existingBatch.setSelectedWorkManifestJson(
        replayManifestJson(service, List.of("N|cmd-mode-mismatch|look")));
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-mode-mismatch|look")));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.resolveReplayBatch(
                    1L,
                    2L,
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-mode-mismatch", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(exception.getMessage().contains("Pending replay mode does not match"));
    verify(tickEffectRepository, never()).findByTickBatchId(anyString());
  }

  @Test
  void durableCommandAndSealedBatchModeOverrideStaleQueueMode() {
    GameplayCommand command = gameplayCommand("cmd-sealed-solo");
    command.setRequiresSoloTick(true);
    command.setCommandText("generate");
    command.setSanitizedCommandText("generate");
    command.setScriptPatchVersion("");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-sealed-solo")))
        .thenReturn(List.of(command));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-sealed-solo");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setRequiresSoloTick(true);
    existingBatch.setExpectedEffectCount(1);
    existingBatch.setSelectedWorkManifestJson(
        replayManifestJson(service, List.of("S|cmd-sealed-solo|generate")));
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("S|cmd-sealed-solo|generate")));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));
    TickQueuedCommandEnvelope staleQueueEntry =
        new TickQueuedCommandEnvelope(false, "cmd-sealed-solo", "generate");

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            List.of(staleQueueEntry),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    assertFalse(staleQueueEntry.requiresSoloTick());
    assertTrue(command.isRequiresSoloTick());
    assertTrue(existingBatch.isRequiresSoloTick());
    assertTrue(existingBatch.getSelectedWorkManifestJson().contains("\"requiresSoloTick\":true"));
    assertEquals("PENDING_REPLAY", replayBatch.getBatchSource());
    org.junit.jupiter.api.Assertions.assertTrue(replayBatch.isRequiresSoloTick());
    assertTrue(replayBatch.getSelectedWorkManifestJson().contains("\"requiresSoloTick\":true"));
  }

  @Test
  void sealedReplayRejectsSentinelCommandIdBeforeRestagingOrRedisWrites() {
    GameplayCommand pendingCommand = gameplayCommand("cmd-pending");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-pending")))
        .thenReturn(List.of(pendingCommand));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-sentinel");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setSelectedWorkManifestJson(
        "{\"version\":1,\"source\":\"GAMEPLAY_COMMAND_QUEUE\",\"regionId\":\"region-a\","
            + "\"items\":[{\"sourceKind\":\"GAMEPLAY_RETRY\",\"sourceOrdinal\":1,"
            + "\"sourceState\":\"RETRY_QUEUED\",\"effectKey\":\"command:-\","
            + "\"commandId\":\"-\",\"requiresSoloTick\":false,\"commandDigest\":\"digest\"}]}");
    existingBatch.setSelectedWorkManifestDigest(
        shortHash(service, existingBatch.getSelectedWorkManifestJson()));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.resolveReplayBatch(
                    1L,
                    2L,
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-pending", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(exception.getMessage().contains("requires commandId"));
    verify(redisTemplate, never()).delete(anyString());
    verify(listOps, never()).leftPush(anyString(), any());
    verify(listOps, never()).rightPush(anyString(), any());
    verify(gameplayCommandRepository, never()).saveAll(any());
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void sealedReplayRejectsUnsafeCommandIdBeforeRestagingOrRedisWrites() {
    GameplayCommand pendingCommand = gameplayCommand("cmd-pending");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-pending")))
        .thenReturn(List.of(pendingCommand));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-unsafe-command-id");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setSelectedWorkManifestJson(
        "{\"version\":1,\"source\":\"GAMEPLAY_COMMAND_QUEUE\",\"regionId\":\"region-a\","
            + "\"items\":[{\"sourceKind\":\"GAMEPLAY_RETRY\",\"sourceOrdinal\":1,"
            + "\"sourceState\":\"RETRY_QUEUED\",\"effectKey\":\"command:cmd|unsafe\","
            + "\"commandId\":\"cmd|unsafe\",\"requiresSoloTick\":false,\"commandDigest\":\"digest\"}]}");
    existingBatch.setSelectedWorkManifestDigest(
        shortHash(service, existingBatch.getSelectedWorkManifestJson()));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.resolveReplayBatch(
                    1L,
                    2L,
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-pending", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(exception.getMessage().contains("unsafe commandId"));
    verify(redisTemplate, never()).delete(anyString());
    verify(listOps, never()).leftPush(anyString(), any());
    verify(listOps, never()).rightPush(anyString(), any());
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void createBatchPersistsComparableOrderingAndCanonicalRoutingManifest() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId("dispatch-1");
    command.setAutomationWorkItemId("work-1");
    command.setScriptId("script-1");
    command.setScriptPatchVersion("patch-1");
    command.setScriptPinEpoch(1L);
    command.setScriptPinControlPlaneRequestId("request-1");
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
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"scriptPinEpoch\":1"));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch
            .getSelectedWorkManifestJson()
            .contains("\"scriptPinControlPlaneRequestId\":\"request-1\""));
  }

  @Test
  void createBatchRejectsLocalAutomationCommandWithSamePatchAndDifferentEpoch() {
    GameplayCommand command = gameplayCommand("cmd-different-epoch");
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setScriptPinEpoch(2L);
    command.setScriptPinControlPlaneRequestId("request-2");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-different-epoch")))
        .thenReturn(List.of(command));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createBatch(
                "FRESH_STAGE",
                1L,
                2L,
                false,
                new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
                List.of(new TickQueuedCommandEnvelope(false, "cmd-different-epoch", "look"))));

    verify(gameInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(1L, 2L);
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void createBatchRejectsLocalAutomationCommandWithDifferentOwnerRequest() {
    GameplayCommand command = gameplayCommand("cmd-owner-request");
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setScriptPinEpoch(1L);
    command.setScriptPinControlPlaneRequestId("request-old");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-owner-request")))
        .thenReturn(List.of(command));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.createBatch(
                "FRESH_STAGE",
                1L,
                2L,
                false,
                new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
                List.of(new TickQueuedCommandEnvelope(false, "cmd-owner-request", "look"))));

    verify(gameInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(1L, 2L);
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void createBatchLeavesOrdinaryPlayerCommandOutsideScriptPinBoundary() {
    GameplayCommand command = gameplayCommand("cmd-player");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-player")))
        .thenReturn(List.of(command));

    service.createBatch(
        "FRESH_STAGE",
        1L,
        2L,
        false,
        new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
        List.of(new TickQueuedCommandEnvelope(false, "cmd-player", "look")));

    verify(gameInstanceRepository, never()).findByTenantIdAndGameInstanceIdForUpdate(any(), any());
  }

  @Test
  void resolveReplayBatchRejectsLocalAutomationWithSamePatchAndDifferentEpoch() {
    GameplayCommand command = gameplayCommand("cmd-replay-different-epoch");
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setScriptPinEpoch(2L);
    command.setScriptPinControlPlaneRequestId("request-2");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-replay-different-epoch")))
        .thenReturn(List.of(command));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.resolveReplayBatch(
                    1L,
                    2L,
                    List.of(
                        new TickQueuedCommandEnvelope(false, "cmd-replay-different-epoch", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(failure.getMessage().contains("does not match"));
    verify(gameInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(1L, 2L);
    verify(tickBatchRepository, never())
        .findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(1L, 2L, "STAGED");
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void sealedReplayRejectsLocalAutomationWithDifferentOwnerRequestBeforeRedisRestore() {
    GameplayCommand pendingCommand = gameplayCommand("cmd-pending-player");
    pendingCommand.setSourceType("PLAYER");
    GameplayCommand sealedCommand = gameplayCommand("cmd-sealed-owner");
    sealedCommand.setSourceType(" automation ");
    sealedCommand.setScriptPatchVersion("patch-1");
    sealedCommand.setScriptPinEpoch(1L);
    sealedCommand.setScriptPinControlPlaneRequestId("request-old");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-pending-player")))
        .thenReturn(List.of(pendingCommand));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-sealed-owner")))
        .thenReturn(List.of(sealedCommand));

    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-sealed-owner");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setSelectedWorkManifestJson(
        replayManifestJson(service, List.of("N|cmd-sealed-owner|look")));
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-sealed-owner|look")));
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
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-pending-player", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(failure.getMessage().contains("does not match"));
    verify(gameInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(1L, 2L);
    verify(redisTemplate, never()).delete(anyString());
    verify(listOps, never()).leftPush(anyString(), any());
    verify(listOps, never()).rightPush(anyString(), any());
    verify(gameplayCommandRepository, never()).saveAll(any());
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void sealedReplayRejectsTamperedPinEvidenceEvenWhenManifestDigestIsRecomputed() {
    GameplayCommand pendingCommand = gameplayCommand("cmd-pending-player");
    pendingCommand.setSourceType("PLAYER");
    GameplayCommand sealedCommand = gameplayCommand("cmd-sealed-tamper");
    sealedCommand.setSourceType("AUTOMATION");
    sealedCommand.setScriptPatchVersion("patch-1");
    sealedCommand.setScriptPinEpoch(1L);
    sealedCommand.setScriptPinControlPlaneRequestId("request-1");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-pending-player")))
        .thenReturn(List.of(pendingCommand));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-sealed-tamper")))
        .thenReturn(List.of(sealedCommand));

    String sealedManifest = replayManifestJson(service, List.of("N|cmd-sealed-tamper|look"));
    String tamperedManifest =
        sealedManifest.replace(
            "\"scriptPinControlPlaneRequestId\":\"request-1\"",
            "\"scriptPinControlPlaneRequestId\":\"request-tampered\"");
    assertFalse(sealedManifest.equals(tamperedManifest));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-sealed-tamper");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setSelectedWorkManifestJson(tamperedManifest);
    existingBatch.setSelectedWorkManifestDigest(shortHash(service, tamperedManifest));
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
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-pending-player", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(failure.getMessage().contains("does not match gameplay command"));
    verify(redisTemplate, never()).delete(anyString());
    verify(listOps, never()).leftPush(anyString(), any());
    verify(listOps, never()).rightPush(anyString(), any());
    verify(gameplayCommandRepository, never()).saveAll(any());
    verify(tickBatchRepository, never()).save(any());
    verify(tickEffectRepository, never()).saveAll(any());
  }

  @Test
  void sealedReplayRejectsStoredManifestDigestTamperBeforeLoadingSealedCommands() {
    GameplayCommand pendingCommand = gameplayCommand("cmd-pending-digest");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-pending-digest")))
        .thenReturn(List.of(pendingCommand));
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-digest-tamper");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionId("region-a");
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setSelectedWorkManifestJson(
        "{\"version\":1,\"source\":\"GAMEPLAY_COMMAND_QUEUE\",\"regionId\":\"region-a\","
            + "\"items\":[{\"sourceKind\":\"GAMEPLAY_RETRY\",\"sourceOrdinal\":1,"
            + "\"sourceState\":\"RETRY_QUEUED\",\"effectKey\":\"command:cmd-sealed-digest\","
            + "\"commandId\":\"cmd-sealed-digest\",\"requiresSoloTick\":false,"
            + "\"commandDigest\":\"digest\"}]}");
    existingBatch.setSelectedWorkManifestDigest("tampered-digest");
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
                    List.of(new TickQueuedCommandEnvelope(false, "cmd-pending-digest", "look")),
                    new TickQueueControlService.OwnershipSnapshot(
                        "region-a", 1L, "fence-a", false, 0L)));

    assertTrue(failure.getMessage().contains("manifest digest does not match"));
    verify(gameplayCommandRepository, never()).findByCommandIdIn(List.of("cmd-sealed-digest"));
    verify(gameplayCommandRepository, never()).saveAll(any());
    verify(redisTemplate, never()).delete(anyString());
    verify(listOps, never()).leftPush(anyString(), any());
    verify(listOps, never()).rightPush(anyString(), any());
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
    initialBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-1|look")));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(initialBatch));

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "look changed")),
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
    existingBatch.setSelectedWorkManifestDigest(
        shortHash(service, existingBatch.getSelectedWorkManifestJson()));
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
    doAnswer(
            invocation -> {
              events.add("redis-reconcile");
              return 1L;
            })
        .when(redisTemplate)
        .execute(any(), any(), any(Object[].class));
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
    second.setSourceType("PLAYER");
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
              events.add("durable-command-status");
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

    TickStagingService.ReplayResolution replayResolution =
        service.resolveReplayBatchForTick(
            1L,
            2L,
            parseEntries(service, pendingRawEntries),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));
    TickBatch replayBatch = replayResolution.batch();

    org.junit.jupiter.api.Assertions.assertEquals("PENDING_REPLAY", replayBatch.getBatchSource());
    assertEquals(
        List.of("cmd-1"),
        replayResolution.drainEntries().stream()
            .map(TickQueuedCommandEnvelope::commandId)
            .toList());
    verify(redisTemplate).execute(any(), any(), any(Object[].class));
    int finalTransactionCommit = events.lastIndexOf("transaction-commit");
    assertTrue(finalTransactionCommit >= 0);
    assertTrue(
        events.subList(0, finalTransactionCommit).stream()
            .noneMatch(event -> event.startsWith("redis-")));
    assertTrue(events.indexOf("durable-command-status") < finalTransactionCommit);
    assertTrue(events.indexOf("redis-reconcile") > finalTransactionCommit);
    org.junit.jupiter.api.Assertions.assertTrue(
        savedSnapshots.stream()
            .anyMatch(
                saved ->
                    "cmd-2".equals(saved.getCommandId())
                        && "RETRY_QUEUED".equals(saved.getExecutionOutcome())
                        && "GAMEPLAY_RETRY".equals(saved.getQueueSourceKind())));
  }

  @Test
  void resolveReplayBatchRetriesTheCommittedReplacementWithoutCreatingAnotherBatch() {
    TickBatch replacement = new TickBatch();
    replacement.setTickBatchId("tb-replacement");
    replacement.setTenantId(1L);
    replacement.setGameInstanceId(2L);
    replacement.setRegionId("region-a");
    replacement.setRegionEpoch(1L);
    replacement.setExecutorFence("fence-a");
    replacement.setStatus("STAGED");
    replacement.setBatchSource("PENDING_REPLAY");
    replacement.setRequiresSoloTick(false);
    replacement.setCommandCount(1);
    replacement.setExpectedEffectCount(1);
    GameplayCommand sealedCommand = gameplayCommand("cmd-1");
    GameplayCommand redisOnlyCommand = gameplayCommand("cmd-2");
    redisOnlyCommand.setCommandText("wave");
    redisOnlyCommand.setSanitizedCommandText("wave");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1", "cmd-2")))
        .thenReturn(List.of(sealedCommand, redisOnlyCommand));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(sealedCommand));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-2")))
        .thenReturn(List.of(redisOnlyCommand));
    String sealedManifest = replayManifestJson(service, List.of("N|cmd-1|look"));
    replacement.setSelectedWorkManifestJson(sealedManifest);
    replacement.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-1|look")));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(replacement));

    TickStagingService.ReplayResolution resolution =
        service.resolveReplayBatchForTick(
            1L,
            2L,
            List.of(
                new TickQueuedCommandEnvelope(false, "cmd-1", "look"),
                new TickQueuedCommandEnvelope(false, "cmd-2", "wave")),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    assertEquals("tb-replacement", resolution.batch().getTickBatchId());
    assertTrue(resolution.replacementCommitted());
    assertEquals("STAGED", replacement.getStatus());
    verify(tickBatchRepository, never()).save(any());
    verify(redisTemplate).execute(any(), any(), any(Object[].class));
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
        gameInstanceRepository,
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
