package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.util.concurrent.atomic.AtomicReference;
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
    verify(coordinatorRepository)
        .save(
            argThat(
                coordinator ->
                    "SHARED".equals(coordinator.getPlayableStateScope())
                        && "dispatch-1".equals(coordinator.getAutomationDispatchId())
                        && "work-1".equals(coordinator.getAutomationWorkItemId())
                        && "script-1".equals(coordinator.getScriptId())
                        && "patch-1".equals(coordinator.getScriptPatchVersion())
                        && "plugin-1".equals(coordinator.getPluginId())
                        && "plugin-v1".equals(coordinator.getPluginVersionId())
                        && "demo".equals(coordinator.getWorldSlug())
                        && "production".equals(coordinator.getRealmSlug())
                        && Long.valueOf(17L).equals(coordinator.getPointerVersion())));
    verify(followupRepository)
        .save(
            argThat(
                followup ->
                    "SHARED".equals(followup.getPlayableStateScope())
                        && "cmd-1".equals(followup.getCommandId())
                        && "entity:entity-9".equals(followup.getClaimTargetAggregate())
                        && "enqueue_automation_command".equals(followup.getPayloadKind())
                        && "LOOK".equals(followup.getRequestedCommand())
                        && followup.isRequiresSoloTick()
                        && "REMOTE_FOLLOWUP".equals(followup.getOriginSourceKind())
                        && "TARGET_REGION_EXECUTED".equals(followup.getOriginSourceState())
                        && Long.valueOf(44L).equals(followup.getOriginSourceOrdinal())
                        && Long.valueOf(22L).equals(followup.getOriginSourceDueTickId())
                        && Long.valueOf(1700L).equals(followup.getOriginSourceDueAtMs())
                        && "REMOTE_FOLLOWUP".equals(followup.getQueueSourceKind())
                        && "TARGET_REGION_SCHEDULED".equals(followup.getQueueSourceState())
                        && followup.getQueueSourceOrdinal() == null
                        && Long.valueOf(22L).equals(followup.getQueueSourceDueTickId())
                        && followup.getQueueSourceDueAtMs() == null
                        && "dispatch-1".equals(followup.getAutomationDispatchId())
                        && "work-1".equals(followup.getAutomationWorkItemId())
                        && "script-1".equals(followup.getScriptId())
                        && "patch-1".equals(followup.getScriptPatchVersion())
                        && "plugin-1".equals(followup.getPluginId())
                        && "plugin-v1".equals(followup.getPluginVersionId())
                        && "demo".equals(followup.getWorldSlug())
                        && "production".equals(followup.getRealmSlug())
                        && Long.valueOf(17L).equals(followup.getPointerVersion())));
    verify(valueOperations).set("remote:1:entity-9", "1", java.time.Duration.ofMillis(60_000L));
  }

  @Test
  void scheduleFollowupUsesRequestMetadataWhenCommandRowIsMissing() {
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.empty());
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        service.scheduleFollowup(scheduleRequest());

    assertTrue(outcome.coordinatorCreated());
    assertTrue(outcome.followupCreated());
    verify(coordinatorRepository)
        .save(
            argThat(
                coordinator ->
                    "SHARED".equals(coordinator.getPlayableStateScope())
                        && "dispatch-1".equals(coordinator.getAutomationDispatchId())
                        && "work-1".equals(coordinator.getAutomationWorkItemId())
                        && "script-1".equals(coordinator.getScriptId())
                        && "patch-1".equals(coordinator.getScriptPatchVersion())
                        && "plugin-1".equals(coordinator.getPluginId())
                        && "plugin-v1".equals(coordinator.getPluginVersionId())
                        && "demo".equals(coordinator.getWorldSlug())
                        && "production".equals(coordinator.getRealmSlug())
                        && Long.valueOf(17L).equals(coordinator.getPointerVersion())));
    verify(followupRepository)
        .save(
            argThat(
                followup ->
                    "SHARED".equals(followup.getPlayableStateScope())
                        && "cmd-1".equals(followup.getCommandId())
                        && "entity:entity-9".equals(followup.getClaimTargetAggregate())
                        && "enqueue_automation_command".equals(followup.getPayloadKind())
                        && "LOOK".equals(followup.getRequestedCommand())
                        && followup.isRequiresSoloTick()
                        && "REMOTE_FOLLOWUP".equals(followup.getQueueSourceKind())
                        && "TARGET_REGION_SCHEDULED".equals(followup.getQueueSourceState())
                        && followup.getQueueSourceOrdinal() == null
                        && Long.valueOf(22L).equals(followup.getQueueSourceDueTickId())
                        && followup.getQueueSourceDueAtMs() == null
                        && "dispatch-1".equals(followup.getAutomationDispatchId())
                        && "work-1".equals(followup.getAutomationWorkItemId())
                        && "script-1".equals(followup.getScriptId())
                        && "patch-1".equals(followup.getScriptPatchVersion())
                        && "plugin-1".equals(followup.getPluginId())
                        && "plugin-v1".equals(followup.getPluginVersionId())
                        && "demo".equals(followup.getWorldSlug())
                        && "production".equals(followup.getRealmSlug())
                        && Long.valueOf(17L).equals(followup.getPointerVersion())));
  }

  @Test
  void scheduleFollowupDropsPartialRoutingBundle() {
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.empty());
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        service.scheduleFollowup(
            new RemoteFollowupRuntimeService.ScheduleRequest(
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
                "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}",
                "enqueue_automation_command",
                "LOOK",
                false,
                "SHARED",
                "demo",
                "production",
                null,
                "patch-1",
                "plugin-1",
                "plugin-v1",
                "dispatch-1",
                "work-1",
                "script-1",
                "REMOTE_FOLLOWUP",
                "TARGET_REGION_EXECUTED",
                44L,
                22L,
                1700L));

    assertTrue(outcome.coordinatorCreated());
    assertTrue(outcome.followupCreated());
    verify(coordinatorRepository)
        .save(
            argThat(
                coordinator ->
                    coordinator.getPlayableStateScope() == null
                        && coordinator.getWorldSlug() == null
                        && coordinator.getRealmSlug() == null
                        && coordinator.getPointerVersion() == null));
    verify(followupRepository)
        .save(
            argThat(
                followup ->
                    followup.getPlayableStateScope() == null
                        && followup.getWorldSlug() == null
                        && followup.getRealmSlug() == null
                        && followup.getPointerVersion() == null));
  }

  @Test
  void scheduleFollowupAllowsRetryWhenPartialRequestCollapsedToStoredAbsentRoutingBundle() {
    AtomicReference<RemoteCommandCoordinator> storedCoordinator = new AtomicReference<>();
    AtomicReference<RemoteFollowup> storedFollowup = new AtomicReference<>();
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenAnswer(invocation -> Optional.ofNullable(storedCoordinator.get()));
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenAnswer(invocation -> Optional.ofNullable(storedFollowup.get()));
    when(coordinatorRepository.save(any()))
        .thenAnswer(
            invocation -> {
              RemoteCommandCoordinator coordinator = invocation.getArgument(0);
              storedCoordinator.set(coordinator);
              return coordinator;
            });
    when(followupRepository.save(any()))
        .thenAnswer(
            invocation -> {
              RemoteFollowup followup = invocation.getArgument(0);
              storedFollowup.set(followup);
              return followup;
            });
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ScheduleRequest request =
        new RemoteFollowupRuntimeService.ScheduleRequest(
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
            "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\",\"requiresSoloTick\":true}",
            "enqueue_automation_command",
            "LOOK",
            true,
            "SHARED",
            "demo",
            null,
            null,
            "patch-1",
            "plugin-1",
            "plugin-v1",
            "dispatch-1",
            "work-1",
            "script-1",
            "REMOTE_FOLLOWUP",
            "TARGET_REGION_EXECUTED",
            44L,
            22L,
            1700L);

    RemoteFollowupRuntimeService.ScheduleOutcome firstOutcome = service.scheduleFollowup(request);
    RemoteFollowupRuntimeService.ScheduleOutcome outcome = service.scheduleFollowup(request);

    assertTrue(firstOutcome.coordinatorCreated());
    assertTrue(firstOutcome.followupCreated());
    assertFalse(outcome.coordinatorCreated());
    assertFalse(outcome.followupCreated());
    assertEquals("coord-1", outcome.coordinatorId());
    assertEquals("followup-1", outcome.followupId());
    assertEquals(null, storedCoordinator.get().getPlayableStateScope());
    assertEquals(null, storedCoordinator.get().getWorldSlug());
    assertEquals(null, storedCoordinator.get().getRealmSlug());
    assertEquals(null, storedCoordinator.get().getPointerVersion());
    assertEquals(null, storedFollowup.get().getPlayableStateScope());
    assertEquals(null, storedFollowup.get().getWorldSlug());
    assertEquals(null, storedFollowup.get().getRealmSlug());
    assertEquals(null, storedFollowup.get().getPointerVersion());
  }

  @Test
  void scheduleFollowupAllowsRetryWhenLegacyStoredPartialRoutingBundleCollapsesToEmpty() {
    RemoteCommandCoordinator existingCoordinator = coordinator();
    existingCoordinator.setPointerVersion(null);
    RemoteFollowup existingFollowup = followup();
    existingFollowup.setTargetEntityId("entity-9");
    existingFollowup.setPayloadJson(
        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\",\"requiresSoloTick\":true}");
    existingFollowup.setPayloadKind("enqueue_automation_command");
    existingFollowup.setRequestedCommand("LOOK");
    existingFollowup.setRequiresSoloTick(true);
    existingFollowup.setPointerVersion(null);
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.of(existingCoordinator));
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.of(existingFollowup));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        service.scheduleFollowup(
            new RemoteFollowupRuntimeService.ScheduleRequest(
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
                "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\",\"requiresSoloTick\":true}",
                "enqueue_automation_command",
                "LOOK",
                true,
                "SHARED",
                "demo",
                "production",
                null,
                "patch-1",
                "plugin-1",
                "plugin-v1",
                "dispatch-1",
                "work-1",
                "script-1",
                "REMOTE_FOLLOWUP",
                "TARGET_REGION_EXECUTED",
                44L,
                22L,
                1700L));

    assertFalse(outcome.coordinatorCreated());
    assertFalse(outcome.followupCreated());
    assertEquals("coord-1", outcome.coordinatorId());
    assertEquals("followup-1", outcome.followupId());
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
                        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}",
                        "enqueue_automation_command",
                        "LOOK",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

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
                        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}",
                        "enqueue_automation_command",
                        "LOOK",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("effect_key already maps to a different remote execution scope", ex.getMessage());
  }

  @Test
  void scheduleFollowupRejectsCoordinatorMetadataRewriteOnRetry() {
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
                        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}",
                        "enqueue_automation_command",
                        "LOOK",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-2",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("command_id already maps to different remote followup metadata", ex.getMessage());
    verify(followupRepository, never())
        .findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            anyLong(), anyString(), anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsFollowupPayloadRewriteOnRetry() {
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
                        "{\"kind\":\"enqueue_automation_command\",\"command\":\"SAY hi\"}",
                        "enqueue_automation_command",
                        "SAY hi",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("effect_key already maps to different remote followup metadata", ex.getMessage());
  }

  @Test
  void scheduleFollowupAcceptsExplicitPayloadAuthorityWithoutPayloadJson() {
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.empty());
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        service.scheduleFollowup(
            new RemoteFollowupRuntimeService.ScheduleRequest(
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
                null,
                "enqueue_automation_command",
                "LOOK",
                true,
                "SHARED",
                "demo",
                "production",
                17L,
                "patch-1",
                "plugin-1",
                "plugin-v1",
                "dispatch-1",
                "work-1",
                "script-1",
                "REMOTE_FOLLOWUP",
                "TARGET_REGION_EXECUTED",
                44L,
                22L,
                1700L));

    assertTrue(outcome.coordinatorCreated());
    assertTrue(outcome.followupCreated());
    verify(followupRepository)
        .save(
            argThat(
                followup ->
                    followup.getPayloadJson() == null
                        && "enqueue_automation_command".equals(followup.getPayloadKind())
                        && "LOOK".equals(followup.getRequestedCommand())
                        && followup.isRequiresSoloTick()));
  }

  @Test
  void scheduleFollowupAcceptsTriggerScriptEventPayload() {
    when(coordinatorRepository.findByTenantIdAndCommandId(1L, "cmd-1"))
        .thenReturn(Optional.empty());
    when(followupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            1L, "region-b", 8L, "effect-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        service.scheduleFollowup(triggerScriptEventScheduleRequest());

    assertTrue(outcome.coordinatorCreated());
    assertTrue(outcome.followupCreated());
    verify(followupRepository)
        .save(
            argThat(
                followup ->
                    "trigger_script_event".equals(followup.getPayloadKind())
                        && followup.getRequestedCommand() == null
                        && "321".equals(followup.getTargetEntityId())
                        && "onEnterRegion".equals(followup.getEventType())
                        && "v1".equals(followup.getEventSchemaVersion())
                        && "remote-enter-1".equals(followup.getScriptEventId())
                        && "game-session:onEnterRegion:9:8:remote-enter-1"
                            .equals(followup.getReadSnapshotToken())
                        && "{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}"
                            .equals(followup.getEventPayloadJson())));
  }

  @Test
  void scheduleFollowupRejectsConflictingExplicitPayloadKindAndJson() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
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
                        "{\"kind\":\"enqueue_gameplay_command\",\"command\":\"LOOK\"}",
                        "enqueue_automation_command",
                        "LOOK",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("payload_json kind does not match payload_kind", ex.getMessage());
    verify(coordinatorRepository, never()).findByTenantIdAndCommandId(anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsInvalidPayloadJsonBeforeDurableRowsAreWritten() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
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
                        "not-json",
                        "enqueue_automation_command",
                        "LOOK",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("payload_json must be valid JSON", ex.getMessage());
    verify(coordinatorRepository, never()).findByTenantIdAndCommandId(anyLong(), anyString());
    verify(followupRepository, never())
        .findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            anyLong(), anyString(), anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsMissingTargetRegionEpoch() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
                        1L,
                        "cmd-1",
                        "coord-1",
                        7L,
                        "region-a",
                        4L,
                        8L,
                        "region-b",
                        0L,
                        22L,
                        4L,
                        25L,
                        "late_result_safe_to_ignore",
                        "followup-1",
                        "effect-1",
                        "entity-9",
                        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\",\"requiresSoloTick\":true}",
                        "enqueue_automation_command",
                        "LOOK",
                        true,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        "REMOTE_FOLLOWUP",
                        "TARGET_REGION_EXECUTED",
                        44L,
                        22L,
                        1700L)));

    assertEquals("target_region_epoch must be positive", ex.getMessage());
    verify(coordinatorRepository, never()).findByTenantIdAndCommandId(anyLong(), anyString());
    verify(followupRepository, never())
        .findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            anyLong(), anyString(), anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsTriggerScriptEventWithoutSnapshotToken() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
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
                        "{\"kind\":\"trigger_script_event\",\"eventType\":\"onEnterRegion\",\"scriptEventId\":\"remote-enter-1\",\"eventPayload\":{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}}",
                        "trigger_script_event",
                        null,
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("trigger_script_event read_snapshot_token is required", ex.getMessage());
    verify(coordinatorRepository, never()).findByTenantIdAndCommandId(anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsUnsupportedPayloadKindBeforeDurableRowsAreWritten() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
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
                        "{\"kind\":\"teleport\",\"command\":\"LOOK\"}",
                        "teleport",
                        "LOOK",
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals("payload kind 'teleport' is not yet supported", ex.getMessage());
    verify(coordinatorRepository, never()).findByTenantIdAndCommandId(anyLong(), anyString());
    verify(followupRepository, never())
        .findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            anyLong(), anyString(), anyLong(), anyString());
  }

  @Test
  void scheduleFollowupRejectsMissingCommandForLiveEnqueuePayloadKind() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.scheduleFollowup(
                    new RemoteFollowupRuntimeService.ScheduleRequest(
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
                        "{\"kind\":\"enqueue_automation_command\"}",
                        "enqueue_automation_command",
                        null,
                        false,
                        "SHARED",
                        "demo",
                        "production",
                        17L,
                        "patch-1",
                        "plugin-1",
                        "plugin-v1",
                        "dispatch-1",
                        "work-1",
                        "script-1",
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertEquals(
        "payload command is required for kind 'enqueue_automation_command'", ex.getMessage());
    verify(coordinatorRepository, never()).findByTenantIdAndCommandId(anyLong(), anyString());
    verify(followupRepository, never())
        .findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            anyLong(), anyString(), anyLong(), anyString());
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
    verify(resultRepository)
        .save(
            argThat(
                result ->
                    Long.valueOf(7L).equals(result.getOriginGameInstanceId())
                        && Long.valueOf(8L).equals(result.getTargetGameInstanceId())
                        && "SHARED".equals(result.getPlayableStateScope())
                        && "cmd-1".equals(result.getCommandId())
                        && "auto-1".equals(result.getResultCommandId())
                        && "RATE_LIMIT".equals(result.getResultErrorCode())
                        && "Target region rejected the command".equals(result.getResultMessage())
                        && "dispatch-1".equals(result.getAutomationDispatchId())
                        && "work-1".equals(result.getAutomationWorkItemId())
                        && "script-1".equals(result.getScriptId())
                        && "patch-1".equals(result.getScriptPatchVersion())
                        && "plugin-1".equals(result.getPluginId())
                        && "plugin-v1".equals(result.getPluginVersionId())
                        && "demo".equals(result.getWorldSlug())
                        && "production".equals(result.getRealmSlug())
                        && Long.valueOf(17L).equals(result.getPointerVersion())));
    verify(coordinatorRepository, never()).save(any());
  }

  @Test
  void recordLateResultLeavesTimedOutCoordinatorForLaterOriginReconciliation() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED);
    coordinator.setLateResultPolicy("late_result_safe_to_ignore");
    coordinator.setPlayableStateScope("SHARED");
    coordinator.setWorldSlug("demo");
    coordinator.setRealmSlug("production");
    coordinator.setPointerVersion(17L);
    RemoteFollowup followup = followup();
    followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
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
  void recordResultDropsPartialStoredRoutingBundleFromResultProjection() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setPlayableStateScope("SHARED");
    coordinator.setWorldSlug("demo");
    coordinator.setRealmSlug("production");
    coordinator.setPointerVersion(null);
    RemoteFollowup followup = followup();
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(null);
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1")).thenReturn(Optional.empty());

    service.recordResult(resultRequest("APPLIED"));

    verify(resultRepository)
        .save(
            argThat(
                result ->
                    result.getPlayableStateScope() == null
                        && result.getWorldSlug() == null
                        && result.getRealmSlug() == null
                        && result.getPointerVersion() == null));
  }

  @Test
  void recordResultMirrorsConcreteRemoteFailureOntoFollowup() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowup followup = followup();
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ResultOutcome outcome =
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
                "{\"errorCode\":\"RATE_LIMIT\",\"message\":\"Target region rejected the command\"}",
                null,
                "RATE_LIMIT",
                "Target region rejected the command"));

    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, outcome.coordinatorState());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED, outcome.followupStatus());
    assertEquals("RATE_LIMIT", followup.getFailureCode());
    assertEquals("Target region rejected the command", followup.getFailureMessage());
  }

  @Test
  void recordResultAcceptsExplicitAuthorityWithoutPayloadJson() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowup followup = followup();
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1")).thenReturn(Optional.empty());

    RemoteFollowupRuntimeService.ResultOutcome outcome =
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
                null,
                "auto-1",
                "RATE_LIMIT",
                "Target region rejected the command"));

    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, outcome.coordinatorState());
    verify(resultRepository)
        .save(
            argThat(
                result ->
                    result.getResultPayloadJson() == null
                        && "auto-1".equals(result.getResultCommandId())
                        && "RATE_LIMIT".equals(result.getResultErrorCode())
                        && "Target region rejected the command".equals(result.getResultMessage())));
  }

  @Test
  void recordResultRejectsConflictingExplicitErrorCodeAndPayloadJson() {
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
                        "region-b",
                        8L,
                        "ABANDONED",
                        "{\"errorCode\":\"RATE_LIMIT\",\"message\":\"Target region rejected the command\"}",
                        null,
                        "OTHER_CODE",
                        "Target region rejected the command")));

    assertEquals("result_payload_json errorCode does not match result_error_code", ex.getMessage());
    verify(resultRepository, never()).save(any());
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
                        "{\"status\":\"done\"}",
                        null,
                        null,
                        null)));

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
                        "{\"status\":\"failed\"}",
                        null,
                        null,
                        null)));

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
    existing.setResultPayloadJson(
        "{\"status\":\"done\",\"commandId\":\"auto-1\",\"errorCode\":\"RATE_LIMIT\",\"message\":\"Target region rejected the command\"}");
    existing.setResultCommandId("auto-1");
    existing.setResultErrorCode("RATE_LIMIT");
    existing.setResultMessage("Target region rejected the command");
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
  void recordResultReusesExistingResultIdWhenReplayOmitsPayloadJson() {
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
    existing.setResultPayloadJson(
        "{\"status\":\"done\",\"commandId\":\"auto-1\",\"errorCode\":\"RATE_LIMIT\",\"message\":\"Target region rejected the command\"}");
    existing.setResultCommandId("auto-1");
    existing.setResultErrorCode("RATE_LIMIT");
    existing.setResultMessage("Target region rejected the command");
    existing.setObservedAt(Instant.parse("2026-05-01T00:00:05Z"));
    when(coordinatorRepository.findByTenantIdAndCoordinatorId(1L, "coord-1"))
        .thenReturn(Optional.of(coordinator));
    when(followupRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(followup));
    when(resultRepository.findByTenantIdAndResultId(1L, "result-1"))
        .thenReturn(Optional.of(existing));

    RemoteFollowupRuntimeService.ResultOutcome outcome =
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
                "APPLIED",
                null,
                "auto-1",
                "RATE_LIMIT",
                "Target region rejected the command"));

    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, outcome.coordinatorState());
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
    GameplayCommand targetCommand = gameplayCommand();
    targetCommand.setCommandId("rfcmd-followup-1");
    targetCommand.setExecutionOutcome("APPLIED");
    targetCommand.setGameplayResult("APPLIED");
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(targetCommand));

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
  void reconcileResultsKeepsPendingCoordinatorUntilTargetCommandTerminates() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.COMMAND_PENDING_REMOTE);
    coordinator.setGameplayResult("PENDING");
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    GameplayCommand originCommand = gameplayCommand();
    GameplayCommand targetCommand = gameplayCommand();
    targetCommand.setCommandId("rfcmd-followup-1");
    targetCommand.setExecutionOutcome("STAGED");
    targetCommand.setGameplayResult("PENDING");
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(originCommand));
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(targetCommand));

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(0, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, coordinator.getState());
    assertEquals("STAGED", originCommand.getExecutionOutcome());
    assertEquals("PENDING", originCommand.getGameplayResult());
  }

  @Test
  void reconcileResultsKeepsPendingCoordinatorWhenAppliedInboxRowHasNoTargetCommand() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.COMMAND_PENDING_REMOTE);
    coordinator.setGameplayResult("PENDING");
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    GameplayCommand originCommand = gameplayCommand();
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(originCommand));
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.empty());

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(0, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE, coordinator.getState());
    assertEquals("STAGED", originCommand.getExecutionOutcome());
    assertEquals("PENDING", originCommand.getGameplayResult());
  }

  @Test
  void reconcileResultsMirrorsTargetCommandFailureCodeWhenRemoteCommandFails() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    coordinator.setExecutionOutcome(RemoteFollowupRuntimeServiceImpl.COMMAND_PENDING_REMOTE);
    coordinator.setGameplayResult("PENDING");
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("APPLIED");
    GameplayCommand originCommand = gameplayCommand();
    GameplayCommand targetCommand = gameplayCommand();
    targetCommand.setCommandId("rfcmd-followup-1");
    targetCommand.setExecutionOutcome("COMPLETED");
    targetCommand.setGameplayResult("NOT_APPLIED");
    targetCommand.setFailureCode("RATE_LIMIT");
    targetCommand.setFailureMessage("Target region rejected the remote gameplay command");
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(originCommand));
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(targetCommand));

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(1, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_ABANDONED, coordinator.getState());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED, originCommand.getExecutionOutcome());
    assertEquals("NOT_APPLIED", originCommand.getGameplayResult());
    assertEquals("RATE_LIMIT", originCommand.getFailureCode());
    assertEquals(
        "Target region rejected the remote gameplay command", originCommand.getFailureMessage());
  }

  @Test
  void reconcileResultsMirrorsDurableResultFailureWhenTargetCommandMissing() {
    RemoteCommandCoordinator coordinator = coordinator();
    coordinator.setState(RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE);
    RemoteFollowupResult result = new RemoteFollowupResult();
    result.setOutcome("ABANDONED");
    result.setResultErrorCode("RATE_LIMIT");
    result.setResultPayloadJson(
        "{\"errorCode\":\"RATE_LIMIT\",\"message\":\"Target region rejected the remote gameplay command\"}");
    GameplayCommand originCommand = gameplayCommand();
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of(coordinator));
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of());
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(originCommand));
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.empty());

    int reconciled = service.reconcileResults(1L, "region-a", 4L);

    assertEquals(1, reconciled);
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_ABANDONED, coordinator.getState());
    assertEquals(
        RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED, originCommand.getExecutionOutcome());
    assertEquals("NOT_APPLIED", originCommand.getGameplayResult());
    assertEquals("RATE_LIMIT", originCommand.getFailureCode());
    assertEquals(
        "Target region rejected the remote gameplay command", originCommand.getFailureMessage());
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
    GameplayCommand targetCommand = gameplayCommand();
    targetCommand.setCommandId("rfcmd-followup-1");
    targetCommand.setExecutionOutcome("APPLIED");
    targetCommand.setGameplayResult("APPLIED");
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
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(targetCommand));

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
    GameplayCommand targetCommand = gameplayCommand();
    targetCommand.setCommandId("rfcmd-followup-1");
    targetCommand.setExecutionOutcome("APPLIED");
    targetCommand.setGameplayResult("APPLIED");
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_PENDING_REMOTE))
        .thenReturn(List.of());
    when(coordinatorRepository.findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
            1L, "region-a", RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_TIMEOUT_ABANDONED))
        .thenReturn(List.of(coordinator));
    when(resultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(1L, "coord-1"))
        .thenReturn(Optional.of(result));
    when(gameplayCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));
    when(gameplayCommandRepository.findFirstByTenantIdAndRemoteFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(targetCommand));

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
        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\",\"requiresSoloTick\":true}",
        "enqueue_automation_command",
        "LOOK",
        true,
        "SHARED",
        "demo",
        "production",
        17L,
        "patch-1",
        "plugin-1",
        "plugin-v1",
        "dispatch-1",
        "work-1",
        "script-1",
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_EXECUTED",
        44L,
        22L,
        1700L);
  }

  private static RemoteFollowupRuntimeService.ScheduleRequest triggerScriptEventScheduleRequest() {
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
        "321",
        "{\"kind\":\"trigger_script_event\",\"eventType\":\"onEnterRegion\",\"eventSchemaVersion\":\"v1\",\"scriptEventId\":\"remote-enter-1\",\"readSnapshotToken\":\"game-session:onEnterRegion:9:8:remote-enter-1\",\"eventPayload\":{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}}",
        "trigger_script_event",
        null,
        false,
        "SHARED",
        "demo",
        "production",
        17L,
        "patch-1",
        "plugin-1",
        "plugin-v1",
        "dispatch-1",
        "work-1",
        "script-1",
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_EXECUTED",
        44L,
        22L,
        1700L);
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
        "{\"status\":\"done\",\"commandId\":\"auto-1\",\"errorCode\":\"RATE_LIMIT\",\"message\":\"Target region rejected the command\"}",
        "auto-1",
        "RATE_LIMIT",
        "Target region rejected the command");
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
    coordinator.setPlayableStateScope("SHARED");
    coordinator.setWorldSlug("demo");
    coordinator.setRealmSlug("production");
    coordinator.setPointerVersion(17L);
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
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
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setOriginSourceState("TARGET_REGION_EXECUTED");
    followup.setOriginSourceOrdinal(44L);
    followup.setOriginSourceDueTickId(22L);
    followup.setOriginSourceDueAtMs(1700L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setCommandId("cmd-1");
    followup.setAutomationDispatchId("dispatch-1");
    followup.setAutomationWorkItemId("work-1");
    followup.setScriptId("script-1");
    followup.setScriptPatchVersion("patch-1");
    followup.setPluginId("plugin-1");
    followup.setPluginVersionId("plugin-v1");
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
    command.setPlayableStateScope("SHARED");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(17L);
    command.setAutomationDispatchId("dispatch-1");
    command.setAutomationWorkItemId("work-1");
    command.setScriptId("script-1");
    command.setScriptPatchVersion("patch-1");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    return command;
  }
}
