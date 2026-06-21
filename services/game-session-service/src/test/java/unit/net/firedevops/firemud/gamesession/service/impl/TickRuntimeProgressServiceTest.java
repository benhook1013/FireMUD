package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressRequest;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressResponse;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;

class TickRuntimeProgressServiceTest {
  private SimpleMeterRegistry meterRegistry;
  private RemoteFollowupRepository remoteFollowupRepository;
  private RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private AutomationScriptingClient automationScriptingClient;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private TickRuntimeProgressService service;

  @BeforeEach
  void setup() {
    meterRegistry = new SimpleMeterRegistry();
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    remoteFollowupRuntimeService = mock(RemoteFollowupRuntimeService.class);
    automationScriptingClient = mock(AutomationScriptingClient.class);
    when(automationScriptingClient.observeRuntimeTickProgress(any()))
        .thenReturn(ObserveRuntimeTickProgressResponse.newBuilder().build());
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    TickQueueControlService tickQueueControlService =
        new TickQueueControlService(
            mock(RedisTemplate.class),
            mock(GameInstanceRepository.class),
            mock(GameplayCommandRepository.class),
            runtimeRegionStatusRepository,
            new net.firedevops.firemud.common.runtime.RuntimeIdentity(
                "game-session-service",
                "test-instance",
                "test-host",
                Instant.parse("2026-04-19T00:00:00Z"),
                null,
                null,
                null),
            mock(net.firedevops.firemud.gamesession.service.SessionAuthenticationService.class));
    service =
        new TickRuntimeProgressService(
            meterRegistry,
            remoteFollowupRepository,
            remoteFollowupRuntimeService,
            automationScriptingClient,
            tickQueueControlService);
    service.init();
    setField(service, "tickDurationMs", 1000L);
    setField(service, "maxRemoteFollowupsPerTick", 16);
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void observeRemoteFollowupBacklogPublishesDueDepthAndDrainLag() {
    TickQueueControlService.OwnershipSnapshot ownership =
        new TickQueueControlService.OwnershipSnapshot("region-a", 4L, "fence-a", false, 7L);
    when(remoteFollowupRepository
            .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                1L, "region-a", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 8L))
        .thenReturn(2L);
    net.firedevops.firemud.gamesession.entity.RemoteFollowup oldestDue =
        new net.firedevops.firemud.gamesession.entity.RemoteFollowup();
    oldestDue.setDueTickId(6L);
    when(remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                1L, "region-a", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 8L))
        .thenReturn(Optional.of(oldestDue));

    service.observeRemoteFollowupBacklog(1L, ownership);

    org.junit.jupiter.api.Assertions.assertEquals(
        2.0, meterRegistry.get("remote_followups_due_total").gauge().value());
    org.junit.jupiter.api.Assertions.assertEquals(
        2000.0, meterRegistry.get("remote_followups_drain_lag_ms").gauge().value());
  }

  @Test
  void observeRemoteFollowupBacklogRecordsOverBudgetCounter() {
    TickQueueControlService.OwnershipSnapshot ownership =
        new TickQueueControlService.OwnershipSnapshot("region-a", 4L, "fence-a", false, 7L);
    when(remoteFollowupRepository
            .countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
                1L, "region-a", RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, 8L))
        .thenReturn(17L);
    when(remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                anyLong(), any(), any(), anyLong()))
        .thenReturn(Optional.empty());

    service.observeRemoteFollowupBacklog(1L, ownership);

    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, meterRegistry.get("remote_followups_backlog_over_budget_total").counter().count());
  }

  @Test
  void advancePublishAndReconcileRuntimeTickProgressUsesCanonicalOwnership() {
    RuntimeRegionStatus currentStatus = runtimeOwnership(1L, 2L, "region-a", 4L, "fence-a", false);
    currentStatus.setLastCommittedTickId(7L);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-a"))
        .thenReturn(Optional.of(currentStatus));

    RuntimeRegionStatus saved =
        service.advanceRuntimeTickProgress(
            1L,
            2L,
            new TickQueueControlService.OwnershipSnapshot("region-a", 4L, "fence-a", false, 7L));
    service.reconcileRemoteFollowupTimeouts(saved);
    service.publishRuntimeTickProgress(saved);

    org.junit.jupiter.api.Assertions.assertEquals(8L, saved.getLastCommittedTickId());
    verify(remoteFollowupRuntimeService).reconcileResults(1L, "region-a", 4L);
    verify(remoteFollowupRuntimeService).reconcileTimeouts(1L, "region-a", 4L, 8L);
    ArgumentCaptor<ObserveRuntimeTickProgressRequest> requestCaptor =
        ArgumentCaptor.forClass(ObserveRuntimeTickProgressRequest.class);
    verify(automationScriptingClient).observeRuntimeTickProgress(requestCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "region-a", requestCaptor.getValue().getRegionId());
    org.junit.jupiter.api.Assertions.assertEquals(8L, requestCaptor.getValue().getTickId());
  }

  @Test
  void reconcilePausedRemoteFollowupResultsSkipsTimeoutPass() {
    service.reconcilePausedRemoteFollowupResults(
        1L, new TickQueueControlService.OwnershipSnapshot("region-a", 4L, "fence-a", true, 7L));

    verify(remoteFollowupRuntimeService).reconcileResults(1L, "region-a", 4L);
    verify(remoteFollowupRuntimeService, never())
        .reconcileTimeouts(anyLong(), any(), anyLong(), anyLong());
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
