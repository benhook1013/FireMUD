package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.ArgumentMatchers.any;
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
  private net.firedevops.firemud.gamesession.repository.TickBatchRepository tickBatchRepository;
  private net.firedevops.firemud.gamesession.repository.TickEffectRepository tickEffectRepository;
  private SessionContextService sessionContextService;
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
    tickBatchRepository =
        mock(net.firedevops.firemud.gamesession.repository.TickBatchRepository.class);
    tickEffectRepository =
        mock(net.firedevops.firemud.gamesession.repository.TickEffectRepository.class);
    sessionContextService = mock(SessionContextService.class);
    service =
        new TickServiceImpl(
            redisTemplate,
            meterRegistry,
            conflictTracker,
            repository,
            gameplayCommandRepository,
            runtimeIdentity,
            runtimeRegionStatusRepository,
            tickBatchRepository,
            tickEffectRepository,
            sessionContextService);
    ((TickServiceImpl) service).init();
    var instance = new net.firedevops.firemud.gamesession.entity.GameInstance();
    instance.setTenantId(1L);
    when(repository.findById(anyLong())).thenReturn(java.util.Optional.of(instance));
    when(gameplayCommandRepository.findByCommandIdIn(any())).thenReturn(List.of());
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(tickBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(tickEffectRepository.findByTickBatchId(any())).thenReturn(List.of());
    when(tickEffectRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void enqueueCommandPushesToQueue() {
    service.enqueueCommand(1L, 2L, "cmd-123", "look", false);
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(listOps).rightPush(eq("gamesession:tick:queue:1:2"), payloadCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("N|cmd-123|look", payloadCaptor.getValue());
  }

  @Test
  void enqueueCommandKeepsLegacyPayloadShapeWhenCommandIdIsAbsent() {
    service.enqueueCommand(1L, 2L, "look", true);
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(listOps).rightPush(eq("gamesession:tick:queue:1:2"), payloadCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("S|-|look", payloadCaptor.getValue());
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
  void enqueueCommandAddsGameplayLoggingContextWhenSessionIsBound() {
    when(listOps.rightPush(any(String.class), any(Object.class)))
        .thenAnswer(
            ignored -> {
              org.junit.jupiter.api.Assertions.assertEquals("9", MDC.get("tenantId"));
              org.junit.jupiter.api.Assertions.assertNull(MDC.get("gameInstanceId"));
              org.junit.jupiter.api.Assertions.assertNull(MDC.get("characterId"));
              return 1L;
            });

    service.enqueueCommand(9L, 2L, "look", false);

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

    setField(service, "tickBudgetMs", 0L);

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
  void processTickFinalizesExistingReplayBatchBeforeNewStage() {
    when(valueOps.setIfAbsent(any(String.class), any(Object.class), any(Duration.class)))
        .thenReturn(true);
    when(listOps.size("gamesession:tick:pending:1:2")).thenReturn(1L);
    when(listOps.range("gamesession:tick:pending:1:2", 0, -1)).thenReturn(List.of("N|cmd-1|look"));
    net.firedevops.firemud.gamesession.entity.TickBatch existingBatch =
        new net.firedevops.firemud.gamesession.entity.TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setStatus("STAGED");
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(java.util.Optional.of(existingBatch));
    when(tickEffectRepository.findByTickBatchId("tb-existing")).thenReturn(List.of());
    when(gameplayCommandRepository.findByCommandIdIn(any()))
        .thenReturn(List.of(gameplayCommand("cmd-1")));

    service.processTick(1L, 2L);

    verify(tickBatchRepository)
        .findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(1L, 2L, "STAGED");
    org.junit.jupiter.api.Assertions.assertEquals("DRAINED", existingBatch.getStatus());
  }

  private static net.firedevops.firemud.gamesession.entity.GameplayCommand gameplayCommand(
      String commandId) {
    var command = new net.firedevops.firemud.gamesession.entity.GameplayCommand();
    command.setCommandId(commandId);
    command.setAttemptCount(1);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    return command;
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
}
