package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
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
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ScriptEventIngressServiceImplTest {
  private static AutomationAdmissionStateService admissionStateService() {
    AutomationAdmissionStateService service = Mockito.mock(AutomationAdmissionStateService.class);
    when(service.getState(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 42L, "", "", "", 100L));
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
    when(service.getActivePluginVersions("1", "game-1", "region-1", 7L))
        .thenReturn(Map.of("plugin-1", "plugin-v1"));
    return service;
  }

  private static ScriptQuotaService allowingQuotaService() {
    ScriptQuotaService service = Mockito.mock(ScriptQuotaService.class);
    when(service.tryAcquire(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
    return service;
  }

  private static ScriptQuotaService denyingQuotaService() {
    ScriptQuotaService service = Mockito.mock(ScriptQuotaService.class);
    when(service.tryAcquire(Mockito.anyString(), Mockito.anyString())).thenReturn(false);
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
  void rejectsZeroTenantIdBeforeLookupAndAuditWrite() {
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
    ScriptQuotaService quotaService = allowingQuotaService();
    ScriptDryRunQuotaService dryRunQuotaService = allowingDryRunQuotaService();
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
            Mockito.mock(PluginRuntimeStateService.class),
            quotaService,
            dryRunQuotaService);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.admit(
                gameplayRequestBuilder()
                    .setTenantId("0")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-1")
                    .setRegionEpoch(7)
                    .setEntityId("entity-1")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .setEventType("onCommand")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("event-1")
                    .build()));
    verifyNoInteractions(
        repository,
        bindingRepository,
        workItemRepository,
        eventAuditRepository,
        automationQueueService,
        gameSessionControlPlaneClient,
        quotaService,
        dryRunQuotaService);
  }

  @Test
  void rejectsMalformedGameplayPointerVersionBeforeLookupAndAuditWrite() {
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
    ScriptQuotaService quotaService = allowingQuotaService();
    ScriptDryRunQuotaService dryRunQuotaService = allowingDryRunQuotaService();
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
            Mockito.mock(PluginRuntimeStateService.class),
            quotaService,
            dryRunQuotaService);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.admit(
                gameplayRequestBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-1")
                    .setRegionEpoch(7)
                    .setEntityId("entity-1")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setPointerVersion("bad-pointer")
                    .setEventType("onCommand")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("event-1")
                    .build(),
                "game-session-service"));
    verifyNoInteractions(
        repository,
        bindingRepository,
        workItemRepository,
        eventAuditRepository,
        automationQueueService,
        gameSessionControlPlaneClient,
        quotaService,
        dryRunQuotaService);
  }

  @Test
  void rejectsZeroGameplayPointerVersionBeforeLookupAndAuditWrite() {
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
    ScriptQuotaService quotaService = allowingQuotaService();
    ScriptDryRunQuotaService dryRunQuotaService = allowingDryRunQuotaService();
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
            Mockito.mock(PluginRuntimeStateService.class),
            quotaService,
            dryRunQuotaService);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.admit(
                gameplayRequestBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-1")
                    .setRegionEpoch(7)
                    .setEntityId("entity-1")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setPointerVersion("0")
                    .setEventType("onCommand")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("event-1")
                    .build(),
                "game-session-service"));
    verifyNoInteractions(
        repository,
        bindingRepository,
        workItemRepository,
        eventAuditRepository,
        automationQueueService,
        gameSessionControlPlaneClient,
        quotaService,
        dryRunQuotaService);
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
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
                        .setScriptPinEpoch(1L)
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
    verify(gameSessionControlPlaneClient)
        .getGameInstanceRuntimeState("1", "game-1", "region-1");
    assertThat(auditCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(auditCaptor.getValue().getScriptPinEpoch()).isEqualTo(1L);
    assertThat(auditCaptor.getValue().getSourceService()).isEqualTo("game-session-service");
    assertThat(auditCaptor.getValue().isAdmitted()).isTrue();
    assertThat(auditCaptor.getValue().getResolvedHandlerCount()).isEqualTo(1);
    assertThat(auditCaptor.getValue().getWorldSlug()).isEqualTo("demo");
    assertThat(auditCaptor.getValue().getRealmSlug()).isEqualTo("production");
    assertThat(auditCaptor.getValue().getPointerVersion()).isEqualTo("17");
    assertThat(auditCaptor.getValue().getSourceKind()).isEqualTo("GAMEPLAY_EVENT");
    assertThat(auditCaptor.getValue().getSourceState()).isEqualTo("TRIGGER_ADMITTED");
    assertThat(auditCaptor.getValue().getQuotaClass())
        .isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(workItemCaptor.getValue().getScriptPinEpoch()).isEqualTo(1L);
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
    assertThat(workItemCaptor.getValue().getAdmissionEpoch()).isEqualTo(42L);
    ArgumentCaptor<ScriptEventAudit> eventAuditCaptor =
        ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).save(eventAuditCaptor.capture());
    assertThat(eventAuditCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(eventAuditCaptor.getValue().getScriptPinEpoch()).isEqualTo(1L);
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
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
                        .setScriptPinEpoch(1L)
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
    assertThat(workItemCaptor.getValue().getPluginId()).isEqualTo("plugin-1");
    assertThat(workItemCaptor.getValue().getPluginVersionId()).isEqualTo("plugin-v1");
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void resolvesPluginOwnedHandlersFromRuntimeActivationWhenRequestIsNotPluginScoped() {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(workItemRepository.save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-activation-owned",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(
            List.of(
                binding("script-first-party", "ENTITY", "entity-1", "high"),
                binding("script-active-plugin", "ENTITY", "entity-1", "medium"),
                binding("script-stale-plugin", "ENTITY", "entity-1", "low")));
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(
            List.of(
                scriptDefinitionJson("script-first-party", "{}"),
                scriptDefinition("script-active-plugin", "plugin-1", "plugin-v1"),
                scriptDefinition("script-stale-plugin", "plugin-2", "plugin-v2")));
    when(pluginRuntimeStateService.getActivePluginVersions("1", "game-1", "region-1", 7L))
        .thenReturn(Map.of("plugin-1", "plugin-v1"));
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
                        .setScriptPinEpoch(1L)
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
            pluginRuntimeStateService,
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
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-activation-owned")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(2);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues())
        .extracting(ScriptWorkItem::getScriptId)
        .containsExactly("script-first-party", "script-active-plugin");
    assertThat(workItemCaptor.getAllValues())
        .extracting(ScriptWorkItem::getPluginId)
        .containsExactly("", "plugin-1");
    assertThat(workItemCaptor.getAllValues())
        .extracting(ScriptWorkItem::getPluginVersionId)
        .containsExactly("", "plugin-v1");
    verify(automationQueueService, Mockito.times(2))
        .enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void skipsActivePluginLookupWhenAllCandidateBindingsAreFirstParty() {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(workItemRepository.save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-first-party-only",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-first-party", "ENTITY", "entity-1", "high")));
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(List.of(scriptDefinitionJson("script-first-party", "{}")));

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
                        .setScriptPinEpoch(1L)
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
            pluginRuntimeStateService,
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
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-first-party-only")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    verify(pluginRuntimeStateService, never())
        .getActivePluginVersions(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
  }

  @ParameterizedTest(name = "rejects unresolved binding definition: {0}")
  @MethodSource("unresolvedBindingDefinitions")
  void rejectsGameplayEventWhenBindingDefinitionIsMissingOrDuplicated(
      String scenario,
      List<ScriptEventBinding> bindings,
      List<ScriptDefinition> definitions,
      String scriptEventId) {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(bindings);
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(definitions);
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
                        .setScriptPinEpoch(1L)
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
            pluginRuntimeStateService,
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
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId(scriptEventId)
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("plugin_binding_unresolved");
    ArgumentCaptor<ScriptEventIngressAudit> auditCaptor =
        ArgumentCaptor.forClass(ScriptEventIngressAudit.class);
    verify(repository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAdmissionReason()).isEqualTo("plugin_binding_unresolved");
    verify(pluginRuntimeStateService, never())
        .getActivePluginVersions(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
    verify(workItemRepository, never()).save(Mockito.any(ScriptWorkItem.class));
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  private static Stream<Arguments> unresolvedBindingDefinitions() {
    return Stream.of(
        Arguments.of(
            "missing",
            List.of(
                binding("script-present", "ENTITY", "entity-1", "high"),
                binding("script-missing", "ENTITY", "entity-1", "low")),
            List.of(scriptDefinitionJson("script-present", "{}")),
            "event-missing-definition"),
        Arguments.of(
            "duplicated",
            List.of(binding("script-present", "ENTITY", "entity-1", "high")),
            List.of(
                scriptDefinition("script-present", "plugin-1", "plugin-v1"),
                scriptDefinition("script-present", "plugin-2", "plugin-v2")),
            "event-duplicate-definition"));
  }

  @Test
  void rejectsPluginOwnedBindingsWhenNoActiveRuntimeVersionMatches() {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-activation-miss",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-plugin", "ENTITY", "entity-1", "high")));
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(List.of(scriptDefinition("script-plugin", "plugin-1", "plugin-v1")));
    when(pluginRuntimeStateService.getActivePluginVersions("1", "game-1", "region-1", 7L))
        .thenReturn(Map.of("plugin-1", "plugin-v2"));
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
                        .setScriptPinEpoch(1L)
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
            pluginRuntimeStateService,
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
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-activation-miss")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("plugin_binding_unresolved");
    verify(workItemRepository, never()).save(Mockito.any(ScriptWorkItem.class));
    verify(eventAuditRepository, never()).save(Mockito.any(ScriptEventAudit.class));
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void rejectsGameplayEventWhenActivePluginDoesNotOwnAnyScriptBinding() {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
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
                scriptDefinition("script-foreign", "plugin-2", "plugin-v1")));
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
                        .setScriptPinEpoch(1L)
                        .build())
                .build());
    when(pluginRuntimeStateService.getStatus("1", "game-1", "plugin-2"))
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
            pluginRuntimeStateService,
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
                .setPluginId("plugin-2")
                .setPluginVersionId("plugin-v2")
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("plugin_binding_unresolved");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
    verify(workItemRepository, never()).save(Mockito.any(ScriptWorkItem.class));
    verify(eventAuditRepository, never()).save(Mockito.any(ScriptEventAudit.class));
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @ParameterizedTest(name = "rejects malformed plugin metadata {0}")
  @MethodSource("malformedPluginMetadata")
  void rejectsGameplayEventWhenPluginOwnedDefinitionIsMalformed(String definitionJson) {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-partial-plugin-owner",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-plugin", "ENTITY", "entity-1", "high")));
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(List.of(scriptDefinitionJson("script-plugin", definitionJson)));
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
                        .setScriptPinEpoch(1L)
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
            pluginRuntimeStateService,
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
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-partial-plugin-owner")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("plugin_binding_unresolved");
    verify(pluginRuntimeStateService, never())
        .getActivePluginVersions(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
    verify(workItemRepository, never()).save(Mockito.any(ScriptWorkItem.class));
    verify(eventAuditRepository, never()).save(Mockito.any(ScriptEventAudit.class));
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  private static Stream<String> malformedPluginMetadata() {
    return Stream.of(
        "{\"pluginId\":\"plugin-1\"}",
        "{\"pluginId\":123,\"pluginVersionId\":\"plugin-v1\"}",
        "{\"pluginId\":\"plugin-1\",\"pluginVersionId\":\"plugin-v1\",\"plugin\":{\"pluginId\":\"plugin-2\",\"pluginVersionId\":\"plugin-v2\"}}",
        "{\"pluginId\":\"plugin-1\",\"pluginVersionId\":\"plugin-v1\",\"owner\":{\"pluginId\":\"plugin-1\"}}",
        "{\"pluginId\":\"plugin-1\"");
  }

  @ParameterizedTest(name = "rejects handler-only plugin owner when active version is {0}")
  @MethodSource("handlerOnlyInactivePluginVersions")
  void rejectsHandlerOnlyPluginOwnerWhenActivationIsNotCurrent(
      Map<String, String> activePluginVersions) {
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
    PluginRuntimeStateService pluginRuntimeStateService =
        Mockito.mock(PluginRuntimeStateService.class);

    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-handler-only-plugin",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-handler-only", "ENTITY", "entity-1", "high")));
    when(scriptDefinitionRepository.findByTenantIdAndScriptVersionAndNameIn(
            Mockito.eq(1L), Mockito.eq("patch-1"), Mockito.anyList()))
        .thenReturn(
            List.of(
                scriptDefinitionJson(
                    "script-handler-only",
                    "{\"eventHandlers\":{\"onCommand\":{\"pluginId\":\"plugin-1\",\"pluginVersionId\":\"plugin-v1\"}}}")));
    when(pluginRuntimeStateService.getActivePluginVersions("1", "game-1", "region-1", 7L))
        .thenReturn(activePluginVersions);
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
                        .setScriptPinEpoch(1L)
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
            pluginRuntimeStateService,
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
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-handler-only-plugin")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("plugin_binding_unresolved");
    verify(workItemRepository, never()).save(Mockito.any(ScriptWorkItem.class));
    verify(eventAuditRepository, never()).save(Mockito.any(ScriptEventAudit.class));
    verify(automationQueueService, never()).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  private static Stream<Map<String, String>> handlerOnlyInactivePluginVersions() {
    return Stream.of(Map.of(), Map.of("plugin-1", "plugin-v2"));
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
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
                        .setScriptPinEpoch(1L)
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
            denyingQuotaService(),
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
  void resolvesOnCommandHandlersByCommandAliasScope() {
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-alias",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-1", "COMMAND_ALIAS", "look", "high")));
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
                        .setScriptPinEpoch(1L)
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
                .setScriptEventId("event-alias")
                .setReadSnapshotToken("snapshot-1")
                .setPayloadJson(
                    "{\"commandId\":\"cmd-1\",\"commandName\":\"LOOK\",\"commandAlias\":\"look\"}")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo("script-1");
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void resolvesOnCommandHandlersByActionCategoryScope() {
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-category",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-1", "ACTION_CATEGORY", "GAMEPLAY", "high")));
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
                        .setScriptPinEpoch(1L)
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
                .setScriptEventId("event-category")
                .setReadSnapshotToken("snapshot-1")
                .setPayloadJson(
                    "{\"commandId\":\"cmd-1\",\"commandName\":\"LOOK\",\"actionCategory\":\"GAMEPLAY\"}")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    verify(workItemRepository).save(Mockito.any(ScriptWorkItem.class));
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
  }

  @Test
  void resolvesOnCommandHandlersByActionTagScope() {
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-tag",
                false,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onCommand", "v1"))
        .thenReturn(List.of(binding("script-1", "ACTION_TAG", "COMMUNICATION", "high")));
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
                        .setScriptPinEpoch(1L)
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
                .setScriptEventId("event-tag")
                .setReadSnapshotToken("snapshot-1")
                .setPayloadJson(
                    "{\"commandId\":\"cmd-1\",\"commandName\":\"SAY\",\"actionCategory\":\"SOCIAL\",\"actionTags\":[\"COMMUNICATION\"]}")
                .build());

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    verify(workItemRepository).save(Mockito.any(ScriptWorkItem.class));
    verify(automationQueueService).enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
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
  void rejectsFractionalTimerDuePointBeforeHandlerResolution() {
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptEventBindingRepository bindingRepository =
        Mockito.mock(ScriptEventBindingRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository eventAuditRepository =
        Mockito.mock(ScriptEventAuditRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
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
                .setEventType("onTimerExpire")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("timer-1")
                .setReadSnapshotToken("snapshot-1")
                .setPayloadJson("{\"scheduleId\":\"timer-1\",\"dueTickId\":1.5}")
                .build(),
            "automation-scripting-service");

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(
            TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_EVENT_REGISTRY_REJECTED.name());
    assertThat(admission.reason()).isEqualTo("invalid_built_in_payload");
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
    verifyNoInteractions(
        bindingRepository, workItemRepository, eventAuditRepository, automationQueueService);
  }

  @Test
  void rejectsTimerPayloadWhenBothDuePointFieldsArePresent() {
    TimerIngressFixture fixture = timerIngressFixture();
    ScriptEventIngressService.TriggerAdmission admission =
        fixture
            .service()
            .admit(
                gameplayRequestBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-1")
                    .setRegionEpoch(7)
                    .setEntityId("entity-1")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .setEventType("onTimerExpire")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("timer-both-due-points")
                    .setReadSnapshotToken("snapshot-1")
                    .setPayloadJson("{\"scheduleId\":\"timer-1\",\"dueTickId\":3,\"dueAt\":4000}")
                    .build(),
                "automation-scripting-service");

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("invalid_built_in_payload");
    verify(fixture.repository()).save(Mockito.any(ScriptEventIngressAudit.class));
    verify(fixture.eventAuditRepository(), never()).save(Mockito.any(ScriptEventAudit.class));
    verify(fixture.workItemRepository(), never()).save(Mockito.any(ScriptWorkItem.class));
    verify(fixture.automationQueueService(), never())
        .enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
    verifyNoInteractions(fixture.bindingRepository());
  }

  @Test
  void rejectsTimerPayloadWhenNeitherDuePointFieldIsPresent() {
    TimerIngressFixture fixture = timerIngressFixture();
    ScriptEventIngressService.TriggerAdmission admission =
        fixture
            .service()
            .admit(
                gameplayRequestBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-1")
                    .setRegionEpoch(7)
                    .setEntityId("entity-1")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .setEventType("onInterval")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("timer-no-due-point")
                    .setReadSnapshotToken("snapshot-1")
                    .setPayloadJson("{\"scheduleId\":\"timer-1\"}")
                    .build(),
                "automation-scripting-service");

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("invalid_built_in_payload");
    verify(fixture.repository()).save(Mockito.any(ScriptEventIngressAudit.class));
    verify(fixture.eventAuditRepository(), never()).save(Mockito.any(ScriptEventAudit.class));
    verify(fixture.workItemRepository(), never()).save(Mockito.any(ScriptWorkItem.class));
    verify(fixture.automationQueueService(), never())
        .enqueueWorkItem(Mockito.any(ScriptWorkItem.class));
    verifyNoInteractions(fixture.bindingRepository());
  }

  @Test
  void admitsTimerPayloadWithDueAtWithoutDueTickId() {
    TimerIngressFixture fixture = timerIngressFixture();
    when(fixture
            .bindingRepository()
            .findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
                1L, "patch-1", "onTimerExpire", "v1"))
        .thenReturn(List.of(binding("script-1", "ENTITY", "entity-1", "normal")));
    when(fixture.workItemRepository().save(Mockito.any(ScriptWorkItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptEventIngressService.TriggerAdmission admission =
        fixture
            .service()
            .admit(
                gameplayRequestBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-1")
                    .setRegionEpoch(7)
                    .setEntityId("entity-1")
                    .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                    .setEventType("onTimerExpire")
                    .setScriptPatchVersion("patch-1")
                    .setScriptEventId("timer-due-at")
                    .setReadSnapshotToken("snapshot-1")
                    .setPayloadJson("{\"scheduleId\":\"timer-1\",\"dueAt\":4000}")
                    .build(),
                "automation-scripting-service");

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_ADMITTED.name());
    assertThat(admission.resolvedHandlerCount()).isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(fixture.workItemRepository()).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo("script-1");
    assertThat(workItemCaptor.getValue().getPayloadJson())
        .isEqualTo("{\"scheduleId\":\"timer-1\",\"dueAt\":4000}");
    verify(fixture.automationQueueService()).enqueueWorkItem(workItemCaptor.getValue());
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false,
                "automation-scripting-service"))
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
    assertThat(workItemCaptor.getValue().getQuotaClass())
        .isEqualTo(ScriptQuotaClasses.PUBLISH_READINESS);
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "",
                "",
                0L,
                "",
                "",
                "onLoad",
                "v1",
                "patch-1",
                "onload:1:patch-1:script-1",
                false,
                "automation-scripting-service"))
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
    assertThat(ingressCaptor.getValue().getQuotaClass())
        .isEqualTo(ScriptQuotaClasses.PUBLISH_READINESS);
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
                        .setScriptPinEpoch(1L)
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
                        .setScriptPinEpoch(1L)
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-quota",
                false,
                "game-session-service"))
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
                        .setScriptPinEpoch(1L)
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
        "41", List.of("platformAdmin"), Map.of(), true, "game-session-service", "gs-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptDryRunQuotaService dryRunQuotaService = Mockito.mock(ScriptDryRunQuotaService.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-dry-run-denied",
                true,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
                        .build())
                .build());
    when(dryRunQuotaService.tryAcquire("1", "script-1", "account:41")).thenReturn(false);
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
  void dryRunRejectsMalformedCurrentAccountClaimBeforeQuotaLookup() {
    SessionContext.setContext("not-a-long", List.of(), Map.of());
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptDryRunQuotaService dryRunQuotaService = Mockito.mock(ScriptDryRunQuotaService.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-dry-run-invalid-account",
                true,
                "game-session-service"))
        .thenReturn(Optional.empty());
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
                        .build())
                .build());
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

    assertThrows(
        IllegalArgumentException.class,
        () ->
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
                    .setScriptEventId("event-dry-run-invalid-account")
                    .setReadSnapshotToken("snapshot-1")
                    .setIsDryRun(true)
                    .build(),
                "game-session-service"));

    verifyNoInteractions(dryRunQuotaService);
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
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-session-service"))
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
  void keepsIngressClaimsSeparateWhenSourceServiceChanges() {
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    ScriptOutputProperties outputProperties = outputProperties();
    outputProperties.setMaxSerializedWorkItemBytes(1);
    when(repository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
                "1",
                "game-1",
                "region-1",
                7L,
                "entity-1",
                "SHARED",
                "onCommand",
                "v1",
                "patch-1",
                "event-1",
                false,
                "game-logic-service"))
        .thenReturn(Optional.empty());
    ScriptEventIngressService service =
        new ScriptEventIngressServiceImpl(
            repository,
            Mockito.mock(ScriptEventBindingRepository.class),
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            new BuiltInScriptEventRegistryService(),
            Mockito.mock(AutomationQueueService.class),
            outputProperties,
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
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setEventType("onCommand")
                .setScriptPatchVersion("patch-1")
                .setScriptEventId("event-1")
                .build(),
            "game-logic-service");

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("work_item_size_exceeded");
    verify(repository)
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRunAndSourceService(
            "1",
            "game-1",
            "region-1",
            7L,
            "entity-1",
            "SHARED",
            "onCommand",
            "v1",
            "patch-1",
            "event-1",
            false,
            "game-logic-service");
    ArgumentCaptor<ScriptEventIngressAudit> auditCaptor =
        ArgumentCaptor.forClass(ScriptEventIngressAudit.class);
    verify(repository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getSourceService()).isEqualTo("game-logic-service");
    assertThat(auditCaptor.getValue().getWorldSlug()).isEqualTo("demo");
    assertThat(auditCaptor.getValue().getRealmSlug()).isEqualTo("production");
    assertThat(auditCaptor.getValue().getPointerVersion()).isEqualTo("17");
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
                        .setScriptPinEpoch(1L)
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
    ArgumentCaptor<ScriptEventIngressAudit> auditCaptor =
        ArgumentCaptor.forClass(ScriptEventIngressAudit.class);
    verify(repository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(auditCaptor.getValue().getScriptPinEpoch()).isEqualTo(1L);
    verify(gameSessionControlPlaneClient)
        .getGameInstanceRuntimeState("1", "game-1", "region-1");
  }

  @ParameterizedTest(name = "rejects runtime authority from {0}/{1}")
  @MethodSource("mismatchedRuntimeScopes")
  void rejectsRuntimeStateFromDifferentScopeBeforeProjection(
      String runtimeTenantId, String runtimeGameInstanceId) {
    SessionContext.setContext(
        "svc", List.of(), Map.of(), true, "game-session-service", "game-session-1");
    ScriptEventIngressAuditRepository repository =
        Mockito.mock(ScriptEventIngressAuditRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptPatchPinProjectionService projectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId(runtimeTenantId)
                        .setGameInstanceId(runtimeGameInstanceId)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
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
            projectionService,
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
                .setScriptEventId("event-mismatched-runtime-scope")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("pin_state_unavailable");
    verifyNoInteractions(projectionService);
    verify(repository).save(Mockito.any(ScriptEventIngressAudit.class));
  }

  private static Stream<Arguments> mismatchedRuntimeScopes() {
    return Stream.of(Arguments.of("2", "game-1"), Arguments.of("1", "game-2"));
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
                        .setScriptPinEpoch(1L)
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
                        .setScriptPinEpoch(1L)
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
  void rejectsAdmissionWhenAutomationScopeModeIsCorrupt() {
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
                        .setScriptPinEpoch(1L)
                        .build())
                .build());
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "CORRUPT", 2L, "", "", "", 200L));
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
                .setScriptEventId("event-corrupt-admission")
                .setReadSnapshotToken("snapshot-1")
                .build());

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.outcome())
        .isEqualTo(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE.name());
    assertThat(admission.reason()).isEqualTo("admission_state_unavailable");
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
    return scriptDefinitionJson(
        scriptName,
        "{\"pluginId\":\"" + pluginId + "\",\"pluginVersionId\":\"" + pluginVersionId + "\"}");
  }

  private static ScriptDefinition scriptDefinitionJson(String scriptName, String definitionJson) {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setName(scriptName);
    definition.setTenantId(1L);
    definition.setScriptVersion("patch-1");
    definition.setDefinition(definitionJson);
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

  private static TimerIngressFixture timerIngressFixture() {
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
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
                        .setRegionId("region-1")
                        .setRegionEpoch(7L)
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                        .build())
                .build());
    return new TimerIngressFixture(
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
            Mockito.mock(PluginRuntimeStateService.class),
            allowingQuotaService(),
            allowingDryRunQuotaService()),
        repository,
        bindingRepository,
        eventAuditRepository,
        workItemRepository,
        automationQueueService);
  }

  private record TimerIngressFixture(
      ScriptEventIngressService service,
      ScriptEventIngressAuditRepository repository,
      ScriptEventBindingRepository bindingRepository,
      ScriptEventAuditRepository eventAuditRepository,
      ScriptWorkItemRepository workItemRepository,
      AutomationQueueService automationQueueService) {}
}
