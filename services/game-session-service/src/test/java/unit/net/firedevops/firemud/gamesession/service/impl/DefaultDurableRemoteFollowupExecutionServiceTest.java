package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultDurableRemoteFollowupExecutionServiceTest {
  private RemoteFollowupRepository remoteFollowupRepository;
  private RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private GameInstanceRepository gameInstanceRepository;
  private GameplayCommandRepository gameplayCommandRepository;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private TickService tickService;
  private RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private DurableRemoteFollowupExecutionService service;

  @BeforeEach
  void setup() {
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    remoteCommandCoordinatorRepository = mock(RemoteCommandCoordinatorRepository.class);
    gameInstanceRepository = mock(GameInstanceRepository.class);
    gameplayCommandRepository = mock(GameplayCommandRepository.class);
    runtimeRegionStatusRepository = mock(RuntimeRegionStatusRepository.class);
    tickService = mock(TickService.class);
    remoteFollowupRuntimeService = mock(RemoteFollowupRuntimeService.class);
    when(remoteFollowupRuntimeService.recordResult(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new RemoteFollowupRuntimeService.ResultOutcome(
                RemoteFollowupRuntimeServiceImpl.COORDINATOR_REMOTE_ABANDONED,
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED,
                false,
                false));
    service =
        new DefaultDurableRemoteFollowupExecutionService(
            remoteFollowupRepository,
            remoteCommandCoordinatorRepository,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService,
            remoteFollowupRuntimeService);
  }

  @Test
  void executeFailsClosedForUnsupportedPayloadKind() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson("{\"kind\":\"teleport\"}");
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_FOLLOWUP_KIND_UNSUPPORTED", result.failureCode());
    ArgumentCaptor<RemoteFollowupRuntimeService.ResultRequest> requestCaptor =
        ArgumentCaptor.forClass(RemoteFollowupRuntimeService.ResultRequest.class);
    org.mockito.Mockito.verify(remoteFollowupRuntimeService).recordResult(requestCaptor.capture());
    assertEquals("remote-result:followup-1", requestCaptor.getValue().resultId());
    assertEquals("coord-1", requestCaptor.getValue().coordinatorId());
    assertEquals("followup-1", requestCaptor.getValue().followupId());
    assertEquals("ABANDONED", requestCaptor.getValue().outcome());
  }

  @Test
  void executeReplaysTerminalAppliedFollowupWithoutDuplicateResultWrite() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    org.mockito.Mockito.verifyNoInteractions(remoteCommandCoordinatorRepository);
    org.mockito.Mockito.verify(remoteFollowupRuntimeService, org.mockito.Mockito.never())
        .recordResult(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void executeAbandonsClaimedFollowupWhenCoordinatorIsMissing() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson("{\"kind\":\"teleport\"}");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.empty());

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("REJECTED", result.effectStatus());
    assertEquals("REMOTE_COORDINATOR_NOT_FOUND", result.failureCode());
    org.mockito.Mockito.verify(remoteFollowupRuntimeService)
        .abandonFollowup(
            1L,
            "followup-1",
            "REMOTE_COORDINATOR_NOT_FOUND",
            "Durable remote followup execution could not load the linked coordinator");
  }

  @Test
  void executeEnqueuesAutomationCommandForSupportedPayloadKind() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_automation_command","automationDispatchId":"dispatch-1","automationWorkItemId":"work-1","scriptId":"script-1","scriptPatchVersion":"patch-1","command":"LOOK"}
        """);
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus runtimeStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(9L);
    runtimeStatus.setRegionEpoch(8L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(runtimeStatus));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-b", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    org.mockito.Mockito.verify(tickService)
        .enqueueCommand(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(9L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.Mockito.eq("LOOK"),
            org.mockito.Mockito.eq(false));
    ArgumentCaptor<RemoteFollowupRuntimeService.ResultRequest> requestCaptor =
        ArgumentCaptor.forClass(RemoteFollowupRuntimeService.ResultRequest.class);
    org.mockito.Mockito.verify(remoteFollowupRuntimeService).recordResult(requestCaptor.capture());
    assertEquals("remote-result:followup-1", requestCaptor.getValue().resultId());
    assertEquals("APPLIED", requestCaptor.getValue().outcome());
  }
}
