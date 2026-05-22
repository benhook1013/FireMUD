package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptGameplayCommandHandoffServiceImplTest {
  private static AutomationAdmissionStateService admissionStateService() {
    AutomationAdmissionStateService service = Mockito.mock(AutomationAdmissionStateService.class);
    when(service.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "NORMAL", 1L, "", "", "", 100L));
    return service;
  }

  @Test
  void acceptedGameSessionOutcomeMarksWorkItemHandedOff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("auto-1")
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(),
            emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome()).isEqualTo("ENQUEUED");
    ArgumentCaptor<EnqueueAutomationCommandIfAbsentRequest> requestCaptor =
        ArgumentCaptor.forClass(EnqueueAutomationCommandIfAbsentRequest.class);
    verify(gameSessionClient).enqueueAutomationCommandIfAbsent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getAutomationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(requestCaptor.getValue().getAutomationWorkItemId()).isEqualTo("99");
    assertThat(requestCaptor.getValue().getCommand()).isEqualTo("say hello");
    assertThat(requestCaptor.getValue().getTargetEntityId()).isEqualTo("target-entity-1");
    assertThat(requestCaptor.getValue().getDueTickId()).isEqualTo(34L);
    assertThat(requestCaptor.getValue().getPluginId()).isEqualTo("plugin-1");
    assertThat(requestCaptor.getValue().getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(requestCaptor.getValue().getPlayableStateScope().name())
        .isEqualTo("PLAYABLE_STATE_SCOPE_SHARED");
    assertThat(requestCaptor.getValue().getWorldSlug()).isEqualTo("demo");
    assertThat(requestCaptor.getValue().getRealmSlug()).isEqualTo("production");
    assertThat(requestCaptor.getValue().getPointerVersion()).isEqualTo("17");
    assertThat(requestCaptor.getValue().getOriginSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(requestCaptor.getValue().getOriginSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(requestCaptor.getValue().getOriginSourceOrdinal()).isEqualTo(5000L);
    assertThat(requestCaptor.getValue().getOriginSourceDueAtMs()).isEqualTo(5000L);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("enqueued");
    assertThat(audit.getFinalReason()).isEqualTo("game_session_accepted");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getAutomationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(handoffCaptor.getValue().getGameSessionCommandId()).isEqualTo("auto-1");
    assertThat(handoffCaptor.getValue().getTargetGameInstanceId()).isEqualTo("7");
    assertThat(handoffCaptor.getValue().getTargetRegionId()).isEqualTo("region-1");
    assertThat(handoffCaptor.getValue().getTargetRegionEpoch()).isEqualTo(12L);
    assertThat(handoffCaptor.getValue().getRemoteCoordinatorId()).isBlank();
    assertThat(handoffCaptor.getValue().getRemoteFollowupId()).isBlank();
    assertThat(handoffCaptor.getValue().getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(handoffCaptor.getValue().getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(handoffCaptor.getValue().getSourceOrdinal()).isEqualTo(5000L);
    assertThat(handoffCaptor.getValue().getEmittedCommandText()).isEqualTo("say hello");
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("enqueued");
  }

  @Test
  void rejectedGameSessionOutcomeDeadLettersWorkItem() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .setError(ErrorDetail.newBuilder().setCode("STALE_TIMELINE").build())
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("STALE_TIMELINE");
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItemCaptor.getAllValues().get(1).getCancelReason()).isEqualTo("STALE_TIMELINE");
    assertThat(audit.getFinalStage()).isEqualTo("HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_failed");
    assertThat(audit.getFinalReason()).isEqualTo("STALE_TIMELINE");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(handoffCaptor.getValue().getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(handoffCaptor.getValue().getEmittedCommandText()).isEqualTo("say hello");
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("rejected");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("STALE_TIMELINE");
  }

  @Test
  void advancedAdmissionEpochCancelsBeforeGameSessionHandoff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionStateService.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1",
                "7",
                "region-1",
                "PAUSED_FOR_ROLLBACK",
                2L,
                "req-2",
                "admin",
                "rollback",
                200L));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("rollback_epoch_advanced");
    verify(gameSessionClient, Mockito.never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("CANCELED");
    assertThat(workItemCaptor.getValue().getCancelReason()).isEqualTo("rollback_epoch_advanced");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("rollback_epoch_advanced");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(handoffCaptor.getValue().getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(handoffCaptor.getValue().getEmittedCommandText()).isEqualTo("say hello");
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("rollback_epoch_advanced");
  }

  @Test
  void advancedRuntimeRegionScopeCancelsBeforeGameSessionHandoff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-2")
                        .setRegionEpoch(12L))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("runtime_region_scope_advanced");
    verify(gameSessionClient, Mockito.never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("CANCELED");
    assertThat(workItemCaptor.getValue().getCancelReason())
        .isEqualTo("runtime_region_scope_advanced");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_region_scope_advanced");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getHandoffOutcome())
        .isEqualTo("runtime_region_scope_advanced");
    assertThat(handoffCaptor.getValue().getHandoffReason())
        .isEqualTo("runtime_region_scope_advanced");
  }

  @Test
  void remoteTargetSchedulesDurableFollowupAndMarksWorkItemHandedOff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    when(gameSessionClient.scheduleRemoteFollowup(Mockito.any()))
        .thenReturn(
            ScheduleRemoteFollowupResponse.newBuilder()
                .setCoordinatorId("remote-coordinator:workItem:99#0")
                .setFollowupId("remote-followup:workItem:99#0")
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome()).isEqualTo("REMOTE_SCHEDULED");
    assertThat(result.remoteCoordinatorId()).isEqualTo("remote-coordinator:workItem:99#0");
    assertThat(result.remoteFollowupId()).isEqualTo("remote-followup:workItem:99#0");
    verify(gameSessionClient, Mockito.never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScheduleRemoteFollowupRequest> requestCaptor =
        ArgumentCaptor.forClass(ScheduleRemoteFollowupRequest.class);
    verify(gameSessionClient).scheduleRemoteFollowup(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getCommandId()).isEqualTo("workItem:99#0");
    assertThat(requestCaptor.getValue().getOriginGameInstanceId()).isEqualTo("7");
    assertThat(requestCaptor.getValue().getOriginRegionId()).isEqualTo("region-1");
    assertThat(requestCaptor.getValue().getTargetGameInstanceId()).isEqualTo("8");
    assertThat(requestCaptor.getValue().getTargetRegionId()).isEqualTo("region-2");
    assertThat(requestCaptor.getValue().getTargetRegionEpoch()).isEqualTo(77L);
    assertThat(requestCaptor.getValue().getPayloadKind()).isEqualTo("enqueue_automation_command");
    assertThat(requestCaptor.getValue().getRequestedCommand()).isEqualTo("say hello");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getRemoteCoordinatorId())
        .isEqualTo("remote-coordinator:workItem:99#0");
    assertThat(handoffCaptor.getValue().getRemoteFollowupId())
        .isEqualTo("remote-followup:workItem:99#0");
    assertThat(handoffCaptor.getValue().getTargetGameInstanceId()).isEqualTo("8");
    assertThat(handoffCaptor.getValue().getTargetRegionId()).isEqualTo("region-2");
    assertThat(handoffCaptor.getValue().getTargetRegionEpoch()).isEqualTo(77L);
  }

  @Test
  void collapsesPartialRoutingBundleBeforeForwardingOrPersistingHandoff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("auto-1")
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptWorkItem workItem = workItem();
    workItem.setRealmSlug("");

    service.handoff(
        workItem, emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    ArgumentCaptor<EnqueueAutomationCommandIfAbsentRequest> requestCaptor =
        ArgumentCaptor.forClass(EnqueueAutomationCommandIfAbsentRequest.class);
    verify(gameSessionClient).enqueueAutomationCommandIfAbsent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getWorldSlug()).isBlank();
    assertThat(requestCaptor.getValue().getRealmSlug()).isBlank();
    assertThat(requestCaptor.getValue().getPointerVersion()).isBlank();
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getWorldSlug()).isBlank();
    assertThat(handoffCaptor.getValue().getRealmSlug()).isBlank();
    assertThat(handoffCaptor.getValue().getPointerVersion()).isBlank();
  }

  private static ScriptGameplayCommandHandoffService.EmittedCommand emittedCommand(
      String commandText,
      String targetEntityId,
      String targetGameInstanceId,
      String targetRegionId,
      Long targetRegionEpoch,
      long dueTickId,
      int ordinal) {
    return new ScriptGameplayCommandHandoffService.EmittedCommand(
        commandText,
        targetEntityId,
        targetGameInstanceId,
        targetRegionId,
        targetRegionEpoch,
        false,
        dueTickId,
        ordinal);
  }

  private static ScriptWorkItem workItem() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(99L);
    item.setTenantId("1");
    item.setGameInstanceId("7");
    item.setRegionId("region-1");
    item.setRegionEpoch(12L);
    item.setEntityId("entity-1");
    item.setScriptId("script-1");
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-v1");
    item.setPlayableStateScope("SHARED");
    item.setWorldSlug("demo");
    item.setRealmSlug("production");
    item.setPointerVersion("17");
    item.setSourceKind("SCHEDULE_TIMER");
    item.setSourceState("SCHEDULE_DUE_CLAIMED");
    item.setSourceOrdinal(5000L);
    item.setSourceDueAtMs(5000L);
    item.setScriptPatchVersion("patch-1");
    item.setAdmissionEpoch(1L);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
