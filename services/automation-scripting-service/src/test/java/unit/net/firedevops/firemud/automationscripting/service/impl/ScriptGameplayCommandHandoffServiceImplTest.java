package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptGameplayCommandHandoffServiceImplTest {
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
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(),
            new ScriptGameplayCommandHandoffService.EmittedCommand("say hello", false, 34L, 0));

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome()).isEqualTo("ENQUEUED");
    ArgumentCaptor<EnqueueAutomationCommandIfAbsentRequest> requestCaptor =
        ArgumentCaptor.forClass(EnqueueAutomationCommandIfAbsentRequest.class);
    verify(gameSessionClient).enqueueAutomationCommandIfAbsent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getAutomationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(requestCaptor.getValue().getAutomationWorkItemId()).isEqualTo("99");
    assertThat(requestCaptor.getValue().getCommand()).isEqualTo("say hello");
    assertThat(requestCaptor.getValue().getDueTickId()).isEqualTo(34L);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("enqueued");
    assertThat(audit.getFinalReason()).isEqualTo("game_session_accepted");
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
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(),
            new ScriptGameplayCommandHandoffService.EmittedCommand("say hello", false, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("STALE_TIMELINE");
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItemCaptor.getAllValues().get(1).getCancelReason()).isEqualTo("STALE_TIMELINE");
    assertThat(audit.getFinalStage()).isEqualTo("HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_failed");
    assertThat(audit.getFinalReason()).isEqualTo("STALE_TIMELINE");
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
    item.setScriptPatchVersion("patch-1");
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
