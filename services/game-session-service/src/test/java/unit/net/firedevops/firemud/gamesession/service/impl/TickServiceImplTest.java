package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressRequest;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressResponse;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class TickServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private org.springframework.data.redis.core.ValueOperations<String, Object> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private net.firedevops.firemud.common.conflict.ConflictTracker conflictTracker;
  private net.firedevops.firemud.gamesession.repository.GameInstanceRepository repository;
  private net.firedevops.firemud.gamesession.repository.GameplayCommandRepository
      gameplayCommandRepository;
  private net.firedevops.firemud.common.runtime.RuntimeIdentity runtimeIdentity;
  private net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository
      runtimeRegionStatusRepository;
  private net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository
      remoteFollowupRepository;
  private net.firedevops.firemud.gamesession.repository.TickBatchRepository tickBatchRepository;
  private net.firedevops.firemud.gamesession.repository.TickEffectRepository tickEffectRepository;
  private SessionContextService sessionContextService;
  private net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService
      durableGameplayCommandExecutionService;
  private net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService
      durableRemoteFollowupExecutionService;
  private net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService
      remoteFollowupDrainService;
  private net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService
      remoteFollowupRuntimeService;
  private AutomationScriptingClient automationScriptingClient;
  private TickService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
    valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    meterRegistry = new SimpleMeterRegistry();
    conflictTracker = mock(net.firedevops.firemud.common.conflict.ConflictTracker.class);
    repository = mock(net.firedevops.firemud.gamesession.repository.GameInstanceRepository.class);
    gameplayCommandRepository =
        mock(net.firedevops.firemud.gamesession.repository.GameplayCommandRepository.class);
    AtomicLong commandIds = new AtomicLong();
    when(gameplayCommandRepository.save(any()))
        .thenAnswer(
            invocation -> {
              net.firedevops.firemud.gamesession.entity.GameplayCommand command =
                  invocation.getArgument(0);
              if (command.getId() == null) {
                command.setId(commandIds.incrementAndGet());
              }
              return command;
            });
    runtimeIdentity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "test-instance",
            "test-host",
            Instant.parse("2026-04-19T00:00:00Z"),
            null,
            null,
            null);
    runtimeRegionStatusRepository =
        mock(net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository.class);
    remoteFollowupRepository =
        mock(net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository.class);
    tickBatchRepository =
        mock(net.firedevops.firemud.gamesession.repository.TickBatchRepository.class);
    tickEffectRepository =
        mock(net.firedevops.firemud.gamesession.repository.TickEffectRepository.class);
    sessionContextService = mock(SessionContextService.class);
    durableGameplayCommandExecutionService =
        mock(
            net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService
                .class);
    durableRemoteFollowupExecutionService =
        mock(
            net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService.class);
    remoteFollowupDrainService =
        mock(net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService.class);
    remoteFollowupRuntimeService =
        mock(net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService.class);
    automationScriptingClient = mock(AutomationScriptingClient.class);
    when(automationScriptingClient.observeRuntimeTickProgress(any()))
        .thenReturn(ObserveRuntimeTickProgressResponse.newBuilder().build());
    service =
        new TickServiceImpl(
            redisTemplate,
            meterRegistry,
            conflictTracker,
            repository,
            gameplayCommandRepository,
            runtimeIdentity,
            runtimeRegionStatusRepository,
            remoteFollowupRepository,
            tickBatchRepository,
            tickEffectRepository,
            sessionContextService,
            durableGameplayCommandExecutionService,
            durableRemoteFollowupExecutionService,
            remoteFollowupDrainService,
            remoteFollowupRuntimeService,
            automationScriptingClient);
    ((TickServiceImpl) service).init();
    setField(service, "tickDurationMs", 1000L);
    setField(service, "maxRemoteFollowupsPerTick", 16);
    var instance = new net.firedevops.firemud.gamesession.entity.GameInstance();
    instance.setTenantId(1L);
    when(repository.findById(anyLong())).thenReturn(java.util.Optional.of(instance));
    when(gameplayCommandRepository.findByCommandIdIn(any())).thenReturn(List.of());
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(anyLong(), anyLong()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    runtimeOwnership(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        1L,
                        "fence-a",
                        false)));
    when(tickBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(tickEffectRepository.findByTickBatchId(any())).thenReturn(List.of());
    when(tickEffectRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(remoteFollowupRepository
            .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                anyLong(), any(), any(), anyLong()))
        .thenReturn(0L);
    when(remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                anyLong(), any(), any(), anyLong()))
        .thenReturn(Optional.empty());
    when(remoteFollowupDrainService.claimDueFollowups(anyLong(), any(), anyLong(), any(), anyInt()))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService.ClaimOutcome(
                List.of(), 0));
  }

  @Test
  void enqueueCommandPushesToQueue() {
    service.enqueueCommand(1L, 2L, "cmd-123", "look", false);
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(listOps).rightPush(eq("gamesession:tick:queue:1:2"), payloadCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("N|cmd-123|look", payloadCaptor.getValue());
  }

  @Test
  void enqueueCommandRejectsMissingCommandId() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> service.enqueueCommand(1L, 2L, null, "look", true));
  }

  @Test
  void purgeQueuedAutomationCommandsForScriptPatchRemovesRedisPayloadAndMarksTerminal() {
    var command = gameplayCommand("cmd-1");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSourceType("AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setCommandText("say hello");
    command.setRequiresSoloTick(false);
    when(gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1"))
        .thenReturn(List.of(command));

    long purged =
        service.purgeQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1", "rollback");

    org.junit.jupiter.api.Assertions.assertEquals(1L, purged);
    verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-1|say hello");
    org.junit.jupiter.api.Assertions.assertEquals("PURGED", command.getExecutionOutcome());
    org.junit.jupiter.api.Assertions.assertEquals("NOT_APPLIED", command.getGameplayResult());
    org.junit.jupiter.api.Assertions.assertEquals("ROLLBACK_PURGED", command.getFailureCode());
    org.junit.jupiter.api.Assertions.assertEquals("rollback", command.getFailureMessage());
    org.junit.jupiter.api.Assertions.assertNotNull(command.getCompletedAt());
    verify(gameplayCommandRepository).saveAll(List.of(command));
  }

  @Test
  void purgeQueuedAutomationCommandsForPluginVersionUsesPluginProvenance() {
    var command = gameplayCommand("cmd-2");
    command.setCommandText("emote waves");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    when(gameplayCommandRepository.findQueuedAutomationCommandsForPluginVersion(
            1L, 2L, "", "plugin-1", "plugin-v1"))
        .thenReturn(List.of(command));

    long purged =
        service.purgeQueuedAutomationCommandsForPluginVersion(
            1L, 2L, "", "plugin-1", "plugin-v1", "plugin rollback");

    org.junit.jupiter.api.Assertions.assertEquals(1L, purged);
    verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-2|emote waves");
    org.junit.jupiter.api.Assertions.assertEquals("PURGED", command.getExecutionOutcome());
    org.junit.jupiter.api.Assertions.assertEquals("plugin rollback", command.getFailureMessage());
  }

  @Test
  void processTickUsesGameplayNamespacedLockAndPendingKeys() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);

    service.processTick(1L, 2L);

    verify(valueOps)
        .setIfAbsent(eq("gamesession:tick:lock:1:2"), any(Object.class), any(Duration.class));
    verify(listOps).size("gamesession:tick:pending:1:2");
    verify(listOps).index("gamesession:tick:queue:1:2", 0);
  }

  @Test
  void processTickRecordsRemoteFollowupBacklogOverBudget() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(remoteFollowupRepository
            .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                1L, "2", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 1L))
        .thenReturn(25L);

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0,
        meterRegistry
            .get("remote_followups_backlog_over_budget_total")
            .tag("tenantId", "1")
            .tag("regionId", "2")
            .counter()
            .count());
  }

  @Test
  void processTickPublishesRemoteFollowupDueAndDrainLagByScope() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(remoteFollowupRepository
            .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                1L, "2", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 1L))
        .thenReturn(2L);
    net.firedevops.firemud.gamesession.entity.RemoteFollowup oldestDue =
        new net.firedevops.firemud.gamesession.entity.RemoteFollowup();
    oldestDue.setDueTickId(0L);
    when(remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                1L, "2", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 1L))
        .thenReturn(Optional.of(oldestDue));

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        2.0,
        meterRegistry
            .get("remote_followups_due_total")
            .tag("tenantId", "1")
            .tag("regionId", "2")
            .gauge()
            .value());
    org.junit.jupiter.api.Assertions.assertEquals(
        1000.0,
        meterRegistry
            .get("remote_followups_drain_lag_ms")
            .tag("tenantId", "1")
            .tag("regionId", "2")
            .gauge()
            .value());
  }

  @Test
  void processTickDrainsClaimedRemoteFollowupsIntoDurableBatchEffects() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(remoteFollowupDrainService.claimDueFollowups(
            eq(1L), eq("2"), eq(1L), any(String.class), eq(16)))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService.ClaimOutcome(
                List.of("followup-1"), 1));
    net.firedevops.firemud.gamesession.entity.RemoteFollowup followup =
        new net.firedevops.firemud.gamesession.entity.RemoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setOriginRegionId("origin-1");
    followup.setOriginRegionEpoch(3L);
    followup.setTargetGameInstanceId(2L);
    followup.setTargetRegionId("2");
    followup.setTargetRegionEpoch(4L);
    followup.setDueTickId(10L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-followup");
    followup.setClaimOrdinal(1L);
    followup.setCommandId("cmd-1");
    followup.setAutomationDispatchId("dispatch-1");
    followup.setAutomationWorkItemId("work-1");
    followup.setScriptId("script-1");
    followup.setScriptPatchVersion("patch-1");
    followup.setPluginId("plugin-1");
    followup.setPluginVersionId("plugin-v1");
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setPayloadKind("noop");
    followup.setRequestedCommand("LOOK");
    followup.setPayloadJson("{\"kind\":\"noop\"}");
    when(remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(any(String.class)))
        .thenReturn(List.of(followup));
    when(durableRemoteFollowupExecutionService.execute(any()))
        .thenReturn(
            new net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService
                .DurableRemoteFollowupExecutionResult(
                "ABANDONED", "REMOTE_FOLLOWUP_KIND_UNSUPPORTED", "unsupported"));
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus currentStatus =
        runtimeOwnership(1L, 2L, 1L, "fence-a", false);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(
            Optional.of(currentStatus),
            Optional.of(currentStatus),
            Optional.of(currentStatus),
            Optional.of(currentStatus));

    service.processTick(1L, 2L);

    verify(remoteFollowupDrainService)
        .claimDueFollowups(eq(1L), eq("2"), eq(1L), any(String.class), eq(16));
    ArgumentCaptor<java.util.List<net.firedevops.firemud.gamesession.entity.TickEffect>>
        effectListCaptor = ArgumentCaptor.forClass(java.util.List.class);
    verify(tickEffectRepository).saveAll(effectListCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "REMOTE_FOLLOWUP", effectListCaptor.getValue().get(0).getEffectType());
    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.TickBatch> batchCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        batchCaptor.getAllValues().stream()
            .anyMatch(batch -> "REMOTE_FOLLOWUP_DRAIN".equals(batch.getBatchSource())));
    org.junit.jupiter.api.Assertions.assertTrue(
        batchCaptor.getAllValues().stream()
            .filter(batch -> "REMOTE_FOLLOWUP_DRAIN".equals(batch.getBatchSource()))
            .anyMatch(
                batch ->
                    batch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":1")
                        && batch.getSelectedWorkManifestJson().contains("\"sourceDueTickId\":10")
                        && batch.getSelectedWorkManifestJson().contains("\"commandId\":\"cmd-1\"")
                        && batch
                            .getSelectedWorkManifestJson()
                            .contains("\"automationDispatchId\":\"dispatch-1\"")
                        && batch
                            .getSelectedWorkManifestJson()
                            .contains("\"scriptPatchVersion\":\"patch-1\"")
                        && batch.getSelectedWorkManifestJson().contains("\"worldSlug\":\"demo\"")
                        && batch.getSelectedWorkManifestJson().contains("\"pointerVersion\":17")
                        && batch.getSelectedWorkManifestJson().contains("\"payloadKind\":\"noop\"")
                        && batch
                            .getSelectedWorkManifestJson()
                            .contains("\"requestedCommand\":\"LOOK\"")));
  }

  @Test
  void enqueueCommandAddsGameplayLoggingContextWhenSessionIsBound() {
    when(listOps.rightPush(any(String.class), any(Object.class)))
        .thenAnswer(
            ignored -> {
              org.junit.jupiter.api.Assertions.assertEquals("9", MDC.get("tenantId"));
              org.junit.jupiter.api.Assertions.assertNull(MDC.get("gameInstanceId"));
              org.junit.jupiter.api.Assertions.assertNull(MDC.get("characterId"));
              return 1L;
            });

    service.enqueueCommand(9L, 2L, "cmd-ctx", "look", false);

    org.junit.jupiter.api.Assertions.assertNull(MDC.get("tenantId"));
    org.junit.jupiter.api.Assertions.assertNull(MDC.get("gameInstanceId"));
    org.junit.jupiter.api.Assertions.assertNull(MDC.get("characterId"));
  }

  @Test
  void processTickAttemptsLockAndExecutesScript() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    service.processTick(1L, 2L);
    ArgumentCaptor<RedisScript<?>> scriptCaptor = redisScriptCaptor();
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(scriptCaptor.capture(), org.mockito.ArgumentMatchers.<String>anyList());
  }

  @Test
  void processTickReleasesLockViaOwnershipCheckedScript() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);

    service.processTick(1L, 2L);

    verify(redisTemplate, never()).delete("gamesession:tick:lock:1:2");
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(any(RedisScript.class), org.mockito.ArgumentMatchers.<String>anyList());
  }

  @Test
  void lockContentionIncrementsMetric() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(false);

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("game_session_lock_contention_total").counter().count(), 0.001);
    verify(conflictTracker).recordConflict("session:1:2");
  }

  @Test
  @SuppressWarnings("unchecked")
  void slowTickIncrementsBudgetMetric() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(redisTemplate.execute(
            any(RedisScript.class), org.mockito.ArgumentMatchers.<String>anyList()))
        .thenReturn(1L);
    when(listOps.size(any(String.class))).thenReturn(0L);

    setField(service, "tickBudgetMs", -1L);

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("game_session_tick_budget_exceeded_total").counter().count(), 0.001);
  }

  @Test
  void retryQueueGaugeRecorded() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.size(any(String.class))).thenReturn(3L);
    var instance = new net.firedevops.firemud.gamesession.entity.GameInstance();
    instance.setId(2L);
    instance.setTenantId(10L);
    when(repository.findById(2L)).thenReturn(java.util.Optional.of(instance));
    service.processTick(10L, 2L);
    org.junit.jupiter.api.Assertions.assertEquals(
        3.0, meterRegistry.get("game_session_retry_queue_depth_total").gauge().value(), 0.001);
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0,
        meterRegistry.get("game_session_retry_queue_targets_with_pending").gauge().value(),
        0.001);
  }

  @Test
  void processTickCreatesDurableBatchAndEffectsForStagedCommands() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|look");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(List.of("N|cmd-1|look"));
    when(gameplayCommandRepository.findByCommandIdIn(any()))
        .thenReturn(List.of(gameplayCommand("cmd-1")));

    service.processTick(1L, 2L);

    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce())
        .save(any(net.firedevops.firemud.gamesession.entity.TickBatch.class));
    verify(tickEffectRepository).saveAll(any());
    verify(gameplayCommandRepository, org.mockito.Mockito.atLeastOnce()).saveAll(any());
  }

  @Test
  void processTickPersistsSelectedWorkManifestOnBatch() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("S|cmd-1|say \"hello\"");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("S|cmd-1|say \"hello\""));
    when(gameplayCommandRepository.findByCommandIdIn(any()))
        .thenReturn(List.of(gameplayCommand("cmd-1")));

    service.processTick(1L, 2L);

    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.TickBatch> batchCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    net.firedevops.firemud.gamesession.entity.TickBatch stagedBatch =
        batchCaptor.getAllValues().stream()
            .filter(batch -> batch.getSelectedWorkManifestJson() != null)
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertEquals(1, stagedBatch.getExpectedEffectCount());
    org.junit.jupiter.api.Assertions.assertNotNull(stagedBatch.getSelectedWorkManifestDigest());
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"effectKey\":\"command:cmd-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"requiresSoloTick\":true"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"commandDigest\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("say \"hello\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void processTickPersistsDeterministicEffectIdentity() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|look");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(List.of("N|cmd-1|look"));
    when(gameplayCommandRepository.findByCommandIdIn(any()))
        .thenReturn(List.of(gameplayCommand("cmd-1")));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(runtimeOwnership(1L, 2L, 1L, "fence-a", false)));

    service.processTick(1L, 2L);

    ArgumentCaptor<List<net.firedevops.firemud.gamesession.entity.TickEffect>> effectsCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(tickEffectRepository).saveAll(effectsCaptor.capture());
    net.firedevops.firemud.gamesession.entity.TickEffect effect = effectsCaptor.getValue().get(0);
    org.junit.jupiter.api.Assertions.assertEquals("command:cmd-1", effect.getEffectKey());
    org.junit.jupiter.api.Assertions.assertTrue(effect.getEffectId().startsWith("tfx-"));
    org.junit.jupiter.api.Assertions.assertEquals(64, effect.getEffectId().length());
  }

  @Test
  void processTickFinalizesExistingReplayBatchBeforeNewStage() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.size("gamesession:tick:pending:1:2")).thenReturn(1L);
    List<Object> replayEntries = List.of("N|cmd-1|look");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(replayEntries);
    net.firedevops.firemud.gamesession.entity.TickBatch existingBatch =
        new net.firedevops.firemud.gamesession.entity.TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(runtimeOwnership(1L, 2L, 1L, "fence-a", false)));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(java.util.Optional.of(existingBatch));
    when(tickEffectRepository.findByTickBatchId("tb-existing")).thenReturn(List.of());
    net.firedevops.firemud.gamesession.entity.GameplayCommand command = gameplayCommand("cmd-1");
    when(gameplayCommandRepository.findByCommandIdIn(any())).thenReturn(List.of(command));
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest((TickServiceImpl) service, replayEntries));

    service.processTick(1L, 2L);

    verify(tickBatchRepository)
        .findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(1L, 2L, "STAGED");
    org.junit.jupiter.api.Assertions.assertEquals("DRAINED", existingBatch.getStatus());
  }

  @Test
  void processTickRejectsStaleOwnershipBeforeDrainCommit() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|look");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(List.of("N|cmd-1|look"));
    when(gameplayCommandRepository.findByCommandIdIn(any()))
        .thenReturn(List.of(gameplayCommand("cmd-1")));
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus initialStatus =
        runtimeOwnership(1L, 2L, 1L, "fence-a", false);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus staleStatus =
        runtimeOwnership(1L, 2L, 2L, "fence-b", true);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(initialStatus), Optional.of(staleStatus));

    service.processTick(1L, 2L);

    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.TickBatch> batchCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        batchCaptor.getAllValues().stream()
            .anyMatch(
                batch ->
                    "ABANDONED".equals(batch.getStatus())
                        && "STALE_EXECUTOR_FENCE".equals(batch.getFailureCode())));
    verify(tickEffectRepository).saveAll(any());
    verify(remoteFollowupDrainService)
        .releaseClaimedFollowups(
            org.mockito.ArgumentMatchers.anyString(),
            eq("STALE_EXECUTOR_FENCE"),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void processTickRequeuesDrainedEffectsWhenFenceIsStaleBeforeApplication() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    net.firedevops.firemud.gamesession.entity.TickBatch drainedBatch =
        new net.firedevops.firemud.gamesession.entity.TickBatch();
    drainedBatch.setTickBatchId("tb-drained");
    drainedBatch.setTenantId(1L);
    drainedBatch.setGameInstanceId(2L);
    drainedBatch.setRegionEpoch(1L);
    drainedBatch.setExecutorFence("fence-a");
    drainedBatch.setStatus("DRAINED");
    drainedBatch.setCommandCount(1);
    drainedBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    drainedBatch.setCompletedAt(Instant.parse("2026-04-19T00:00:01Z"));
    net.firedevops.firemud.gamesession.entity.TickEffect drainedEffect =
        new net.firedevops.firemud.gamesession.entity.TickEffect();
    drainedEffect.setEffectId("tfx-drained");
    drainedEffect.setTickBatchId("tb-drained");
    drainedEffect.setCommandId("cmd-1");
    drainedEffect.setEffectKey("command:cmd-1");
    drainedEffect.setEffectType("GAMEPLAY_COMMAND");
    drainedEffect.setTargetAggregate("game-instance:2");
    drainedEffect.setStatus("DRAINED");
    drainedEffect.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    net.firedevops.firemud.gamesession.entity.GameplayCommand command = gameplayCommand("cmd-1");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setSessionId(77L);
    command.setCommandName("MOVE");
    command.setCommandText("north");
    command.setSanitizedCommandText("north");
    command.setRequiresSoloTick(false);
    command.setSourceType("AUTOMATION");
    when(tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            1L, 2L, "DRAINED"))
        .thenReturn(List.of(drainedBatch));
    when(tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc("tb-drained", "DRAINED"))
        .thenReturn(List.of(drainedEffect));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(runtimeOwnership(1L, 2L, 2L, "fence-b", false)));

    service.processTick(1L, 2L);

    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-1|north");
    org.junit.jupiter.api.Assertions.assertEquals("ABANDONED", drainedBatch.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals("ABANDONED", drainedEffect.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals("RETRY_QUEUED", command.getExecutionOutcome());
    verify(durableGameplayCommandExecutionService, never()).execute(any(), any());
    verify(remoteFollowupDrainService)
        .releaseClaimedFollowups(
            eq("tb-drained"), eq("STALE_EXECUTOR_FENCE"), org.mockito.ArgumentMatchers.anyString());
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0,
        meterRegistry
            .get("tick_requeued_action_total")
            .tag("source", "automation")
            .counter()
            .count(),
        0.001);
  }

  @Test
  void processTickUpdatesLastCommittedBatchOnDurableOwnershipRow() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|look");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(List.of("N|cmd-1|look"));
    when(gameplayCommandRepository.findByCommandIdIn(any()))
        .thenReturn(List.of(gameplayCommand("cmd-1")));
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus currentStatus =
        runtimeOwnership(1L, 2L, 1L, "fence-a", false);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(
            Optional.of(currentStatus), Optional.of(currentStatus), Optional.of(currentStatus));

    service.processTick(1L, 2L);

    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus> statusCaptor =
        ArgumentCaptor.forClass(
            net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus.class);
    verify(runtimeRegionStatusRepository, org.mockito.Mockito.atLeastOnce())
        .save(statusCaptor.capture());
    org.junit.jupiter.api.Assertions.assertTrue(
        statusCaptor.getAllValues().stream()
            .anyMatch(
                status ->
                    "test-instance".equals(status.getOwnerInstanceId())
                        && status.getLastCommittedTickBatchId() != null
                        && !status.getLastCommittedTickBatchId().isBlank()));
  }

  @Test
  void processTickAdvancesAndPublishesRuntimeTickProgress() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus currentStatus =
        runtimeOwnership(1L, 2L, 4L, "fence-a", false);
    currentStatus.setLastCommittedTickId(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(currentStatus), Optional.of(currentStatus));

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals(8L, currentStatus.getLastCommittedTickId());
    ArgumentCaptor<ObserveRuntimeTickProgressRequest> requestCaptor =
        ArgumentCaptor.forClass(ObserveRuntimeTickProgressRequest.class);
    verify(automationScriptingClient).observeRuntimeTickProgress(requestCaptor.capture());
    ObserveRuntimeTickProgressRequest request = requestCaptor.getValue();
    org.junit.jupiter.api.Assertions.assertEquals("1", request.getTenantId());
    org.junit.jupiter.api.Assertions.assertEquals("2", request.getGameInstanceId());
    org.junit.jupiter.api.Assertions.assertEquals("2", request.getRegionId());
    org.junit.jupiter.api.Assertions.assertEquals(4L, request.getRegionEpoch());
    org.junit.jupiter.api.Assertions.assertEquals(8L, request.getTickId());
    verify(remoteFollowupRuntimeService).reconcileResults(1L, "2", 4L);
    verify(remoteFollowupRuntimeService).reconcileTimeouts(1L, "2", 4L, 8L);
  }

  @Test
  void processTickReconcilesRemoteFollowupTimeoutsAfterTickAdvance() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(remoteFollowupRuntimeService.reconcileResults(1L, "2", 4L)).thenReturn(1);
    when(remoteFollowupRuntimeService.reconcileTimeouts(1L, "2", 4L, 8L)).thenReturn(2);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus currentStatus =
        runtimeOwnership(1L, 2L, 4L, "fence-a", false);
    currentStatus.setLastCommittedTickId(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(currentStatus), Optional.of(currentStatus));

    service.processTick(1L, 2L);

    verify(remoteFollowupRuntimeService).reconcileResults(1L, "2", 4L);
    verify(remoteFollowupRuntimeService).reconcileTimeouts(1L, "2", 4L, 8L);
  }

  @Test
  void processTickStillReconcilesRemoteResultsWhileOwnershipPaused() {
    when(remoteFollowupRuntimeService.reconcileResults(1L, "2", 4L)).thenReturn(1);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus currentStatus =
        runtimeOwnership(1L, 2L, 4L, "fence-a", true);
    currentStatus.setLastCommittedTickId(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.of(currentStatus));

    service.processTick(1L, 2L);

    verify(remoteFollowupRuntimeService).reconcileResults(1L, "2", 4L);
    verify(remoteFollowupRuntimeService, never())
        .reconcileTimeouts(anyLong(), any(), anyLong(), anyLong());
    verify(valueOps, never())
        .setIfAbsent(any(String.class), any(Object.class), any(Duration.class));
  }

  @Test
  void processTickPersistsComparableOrderingInSelectedWorkManifest() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|say hello");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-1|say hello"));
    net.firedevops.firemud.gamesession.entity.GameplayCommand command = gameplayCommand("cmd-1");
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId("dispatch-1");
    command.setAutomationWorkItemId("work-1");
    command.setScriptId("script-1");
    command.setScriptPatchVersion("patch-1");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    command.setTargetEntityId("entity-1");
    command.setRegionId("region-1");
    command.setRegionEpoch(4L);
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

    service.processTick(1L, 2L);

    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.TickBatch> batchCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    net.firedevops.firemud.gamesession.entity.TickBatch stagedBatch =
        batchCaptor.getAllValues().stream()
            .filter(batch -> "FRESH_STAGE".equals(batch.getBatchSource()))
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"regionId\":\"2\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"enqueueSeq\":77"));
    org.junit.jupiter.api.Assertions.assertEquals("2", stagedBatch.getRegionId());
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"sourceType\":\"AUTOMATION\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":77"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"automationDispatchId\":\"dispatch-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"automationWorkItemId\":\"work-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"scriptId\":\"script-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"scriptPatchVersion\":\"patch-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"pluginId\":\"plugin-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"pluginVersionId\":\"plugin-v1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"targetEntityId\":\"entity-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"regionId\":\"region-1\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"regionEpoch\":4"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"dueTickId\":14"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"queueSourceDueTickId\":14"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"originSourceKind\":\"SCHEDULE_TIMER\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"originSourceState\":\"SCHEDULE_DUE_CLAIMED\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"originSourceOrdinal\":5000"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"originSourceDueAtMs\":5000"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"playableStateScope\":\"SHARED\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"worldSlug\":\"demo\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"realmSlug\":\"production\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"pointerVersion\":17"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"sourceState\":\"REDIS_PENDING_CLAIMED\""));
    org.junit.jupiter.api.Assertions.assertEquals("GAMEPLAY_COMMAND", command.getQueueSourceKind());
    org.junit.jupiter.api.Assertions.assertEquals(
        "REDIS_PENDING_CLAIMED", command.getQueueSourceState());
    org.junit.jupiter.api.Assertions.assertEquals(77L, command.getQueueSourceOrdinal());
    org.junit.jupiter.api.Assertions.assertEquals(14L, command.getQueueSourceDueTickId());
    org.junit.jupiter.api.Assertions.assertNull(command.getQueueSourceDueAtMs());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<net.firedevops.firemud.gamesession.entity.TickEffect>> effectCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(tickEffectRepository).saveAll(effectCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "entity:entity-1", effectCaptor.getValue().get(0).getTargetAggregate());
  }

  @Test
  void processTickPersistsRetryClaimMetadataInSelectedWorkManifest() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|retry look");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-1|retry look"));
    net.firedevops.firemud.gamesession.entity.GameplayCommand command = gameplayCommand("cmd-1");
    command.setCommandText("retry look");
    command.setSanitizedCommandText("retry look");
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId("dispatch-2");
    command.setScriptPatchVersion("patch-2");
    command.setTargetEntityId("entity-2");
    command.setDueTickId(21L);
    command.setEnqueueSeq(78L);
    command.setOriginSourceKind("SCHEDULE_TIMER");
    command.setOriginSourceState("SCHEDULE_DUE_CLAIMED");
    command.setOriginSourceOrdinal(6000L);
    command.setOriginSourceDueAtMs(6000L);
    command.setExecutionOutcome("RETRY_QUEUED");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    service.processTick(1L, 2L);

    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.TickBatch> batchCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    net.firedevops.firemud.gamesession.entity.TickBatch stagedBatch =
        batchCaptor.getAllValues().stream()
            .filter(batch -> "FRESH_STAGE".equals(batch.getBatchSource()))
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"regionId\":\"2\""));
    org.junit.jupiter.api.Assertions.assertEquals("2", stagedBatch.getRegionId());
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"sourceKind\":\"GAMEPLAY_RETRY\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":78"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"sourceState\":\"REDIS_RETRY_CLAIMED\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"automationDispatchId\":\"dispatch-2\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"scriptPatchVersion\":\"patch-2\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"targetEntityId\":\"entity-2\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"dueTickId\":21"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"queueSourceDueTickId\":21"));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch
            .getSelectedWorkManifestJson()
            .contains("\"originSourceKind\":\"SCHEDULE_TIMER\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        stagedBatch.getSelectedWorkManifestJson().contains("\"originSourceDueAtMs\":6000"));
    org.junit.jupiter.api.Assertions.assertEquals("GAMEPLAY_RETRY", command.getQueueSourceKind());
    org.junit.jupiter.api.Assertions.assertEquals(
        "REDIS_RETRY_CLAIMED", command.getQueueSourceState());
    org.junit.jupiter.api.Assertions.assertEquals(78L, command.getQueueSourceOrdinal());
    org.junit.jupiter.api.Assertions.assertEquals(21L, command.getQueueSourceDueTickId());
    org.junit.jupiter.api.Assertions.assertNull(command.getQueueSourceDueAtMs());
  }

  @Test
  void processTickPersistsCharacterTargetAggregateWhenCharacterIdentityIsKnown() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.index("gamesession:tick:queue:1:2", 0)).thenReturn("N|cmd-1|say hello");
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-1|say hello"));
    net.firedevops.firemud.gamesession.entity.GameplayCommand command = gameplayCommand("cmd-1");
    command.setSourceType("AUTOMATION");
    command.setCharacterId(44L);
    command.setTargetEntityId("44");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    service.processTick(1L, 2L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<net.firedevops.firemud.gamesession.entity.TickEffect>> effectCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(tickEffectRepository).saveAll(effectCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "character:44", effectCaptor.getValue().get(0).getTargetAggregate());
  }

  @Test
  void processTickAbandonsReplayBatchWhenPendingDigestNoLongerMatchesSealedManifest() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.size("gamesession:tick:pending:1:2")).thenReturn(1L);
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(List.of("N|cmd-1|look"));
    net.firedevops.firemud.gamesession.entity.TickBatch existingBatch =
        new net.firedevops.firemud.gamesession.entity.TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setBatchSource("FRESH_STAGE");
    String sealedManifest = replayManifestJson((TickServiceImpl) service, List.of("N|cmd-1|look"));
    existingBatch.setSelectedWorkManifestJson(sealedManifest);
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest((TickServiceImpl) service, List.of("N|cmd-1|look")));
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    net.firedevops.firemud.gamesession.entity.GameplayCommand command = gameplayCommand("cmd-1");
    command.setEnqueueSeq(5L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));
    when(tickEffectRepository.findByTickBatchId("tb-existing")).thenReturn(List.of());

    service.processTick(1L, 2L);

    org.junit.jupiter.api.Assertions.assertEquals("ABANDONED", existingBatch.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals(
        "MANIFEST_MISMATCH", existingBatch.getFailureCode());
    verify(redisTemplate).delete("gamesession:tick:pending:1:2");
    verify(listOps).rightPush("gamesession:tick:pending:1:2", "N|cmd-1|look");
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("tick_manifest_mismatch_total").counter().count(), 0.001);
  }

  @Test
  void processTickRequeuesRedisOnlyEntriesWhenReplayFallsBackToSealedManifest() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.size("gamesession:tick:pending:1:2")).thenReturn(2L);
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1))
        .thenReturn(List.of("N|cmd-1|look", "N|cmd-2|wave"));
    net.firedevops.firemud.gamesession.entity.TickBatch existingBatch =
        new net.firedevops.firemud.gamesession.entity.TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setBatchSource("FRESH_STAGE");
    String sealedManifest = replayManifestJson((TickServiceImpl) service, List.of("N|cmd-1|look"));
    existingBatch.setSelectedWorkManifestJson(sealedManifest);
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest((TickServiceImpl) service, List.of("N|cmd-1|look")));
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    net.firedevops.firemud.gamesession.entity.GameplayCommand first = gameplayCommand("cmd-1");
    first.setEnqueueSeq(5L);
    net.firedevops.firemud.gamesession.entity.GameplayCommand second = gameplayCommand("cmd-2");
    second.setCommandText("wave");
    second.setSanitizedCommandText("wave");
    second.setEnqueueSeq(6L);
    second.setSourceType("AUTOMATION");
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1", "cmd-2")))
        .thenReturn(List.of(first, second));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1"))).thenReturn(List.of(first));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-2"))).thenReturn(List.of(second));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    service.processTick(1L, 2L);

    verify(redisTemplate).delete("gamesession:tick:pending:1:2");
    verify(listOps).rightPush("gamesession:tick:pending:1:2", "N|cmd-1|look");
    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-2|wave");
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0,
        meterRegistry
            .get("tick_requeued_action_total")
            .tag("source", "automation")
            .counter()
            .count(),
        0.001);
  }

  private static net.firedevops.firemud.gamesession.entity.GameplayCommand gameplayCommand(
      String commandId) {
    var command = new net.firedevops.firemud.gamesession.entity.GameplayCommand();
    command.setCommandId(commandId);
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setAttemptCount(1);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    return command;
  }

  private static net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus runtimeOwnership(
      Long tenantId, Long gameInstanceId, long regionEpoch, String executorFence, boolean paused) {
    var status = new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
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

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<RedisScript<?>> redisScriptCaptor() {
    return (ArgumentCaptor<RedisScript<?>>)
        (ArgumentCaptor<?>) ArgumentCaptor.forClass(RedisScript.class);
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

  private static String replayManifestDigest(TickServiceImpl service, List<Object> rawEntries) {
    String manifest = replayManifestJson(service, rawEntries);
    return shortHash(service, manifest);
  }

  private static String replayManifestJson(TickServiceImpl service, List<Object> rawEntries) {
    try {
      var parseMethod = TickServiceImpl.class.getDeclaredMethod("parseQueuedCommand", String.class);
      parseMethod.setAccessible(true);
      List<Object> entries = new java.util.ArrayList<>();
      for (Object rawEntry : rawEntries) {
        entries.add(parseMethod.invoke(service, rawEntry.toString()));
      }
      var selectionsMethod =
          TickServiceImpl.class.getDeclaredMethod("commandSelections", List.class);
      selectionsMethod.setAccessible(true);
      Object selections = selectionsMethod.invoke(service, entries);
      var manifestMethod =
          TickServiceImpl.class.getDeclaredMethod("selectedWorkManifest", Long.class, List.class);
      manifestMethod.setAccessible(true);
      return (String) manifestMethod.invoke(service, 2L, selections);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to compute replay manifest json", e);
    }
  }

  private static String shortHash(TickServiceImpl service, String value) {
    try {
      var hashMethod = TickServiceImpl.class.getDeclaredMethod("shortHash", String.class);
      hashMethod.setAccessible(true);
      return (String) hashMethod.invoke(service, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to compute short hash", e);
    }
  }
}
