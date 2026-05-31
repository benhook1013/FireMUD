package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TickQueueControlServiceTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private ValueOperations<String, Object> valueOps;
  private GameInstanceRepository gameInstanceRepository;
  private GameplayCommandRepository gameplayCommandRepository;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private SessionContextService sessionContextService;
  private TickQueueControlService service;
  private Logger logger;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(ListOperations.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    sessionContextService = mock(SessionContextService.class);
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
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            runtimeIdentity,
            sessionContextService);
    logger = mock(Logger.class);
  }

  @Test
  void enqueueCommandPushesToQueue() {
    service.enqueueCommand(1L, 2L, "cmd-123", "look", false);

    verify(listOps).rightPush("gamesession:tick:queue:1:2", "N|cmd-123|look");
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

    long purged =
        service.purgeQueuedAutomationCommandsForScriptPatch(
            1L, 2L, "region-1", "patch-1", "rollback", logger);

    assertEquals(1L, purged);
    verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-1|say hello");
    assertEquals("PURGED", command.getExecutionOutcome());
    assertEquals("NOT_APPLIED", command.getGameplayResult());
    assertEquals(TickQueueControlService.PURGED_FAILURE_CODE, command.getFailureCode());
    assertEquals("rollback", command.getFailureMessage());
    assertNotNull(command.getCompletedAt());
    verify(gameplayCommandRepository).saveAll(List.of(command));
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

    long purged =
        service.purgeQueuedAutomationCommandsForPluginVersion(
            1L, 2L, "", "plugin-1", "plugin-v1", "plugin rollback", logger);

    assertEquals(1L, purged);
    verify(listOps).remove("gamesession:tick:queue:1:2", 0, "N|cmd-2|emote waves");
    assertEquals("PURGED", command.getExecutionOutcome());
    assertEquals("plugin rollback", command.getFailureMessage());
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
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(9L, 2L))
        .thenReturn(Optional.of(existingForPause), Optional.of(existingForResume));

    service.pauseTicksForGameInstance(2L, "maintenance", logger);
    assertTrue(service.isPaused(2L, false));

    service.resumeTicksForGameInstance(2L, "resume", logger);
    assertFalse(service.isPaused(2L, false));

    ArgumentCaptor<RuntimeRegionStatus> statusCaptor =
        ArgumentCaptor.forClass(RuntimeRegionStatus.class);
    verify(runtimeRegionStatusRepository, org.mockito.Mockito.atLeast(2))
        .save(statusCaptor.capture());
    List<RuntimeRegionStatus> savedStatuses = statusCaptor.getAllValues();
    assertTrue(
        savedStatuses.stream()
            .anyMatch(status -> status.getRegionEpoch() == 5L && status.isPaused()));
    assertTrue(
        savedStatuses.stream()
            .anyMatch(status -> status.getRegionEpoch() == 6L && !status.isPaused()));
    assertTrue(
        savedStatuses.stream()
            .allMatch(status -> "test-instance".equals(status.getOwnerInstanceId())));
  }

  @Test
  void queryStateUsesSessionContextTenantBeforeRepositoryFallback() {
    when(sessionContextService.findBySessionId(7L))
        .thenReturn(Optional.of(new SessionContext(7L, 11L, 0L, 0L, 0L, null)));
    when(valueOps.get("session:11:7")).thenReturn("{\"status\":\"ready\"}");

    String state = service.queryState(7L);

    assertEquals("{\"status\":\"ready\"}", state);
  }

  @Test
  void queryStateFallsBackToGameInstanceTenantWhenSessionContextMissing() {
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(12L);
    when(sessionContextService.findBySessionId(7L)).thenReturn(Optional.empty());
    when(gameInstanceRepository.findById(7L)).thenReturn(Optional.of(instance));
    when(valueOps.get("session:12:7")).thenReturn("{\"status\":\"queued\"}");

    String state = service.queryState(7L);

    assertEquals("{\"status\":\"queued\"}", state);
  }

  @Test
  void observeOwnershipCreatesDefaultRuntimeRowWithRuntimeIdentity() {
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.empty());

    TickQueueControlService.OwnershipSnapshot snapshot = service.observeOwnership(1L, 2L);

    assertEquals("2", snapshot.regionId());
    assertEquals(1L, snapshot.regionEpoch());
    assertTrue(snapshot.executorFence().startsWith("fence-"));
    ArgumentCaptor<RuntimeRegionStatus> statusCaptor =
        ArgumentCaptor.forClass(RuntimeRegionStatus.class);
    verify(runtimeRegionStatusRepository).save(statusCaptor.capture());
    assertEquals("game-session-service", statusCaptor.getValue().getOwnerService());
    assertEquals("test-instance", statusCaptor.getValue().getOwnerInstanceId());
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
