package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultDurableRemoteFollowupExecutionServiceTest {
  private RemoteFollowupRepository remoteFollowupRepository;
  private RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private DurableRemoteFollowupExecutionService service;

  @BeforeEach
  void setup() {
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    remoteCommandCoordinatorRepository = mock(RemoteCommandCoordinatorRepository.class);
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
}
