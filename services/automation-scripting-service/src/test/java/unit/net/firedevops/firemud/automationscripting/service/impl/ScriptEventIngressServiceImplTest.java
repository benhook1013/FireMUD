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
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunQuotaService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptQuotaService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptEventIngressServiceImplTest {
  private static AutomationAdmissionStateService admissionStateService() {
    AutomationAdmissionStateService service = Mockito.mock(AutomationAdmissionStateService.class);
    when(service.getState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 1L, "", "", "", 100L));
    return service;
  }

  private static PluginRuntimeStateService enabledPluginRuntimeStateService() {
    PluginRuntimeStateService service = Mockito.mock(PluginRuntimeStateService.class);
    when(service.getStatus("1", "game-1", "plugin-1"))
        .thenReturn(
            Optional.of(
                new PluginRuntimeStateService.PluginRuntimeStatus(
                    "plugin-v1",
                    "",
                    "region-1",
                    7L,
                    PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    100L,
                    "req-1",
                    "admin",
                    System.currentTimeMillis(),
                    null,
                    null)));
    return service;
  }

  private static ScriptQuotaService allowingQuotaService() {
    ScriptQuotaService service = Mockito.mock(ScriptQuotaService.class);
    when(service.tryAcquire(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
    return service;
  }

  private static ScriptDryRunQuotaService allowingDryRunQuotaService() {
    ScriptDryRunQuotaService service = Mockito.mock(ScriptDryRunQuotaService.class);
    when(service.tryAcquire(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(true);
    return service;
  }

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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-1", "ENTITY", "entity-1", "high")));
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(7L)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            enabledPluginRuntimeStateService(),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
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
    assertThat(auditCaptor.getValue().getWorldSlug()).isEqualTo("demo");
    assertThat(auditCaptor.getValue().getRealmSlug()).isEqualTo("production");
    assertThat(auditCaptor.getValue().getPointerVersion()).isEqualTo("17");
    assertThat(auditCaptor.getValue().getSourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(auditCaptor.getValue().getSourceState()).isEqualTo("TRIGGER_ADMITTED");
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo("script-1");
    assertThat(workItemCaptor.getValue().getPluginId()).isEqualTo("plugin-1");
    assertThat(workItemCaptor.getValue().getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(workItemCaptor.getValue().getPlayableStateScope()).isEqualTo("SHARED");
    assertThat(workItemCaptor.getValue().getWorldSlug()).isEqualTo("demo");
    assertThat(workItemCaptor.getValue().getRealmSlug()).isEqualTo("production");
    assertThat(workItemCaptor.getValue().getPointerVersion()).isEqualTo("17");
    assertThat(workItemCaptor.getValue().getSourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(workItemCaptor.getValue().getSourceState()).isEqualTo("WORK_ITEM_PERSISTED");
    assertThat(workItemCaptor.getValue().getPriorityTag()).isEqualTo("high");
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("PENDING_EVALUATION");
    ArgumentCaptor<ScriptEventAudit> eventAuditCaptor =
        ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).save(eventAuditCaptor.capture());
    assertThat(eventAuditCaptor.getValue().getFinalStage()).isEqualTo("ADMISSION");
    assertThat(eventAuditCaptor.getValue().getFinalOutcome()).isEqualTo("work_item_persisted");
    assertThat(eventAuditCaptor.getValue().getPluginId()).isEqualTo("plugin-1");
    assertThat(eventAuditCaptor.getValue().getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(eventAuditCaptor.getValue().getSourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(eventAuditCaptor.getValue().getSourceState()).isEqualTo("WORK_ITEM_PERSISTED");
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void filtersHandlersByActivePluginOwnershipWhenScriptIdIsNotProvided() {
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
    ScriptDefinitionRepository scriptDefinitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);

    when(workItemRepository.save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(
            List.of(
                binding("script-owned", "ENTITY", "entity-1", "high"),
                binding("script-foreign", "ENTITY", "entity-1", "low")));
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(
            List.of(
                scriptDefinition("script-owned", "plugin-1", "plugin-v1"),
                scriptDefinition("script-foreign", "plugin-2", "plugin-v2")));
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(7L)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            scriptDefinitionRepository,
            enabledPluginRuntimeStateService(),
            allowingQuotaService(),
            allowingDryRunQuotaService(),
            new ScriptRuntimeProperties());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name());
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo("script-owned");
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void rejectsGameplayEventWhenRuntimeRegionScopeAdvanced() {
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
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.empty());
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-2")
                        .setRegionEpoch(7L)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            enabledPluginRuntimeStateService(),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("runtime_region_scope_advanced");
    ArgumentCaptor<ScriptEventIngressAudit> auditCaptor =
        ArgumentCaptor.forClass(ScriptEventIngressAudit.class);
    verify(repository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().isAdmitted()).isFalse();
    assertThat(auditCaptor.getValue().getAdmissionReason())
        .isEqualTo("runtime_region_scope_advanced");
    verify(workItemRepository, never()).save(Mockito.any());
    verify(eventAuditRepository, never()).save(Mockito.any());
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any());
  }

  @Test
  void rejectsBuiltInOnCommandPayloadMissingRequiredFields() {
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
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false))
        .thenReturn(Optional.empty());
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            bindingRepository,
            workItemRepository,
            eventAuditRepository,
            new BuiltInScriptEventRegistryService(),
            automationQueueService,
            outputProperties(),
            Mockito.mock(GameSessionControlPlaneClient.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            enabledPluginRuntimeStateService(),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .setPayloadJson("{\"commandId\":\"cmd-1\"}")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("invalid_built_in_payload");
    verify(workItemRepository, never()).save(Mockito.any());
    verify(eventAuditRepository, never()).save(Mockito.any());
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any());
  }

  @Test
  void admitsOnLoadAsPatchReadinessWorkForOneScript() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "automation-scripting-service", "automation-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository eventAuditRepository =
        Mockito.mock(ScriptEventAuditRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    when(workItemRepository.save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "",
                "",
                "",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false))
        .thenReturn(Optional.empty());
    when(workItemRepository
            .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "",
                "",
                "",
                "script-1",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false))
        .thenReturn(false);
    when(eventAuditRepository
            .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "",
                "",
                "",
                "script-1",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false))
        .thenReturn(false);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            bindingRepository,
            workItemRepository,
            eventAuditRepository,
            new BuiltInScriptEventRegistryService(),
            automationQueueService,
            outputProperties(),
            Mockito.mock(GameSessionControlPlaneClient.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            enabledPluginRuntimeStateService(),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setScriptId("script-1")
                .setEventType("onLoad")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("onload:1:patch-1:script-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo("script-1");
    assertThat(workItemCaptor.getValue().getGameInstanceId()).isEmpty();
    assertThat(workItemCaptor.getValue().getSourceKind()).isEqualTo("PATCH_READINESS_EVENT");
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void collapsesPartialRoutingBundleForNonGameplayIngressPersistence() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "automation-scripting-service", "automation-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository eventAuditRepository =
        Mockito.mock(ScriptEventAuditRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    when(workItemRepository.save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "",
                "",
                "",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false))
        .thenReturn(Optional.empty());
    when(workItemRepository
            .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "",
                "",
                "",
                "script-1",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false))
        .thenReturn(false);
    when(eventAuditRepository
            .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "",
                "",
                "",
                "script-1",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false))
        .thenReturn(false);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            bindingRepository,
            workItemRepository,
            eventAuditRepository,
            new BuiltInScriptEventRegistryService(),
            automationQueueService,
            outputProperties(),
            Mockito.mock(GameSessionControlPlaneClient.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            enabledPluginRuntimeStateService(),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    service.admit(
        TriggerScriptEventRequest.newBuilder()
            .setTenantId("1")
            .setScriptId("script-1")
            .setEventType("onLoad")
            .setScriptPatchVersion("patch-1")
            .setScriptEventId("onload:1:patch-1:script-1")
            .setWorldSlug("demo")
            .build());

    ArgumentCaptor<ScriptEventIngressAudit> ingressCaptor =
        ArgumentCaptor.forClass(ScriptEventIngressAudit.class);
    verify(repository).save(ingressCaptor.capture());
    assertThat(ingressCaptor.getValue().getWorldSlug()).isBlank();
    assertThat(ingressCaptor.getValue().getRealmSlug()).isBlank();
    assertThat(ingressCaptor.getValue().getPointerVersion()).isBlank();
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getWorldSlug()).isBlank();
    assertThat(workItemCaptor.getValue().getRealmSlug()).isBlank();
    assertThat(workItemCaptor.getValue().getPointerVersion()).isBlank();
  }

  @Test
  void rejectsPluginTriggerWhenActiveVersionDoesNotMatch() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .build())
                .build());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    when(pluginRuntimeStateService.getStatus("1", "game-1", "plugin-1"))
        .thenReturn(
            Optional.of(
                new PluginRuntimeStateService.PluginRuntimeStatus(
                    "plugin-v2",
                    "",
                    "region-1",
                    7L,
                    PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    100L,
                    "req-1",
                    "admin",
                    System.currentTimeMillis(),
                    null,
                    null)));
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            pluginRuntimeStateService,
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-plugin-mismatch")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("plugin_version_unavailable");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsPluginTriggerWhenPolicyObservationIsStale() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .build())
                .build());
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);
    when(pluginRuntimeStateService.getStatus("1", "game-1", "plugin-1"))
        .thenReturn(
            Optional.of(
                new PluginRuntimeStateService.PluginRuntimeStatus(
                    "plugin-v1",
                    "",
                    "region-1",
                    7L,
                    PluginState.PLUGIN_STATE_ENABLED,
                    "operator_activation",
                    100L,
                    "req-1",
                    "admin",
                    1L,
                    null,
                    null)));
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            pluginRuntimeStateService,
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-plugin-policy-stale")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("signer_policy_unavailable");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void quotaDeniedHandlerWritesAuditWithoutWorkItem() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository eventAuditRepository =
        Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptQuotaService quotaService = Mockito.mock(ScriptQuotaService.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "onCommand",
                "v1",
                "patch-1",
                "event-quota",
                false))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-1", "ENTITY", "entity-1")));
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .build())
                .build());
    when(quotaService.tryAcquire("1", "script-1")).thenReturn(false);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            bindingRepository,
            workItemRepository,
            eventAuditRepository,
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            quotaService,
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-quota")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    ArgumentCaptor<ScriptEventAudit> eventAuditCaptor =
        ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).save(eventAuditCaptor.capture());
    assertThat(eventAuditCaptor.getValue().getWorkItemId()).isNull();
    assertThat(eventAuditCaptor.getValue().getFinalOutcome()).isEqualTo("quota_denied");
    assertThat(eventAuditCaptor.getValue().getFinalReason()).isEqualTo("script_quota_denied");
    verify(workItemRepository, never()).save(Mockito.any());
  }

  @Test
  void dryRunBudgetDenialStopsBeforeHandlerResolution() {
    SessionContext.setContext(
        "operator-1", List.of("platformAdmin"), Map.of(), true, "game-session-service", "gs-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptDryRunQuotaService dryRunQuotaService = Mockito.mock(ScriptDryRunQuotaService.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "onCommand",
                "v1",
                "patch-1",
                "event-dry-run-denied",
                true))
        .thenReturn(Optional.empty());
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .build())
                .build());
    when(dryRunQuotaService.tryAcquire("1", "script-1", "account:operator-1")).thenReturn(false);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            bindingRepository,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            dryRunQuotaService);

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setScriptId("script-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-dry-run-denied")
                .setReadSnapshotToken("snapshot-1")
                .setIsDryRun(true)
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_QUOTA_DENIED.name());
    assertThat(admission.reason()).isEqualTo("dry_run_budget_exceeded");
    verify(bindingRepository, never())
        .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
            Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "demo",
                "production",
                "17",
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.admit(
                gameplayRequestBuilder()
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
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("version_unavailable");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsWhenPlayableStateScopeDoesNotMatchObservedRuntimeState() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("playable_state_scope_mismatch");
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
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
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
            gameSessionControlPlaneClient,
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("pin_state_unavailable");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsAdmissionWhenAutomationScopeIsPausedForRollback() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .build())
                .build());
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1",
                "game-1",
                "region-1",
                "PAUSED_FOR_ROLLBACK",
                2L,
                "req-2",
                "admin",
                "rollback",
                200L));
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            gameSessionControlPlaneClient,
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK.name());
    assertThat(admission.reason()).isEqualTo("rollback_paused");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsGameplayTriggerWhenPlayableStateScopeIsMissing() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            Mockito.mock(GameSessionControlPlaneClient.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            gameplayRequestBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-missing-scope")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("missing_trigger_identity");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  @Test
  void rejectsGameplayTriggerWhenRoutingBundleIsMissing() {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties(),
            Mockito.mock(GameSessionControlPlaneClient.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService());

    ScriptEventIngressService.TriggerAdmission admission =
        service.admit(
            TriggerScriptEventRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setRegionEpoch(7)
                .setEntityId("entity-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-missing-routing")
                .setReadSnapshotToken("snapshot-1")
                .setPayloadJson("{\"commandId\":\"cmd-1\",\"commandName\":\"LOOK\"}")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("missing_gameplay_routing_bundle");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  private static ScriptEventBinding binding(String scriptId, String scopeType, String scopeId) {
    return binding(scriptId, scopeType, scopeId, "normal");
  }

  private static ScriptDefinition scriptDefinition(
      String scriptName, String pluginId, String pluginVersionId) {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setName(scriptName);
    definition.setTenantId(1L);
    definition.setScriptVersion("patch-1");
    definition.setDefinition(
        "{\"pluginId\":\"" + pluginId + "\",\"pluginVersionId\":\"" + pluginVersionId + "\"}");
    return definition;
  }

  private static ScriptEventBinding binding(
      String scriptId, String scopeType, String scopeId, String priorityTag) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setScriptId(scriptId);
    binding.setTargetScopeType(scopeType);
    binding.setTargetScopeId(scopeId);
    binding.setPriorityTag(priorityTag);
    binding.setEnabled(true);
    return binding;
  }

  private static ScriptOutputProperties outputProperties() {
    return new ScriptOutputProperties();
  }

  private static TriggerScriptEventRequest.Builder gameplayRequestBuilder() {
    return TriggerScriptEventRequest.newBuilder()
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion("17")
        .setPayloadJson("{\"commandId\":\"cmd-1\",\"commandName\":\"LOOK\"}");
  }
}
