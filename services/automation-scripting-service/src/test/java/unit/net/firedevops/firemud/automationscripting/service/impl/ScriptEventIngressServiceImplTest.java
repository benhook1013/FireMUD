package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptEventIngressServiceImplTest {
  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void admitsKnownProducerAndPersistsAuditRow() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository eventAuditRepository =
        Mockito.mock(ScriptEventAuditRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(workItemRepository.save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-1", "ENTITY", "entity-1")));
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .build())
                .build());
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            bindingRepository,
            workItemRepository,
            eventAuditRepository,
            new BuiltInScriptEventRegistryService(),
            automationQueueService,
            outputProperties(),
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setScriptId("script-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name());
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    ArgumentCaptor<ScriptEventIngressAudit> auditCaptor =
        ArgumentCaptor.forClass(ScriptEventIngressAudit.class);
    verify(repository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getSourceService()).isEqualTo("game-session-service");
    assertThat(auditCaptor.getValue().isAdmitted()).isTrue();
    assertThat(auditCaptor.getValue().getResolvedHandlerCount()).isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo("script-1");
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("PENDING_EVALUATION");
    ArgumentCaptor<ScriptEventAudit> eventAuditCaptor =
        ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).save(eventAuditCaptor.capture());
    assertThat(eventAuditCaptor.getValue().getFinalStage()).isEqualTo("ADMISSION");
    assertThat(eventAuditCaptor.getValue().getFinalOutcome()).isEqualTo("work_item_persisted");
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void rejectsRuntimeTriggerWithoutSnapshotToken() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setScriptId("script-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("missing_snapshot_token");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsUnauthorizedProducerThroughRegistryOutcome() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "account-service", "account-service-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setScriptId("script-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("unauthorized_producer");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsOversizedPayloadBeforeWorkItemPersistence() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptOutputProperties outputProperties = outputProperties();
    outputProperties.setMaxSerializedWorkItemBytes(4);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties,
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setPayloadJson("{\"too\":\"large\"}")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_OUTPUT_BUDGET_EXCEEDED.name());
    assertThat(admission.reason()).isEqualTo("work_item_size_exceeded");
    verify(workItemRepository, never()).save(Mockito.any());
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void deduplicatesExistingTriggerIdentity() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAudit existing = new ScriptEventIngressAudit();
    existing.setAdmitted(true);
    existing.setAdmissionOutcome(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name());
    existing.setAdmissionReason("admitted_handlers_resolved");
    existing.setResolvedHandlerCount(2);
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.of(existing));
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setScriptId("script-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(2);
    verify(repository, never()).save(Mockito.any());
  }

  @Test
  void rejectsMissingRequiredIdentityBeforeAuditWrite() {
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.admit(
                TriggerScriptEventRequest.newBuilder()
                    .setEventType("onCommand")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("event-1")
                    .build()));
    verify(repository, never()).save(Mockito.any());
  }

  @Test
  void rejectsWhenPinnedPatchDoesNotMatchRequestedPatch() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-other")
                        .build())
                .build());
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("version_unavailable");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsWhenPinStateCannotBeRead() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("GAME_SESSION_UNAVAILABLE")
                        .build())
                .build());
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_INFRASTRUCTURE_ERROR.name());
    assertThat(admission.reason()).isEqualTo("pin_state_unavailable");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  private static ScriptEventBinding binding(String scriptId, String scopeType, String scopeId) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setScriptId(scriptId);
    binding.setTargetScopeType(scopeType);
    binding.setTargetScopeId(scopeId);
    binding.setEnabled(true);
    return binding;
  }

  private static ScriptOutputProperties outputProperties() {
    return new ScriptOutputProperties();
  }
}
