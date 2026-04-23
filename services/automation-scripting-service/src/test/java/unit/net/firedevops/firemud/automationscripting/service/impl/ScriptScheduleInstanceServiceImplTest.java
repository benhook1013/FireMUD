package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.config.ScriptSchedulerProperties;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleInstanceRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScriptScheduleInstanceServiceImplTest {
  private ScriptScheduleDefinitionRepository scheduleDefinitionRepository;
  private ScriptScheduleInstanceRepository scheduleInstanceRepository;
  private ScriptPatchPinProjectionRepository pinProjectionRepository;
  private PluginRuntimeStateRepository pluginRuntimeStateRepository;
  private ScriptEventBindingRepository bindingRepository;
  private ScriptWorkItemRepository workItemRepository;
  private ScriptEventAuditRepository eventAuditRepository;
  private AutomationQueueService automationQueueService;
  private AutomationAdmissionStateService automationAdmissionStateService;
  private ScriptScheduleInstanceService service;

  @BeforeEach
  void setup() {
    scheduleDefinitionRepository = mock(ScriptScheduleDefinitionRepository.class);
    scheduleInstanceRepository = mock(ScriptScheduleInstanceRepository.class);
    pinProjectionRepository = mock(ScriptPatchPinProjectionRepository.class);
    pluginRuntimeStateRepository = mock(PluginRuntimeStateRepository.class);
    bindingRepository = mock(ScriptEventBindingRepository.class);
    workItemRepository = mock(ScriptWorkItemRepository.class);
    eventAuditRepository = mock(ScriptEventAuditRepository.class);
    automationQueueService = mock(AutomationQueueService.class);
    automationAdmissionStateService = mock(AutomationAdmissionStateService.class);
    when(automationAdmissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 4L, "", "", "", 0L));
    when(workItemRepository.saveAndFlush(org.mockito.Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service =
        new ScriptScheduleInstanceServiceImpl(
            scheduleDefinitionRepository,
            scheduleInstanceRepository,
            pinProjectionRepository,
            pluginRuntimeStateRepository,
            bindingRepository,
            workItemRepository,
            eventAuditRepository,
            automationQueueService,
            automationAdmissionStateService,
            new ScriptSchedulerProperties());
  }

  @Test
  void reconcileObservedRuntimeStateMaterializesMillisecondAndTickSchedules() {
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition(), tickDefinition()));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 10, false),
                binding("npc-guard", "onInterval", "ENTITY", "guard-1", 20, true)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setRuntimeVersionId("runtime-v2")
            .setScriptPatchPinnedControlPlaneRequestId("req-1")
            .setScriptPatchPinnedAtMs(1_000L)
            .build());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    List<ScriptScheduleInstance> saved = captor.getValue();
    assertThat(saved).hasSize(2);
    assertThat(saved)
        .filteredOn(instance -> instance.getScheduleDefinitionId().equals("guard.alert.expire.v1"))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("READY");
              assertThat(instance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(6_000L));
              assertThat(instance.getObservedRuntimeVersionId()).isEqualTo("runtime-v2");
              assertThat(instance.getLastObservedControlPlaneRequestId()).isEqualTo("req-1");
              assertThat(instance.getTargetScopeType()).isEqualTo("ENTITY");
              assertThat(instance.getTargetScopeId()).isEqualTo("guard-1");
              assertThat(instance.getBindingPriority()).isEqualTo(10);
            });
    assertThat(saved)
        .filteredOn(instance -> instance.getScheduleDefinitionId().equals("guard.patrol.v1"))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
              assertThat(instance.getNextDueAt()).isNull();
              assertThat(instance.getNextDueTickId()).isNull();
              assertThat(instance.isRequiresExclusiveEvent()).isTrue();
            });
  }

  @Test
  void reconcilePinnedPatchInstancesReusesObservedPins() {
    ScriptPatchPinProjection projection = new ScriptPatchPinProjection();
    projection.setTenantId("1");
    projection.setGameInstanceId("game-1");
    projection.setObservedPinnedScriptPatchVersion("patch-1");
    projection.setLastObservedControlPlaneRequestId("req-3");
    projection.setObservedAt(Instant.ofEpochMilli(3_000L));
    when(pinProjectionRepository.findByTenantIdAndObservedPinnedScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(projection));
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition()));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 10, false)));

    service.reconcilePinnedPatchInstances("1", "patch-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(
            instance ->
                assertThat(instance.getPinObservedAt()).isEqualTo(Instant.ofEpochMilli(3_000L)));
  }

  @Test
  void reconcileObservedRuntimeStateOnlyMaterializesEnabledPluginVersionSchedules() {
    ScriptScheduleDefinition core = millisecondsDefinition();
    ScriptScheduleDefinition enabledPlugin = pluginDefinition("town-crier", "town-crier-v3");
    ScriptScheduleDefinition disabledPlugin = pluginDefinition("town-crier", "town-crier-v2");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(core, enabledPlugin, disabledPlugin));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    PluginRuntimeState runtimeState = new PluginRuntimeState();
    runtimeState.setTenantId("1");
    runtimeState.setGameInstanceId("game-1");
    runtimeState.setPluginId("town-crier");
    runtimeState.setActivePluginVersionId("town-crier-v3");
    runtimeState.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(runtimeState));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 10, false),
                binding("plugin-town-crier", "onInterval", "GLOBAL", "", 5, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .build());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(ScriptScheduleInstance::getScheduleDefinitionId)
        .containsExactlyInAnyOrder("guard.alert.expire.v1", "town-crier.market.pulse.v1");
  }

  @Test
  void observeRuntimeTickProgressAdvancesTickSchedulesFromHeartbeat() {
    ScriptScheduleInstance tickInstance = new ScriptScheduleInstance();
    tickInstance.setTenantId("1");
    tickInstance.setGameInstanceId("game-1");
    tickInstance.setScriptPatchVersion("patch-1");
    tickInstance.setScriptId("npc-guard");
    tickInstance.setEventType("onInterval");
    tickInstance.setScheduleDefinitionId("guard.patrol.v1");
    tickInstance.setScheduleKind("INTERVAL");
    tickInstance.setCadenceUnit("TICKS");
    tickInstance.setCadenceValue(30L);
    tickInstance.setMaterializationStatus("PENDING_RUNTIME_PROGRESS");
    tickInstance.setScheduleMetadataJson("{}");
    tickInstance.setScheduleSemanticsHash("hash-ticks");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 100L, 5_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isZero();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("READY");
              assertThat(instance.getRuntimeRegionId()).isEqualTo("region-1");
              assertThat(instance.getRuntimeRegionEpoch()).isEqualTo(12L);
              assertThat(instance.getLastObservedTickId()).isEqualTo(100L);
              assertThat(instance.getLastRuntimeProgressObservedAt())
                  .isEqualTo(Instant.ofEpochMilli(5_000L));
              assertThat(instance.getNextDueTickId()).isEqualTo(130L);
              assertThat(instance.getNextDueAt()).isNull();
            });
  }

  @Test
  void observeRuntimeTickProgressEmitsDueTimerWorkItemAndAdvancesPastObservedTick() {
    ScriptScheduleInstance tickInstance = new ScriptScheduleInstance();
    tickInstance.setTenantId("1");
    tickInstance.setGameInstanceId("game-1");
    tickInstance.setScriptPatchVersion("patch-1");
    tickInstance.setScriptId("npc-guard");
    tickInstance.setEventType("onInterval");
    tickInstance.setScheduleDefinitionId("guard.patrol.v1");
    tickInstance.setScheduleKind("INTERVAL");
    tickInstance.setCadenceUnit("TICKS");
    tickInstance.setCadenceValue(30L);
    tickInstance.setPriorityTag("high");
    tickInstance.setTargetScopeType("ENTITY");
    tickInstance.setTargetScopeId("guard-1");
    tickInstance.setMaterializationStatus("READY");
    tickInstance.setRuntimeRegionId("region-1");
    tickInstance.setRuntimeRegionEpoch(12L);
    tickInstance.setLastObservedTickId(100L);
    tickInstance.setNextDueTickId(130L);
    tickInstance.setScheduleMetadataJson("{}");
    tickInstance.setScheduleSemanticsHash("hash-ticks");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).saveAndFlush(workItemCaptor.capture());
    ScriptWorkItem workItem = workItemCaptor.getValue();
    assertThat(workItem.getTenantId()).isEqualTo("1");
    assertThat(workItem.getGameInstanceId()).isEqualTo("game-1");
    assertThat(workItem.getRegionId()).isEqualTo("region-1");
    assertThat(workItem.getRegionEpoch()).isEqualTo(12L);
    assertThat(workItem.getEntityId()).isEqualTo("guard-1");
    assertThat(workItem.getTriggerMode()).isEqualTo("TRIGGER_MODE_CATCH_UP");
    assertThat(workItem.getPriorityTag()).isEqualTo("high");
    assertThat(workItem.getPayloadJson()).contains("\"dueTickId\":130");
    verify(automationQueueService).enqueueWorkItem(workItem);
    verify(eventAuditRepository).save(org.mockito.Mockito.any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> scheduleCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(scheduleCaptor.capture());
    assertThat(scheduleCaptor.getValue())
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getNextDueTickId()).isEqualTo(160L);
              assertThat(instance.getLastObservedTickId()).isEqualTo(131L);
            });
  }

  private static ScriptScheduleDefinition millisecondsDefinition() {
    ScriptScheduleDefinition definition = new ScriptScheduleDefinition();
    definition.setTenantId(1L);
    definition.setScriptPatchVersion("patch-1");
    definition.setScriptId("npc-guard");
    definition.setEventType("onTimerExpire");
    definition.setScheduleDefinitionId("guard.alert.expire.v1");
    definition.setScheduleKind("TIMER");
    definition.setCadenceUnit("MILLISECONDS");
    definition.setCadenceValue(5_000L);
    definition.setPriorityTag("normal");
    definition.setScheduleMetadataJson("{\"scheduleDefinitionId\":\"guard.alert.expire.v1\"}");
    definition.setScheduleSemanticsHash("hash-ms");
    return definition;
  }

  private static ScriptScheduleDefinition tickDefinition() {
    ScriptScheduleDefinition definition = new ScriptScheduleDefinition();
    definition.setTenantId(1L);
    definition.setScriptPatchVersion("patch-1");
    definition.setScriptId("npc-guard");
    definition.setEventType("onInterval");
    definition.setScheduleDefinitionId("guard.patrol.v1");
    definition.setScheduleKind("INTERVAL");
    definition.setCadenceUnit("TICKS");
    definition.setCadenceValue(30L);
    definition.setPriorityTag("high");
    definition.setScheduleMetadataJson("{\"scheduleDefinitionId\":\"guard.patrol.v1\"}");
    definition.setScheduleSemanticsHash("hash-ticks");
    return definition;
  }

  private static ScriptScheduleDefinition pluginDefinition(
      String pluginId, String pluginVersionId) {
    ScriptScheduleDefinition definition = new ScriptScheduleDefinition();
    definition.setTenantId(1L);
    definition.setScriptPatchVersion("patch-1");
    definition.setScriptId("plugin-town-crier");
    definition.setPluginId(pluginId);
    definition.setPluginVersionId(pluginVersionId);
    definition.setEventType("onInterval");
    definition.setScheduleDefinitionId("town-crier.market.pulse.v1");
    definition.setScheduleKind("INTERVAL");
    definition.setCadenceUnit("TICKS");
    definition.setCadenceValue(12L);
    definition.setPriorityTag("normal");
    definition.setScheduleMetadataJson(
        "{\"scheduleDefinitionId\":\"town-crier.market.pulse.v1\",\"pluginId\":\""
            + pluginId
            + "\",\"pluginVersionId\":\""
            + pluginVersionId
            + "\"}");
    definition.setScheduleSemanticsHash("hash-plugin-" + pluginVersionId);
    return definition;
  }

  private static ScriptEventBinding binding(
      String scriptId,
      String eventType,
      String targetScopeType,
      String targetScopeId,
      int priority,
      boolean requiresExclusiveEvent) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-1");
    binding.setScriptId(scriptId);
    binding.setEventType(eventType);
    binding.setEventSchemaVersion("v1");
    binding.setTargetScopeType(targetScopeType);
    binding.setTargetScopeId(targetScopeId);
    binding.setPriority(priority);
    binding.setRequiresExclusiveEvent(requiresExclusiveEvent);
    binding.setEnabled(true);
    return binding;
  }
}
