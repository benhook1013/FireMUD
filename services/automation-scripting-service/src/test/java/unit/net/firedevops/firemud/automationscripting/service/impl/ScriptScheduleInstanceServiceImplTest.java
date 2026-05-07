package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptSchedulerProperties;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
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
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
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
  private GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private ScriptScheduleInstanceService service;
  private SimpleMeterRegistry meterRegistry;

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
    gameDesignControlPlaneClient = mock(GameDesignControlPlaneClient.class);
    meterRegistry = new SimpleMeterRegistry();
    when(automationAdmissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 4L, "", "", "", 0L));
    when(workItemRepository.saveAndFlush(org.mockito.Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignControlPlaneClient.getPublishedScriptPatchVersion(any(), any()))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setScriptPatchVersion("patch-1")
                        .setVersionId(17L)
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(150L)
                        .build())
                .build());
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
            gameDesignControlPlaneClient,
            new ScriptSchedulerProperties(),
            meterRegistry);
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
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(17L)
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
              assertThat(instance.getPlayableStateScope()).isEqualTo("SHARED");
              assertThat(instance.getWorldSlug()).isEqualTo("demo");
              assertThat(instance.getRealmSlug()).isEqualTo("production");
              assertThat(instance.getPointerVersion()).isEqualTo("17");
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
    projection.setWorldSlug("demo");
    projection.setRealmSlug("production");
    projection.setPointerVersion("17");
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
            instance -> {
              assertThat(instance.getPinObservedAt()).isEqualTo(Instant.ofEpochMilli(3_000L));
              assertThat(instance.getWorldSlug()).isEqualTo("demo");
              assertThat(instance.getRealmSlug()).isEqualTo("production");
              assertThat(instance.getPointerVersion()).isEqualTo("17");
            });
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
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(17L)
            .build());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(ScriptScheduleInstance::getScheduleDefinitionId)
        .containsExactlyInAnyOrder("guard.alert.expire.v1", "town-crier.market.pulse.v1");
  }

  @Test
  void reconcileObservedRuntimeStateIgnoresEnabledPluginRowsFromDifferentRuntimeRegion() {
    ScriptScheduleDefinition core = millisecondsDefinition();
    ScriptScheduleDefinition pluginSchedule = pluginDefinition("town-crier", "town-crier-v3");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(core, pluginSchedule));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    PluginRuntimeState runtimeState = new PluginRuntimeState();
    runtimeState.setTenantId("1");
    runtimeState.setGameInstanceId("game-1");
    runtimeState.setRuntimeRegionId("region-stale");
    runtimeState.setRuntimeRegionEpoch(7L);
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
            .setRegionId("region-live")
            .setRegionEpoch(7L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(17L)
            .build());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(ScriptScheduleInstance::getScheduleDefinitionId)
        .containsExactly("guard.alert.expire.v1");
  }

  @Test
  void reconcileObservedRuntimeStateIgnoresEnabledPluginRowsFromDifferentRuntimeEpoch() {
    ScriptScheduleDefinition core = millisecondsDefinition();
    ScriptScheduleDefinition pluginSchedule = pluginDefinition("town-crier", "town-crier-v3");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(core, pluginSchedule));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    PluginRuntimeState runtimeState = new PluginRuntimeState();
    runtimeState.setTenantId("1");
    runtimeState.setGameInstanceId("game-1");
    runtimeState.setRuntimeRegionId("region-1");
    runtimeState.setRuntimeRegionEpoch(6L);
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
            .setRegionId("region-1")
            .setRegionEpoch(7L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .setPointerVersion(17L)
            .build());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .extracting(ScriptScheduleInstance::getScheduleDefinitionId)
        .containsExactly("guard.alert.expire.v1");
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
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 100L, 5_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isZero();
    assertThat(result.truncatedFiringCount()).isZero();
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
    tickInstance.setPlayableStateScope("SHARED");
    tickInstance.setWorldSlug("demo");
    tickInstance.setRealmSlug("production");
    tickInstance.setPointerVersion("17");
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
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(result.truncatedFiringCount()).isZero();
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).saveAndFlush(workItemCaptor.capture());
    ScriptWorkItem workItem = workItemCaptor.getValue();
    assertThat(workItem.getTenantId()).isEqualTo("1");
    assertThat(workItem.getGameInstanceId()).isEqualTo("game-1");
    assertThat(workItem.getRegionId()).isEqualTo("region-1");
    assertThat(workItem.getRegionEpoch()).isEqualTo(12L);
    assertThat(workItem.getEntityId()).isEqualTo("guard-1");
    assertThat(workItem.getPlayableStateScope()).isEqualTo("SHARED");
    assertThat(workItem.getWorldSlug()).isEqualTo("demo");
    assertThat(workItem.getRealmSlug()).isEqualTo("production");
    assertThat(workItem.getPointerVersion()).isEqualTo("17");
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

  @Test
  void observeRuntimeTickProgressStampsAndFiresDueWallClockTimer() {
    ScriptScheduleInstance timerInstance = new ScriptScheduleInstance();
    timerInstance.setTenantId("1");
    timerInstance.setGameInstanceId("game-1");
    timerInstance.setScriptPatchVersion("patch-1");
    timerInstance.setScriptId("npc-guard");
    timerInstance.setEventType("onTimerExpire");
    timerInstance.setScheduleDefinitionId("guard.alert.expire.v1");
    timerInstance.setScheduleKind("TIMER");
    timerInstance.setCadenceUnit("MILLISECONDS");
    timerInstance.setCadenceValue(5_000L);
    timerInstance.setPriorityTag("normal");
    timerInstance.setTargetScopeType("ENTITY");
    timerInstance.setTargetScopeId("guard-1");
    timerInstance.setPlayableStateScope("SHARED");
    timerInstance.setWorldSlug("demo");
    timerInstance.setRealmSlug("production");
    timerInstance.setPointerVersion("17");
    timerInstance.setMaterializationStatus("READY");
    timerInstance.setNextDueAt(Instant.ofEpochMilli(5_000L));
    timerInstance.setScheduleMetadataJson("{}");
    timerInstance.setScheduleSemanticsHash("hash-ms");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(timerInstance));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(result.truncatedFiringCount()).isZero();
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).saveAndFlush(workItemCaptor.capture());
    ScriptWorkItem workItem = workItemCaptor.getValue();
    assertThat(workItem.getRegionId()).isEqualTo("region-1");
    assertThat(workItem.getRegionEpoch()).isEqualTo(12L);
    assertThat(workItem.getWorldSlug()).isEqualTo("demo");
    assertThat(workItem.getRealmSlug()).isEqualTo("production");
    assertThat(workItem.getPointerVersion()).isEqualTo("17");
    assertThat(workItem.getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(workItem.getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(workItem.getSourceOrdinal()).isEqualTo(5_000L);
    assertThat(workItem.getSourceDueAtMs()).isEqualTo(5_000L);
    assertThat(workItem.getSourceDueTickId()).isNull();
    assertThat(workItem.getPayloadJson())
        .contains("\"scheduleId\":\"guard.alert.expire.v1\"")
        .contains("\"dueAt\":5000");
    assertThat(workItem.getReadSnapshotToken()).startsWith("automation:onTimerExpire:");
    verify(automationQueueService).enqueueWorkItem(workItem);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> scheduleCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(scheduleCaptor.capture());
    assertThat(scheduleCaptor.getValue())
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getRuntimeRegionId()).isEqualTo("region-1");
              assertThat(instance.getRuntimeRegionEpoch()).isEqualTo(12L);
              assertThat(instance.getLastObservedTickId()).isEqualTo(131L);
              assertThat(instance.getNextDueAt()).isNull();
            });
  }

  @Test
  void observeRuntimeTickProgressSuppressesDueWallClockTimerWhenRuntimeScopeChanges() {
    ScriptScheduleInstance timerInstance = new ScriptScheduleInstance();
    timerInstance.setTenantId("1");
    timerInstance.setGameInstanceId("game-1");
    timerInstance.setScriptPatchVersion("patch-1");
    timerInstance.setScriptId("npc-guard");
    timerInstance.setEventType("onTimerExpire");
    timerInstance.setScheduleDefinitionId("guard.alert.expire.v1");
    timerInstance.setScheduleKind("TIMER");
    timerInstance.setCadenceUnit("MILLISECONDS");
    timerInstance.setCadenceValue(5_000L);
    timerInstance.setPriorityTag("normal");
    timerInstance.setTargetScopeType("ENTITY");
    timerInstance.setTargetScopeId("guard-1");
    timerInstance.setPlayableStateScope("SHARED");
    timerInstance.setWorldSlug("demo");
    timerInstance.setRealmSlug("production");
    timerInstance.setPointerVersion("17");
    timerInstance.setMaterializationStatus("READY");
    timerInstance.setRuntimeRegionId("region-old");
    timerInstance.setRuntimeRegionEpoch(11L);
    timerInstance.setLastObservedTickId(120L);
    timerInstance.setNextDueAt(Instant.ofEpochMilli(5_000L));
    timerInstance.setScheduleMetadataJson("{}");
    timerInstance.setScheduleSemanticsHash("hash-ms");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(timerInstance));
    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-new", 12L, 131L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isZero();
    assertThat(result.truncatedFiringCount()).isZero();
    verify(workItemRepository, never()).saveAndFlush(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalStage()).isEqualTo("ADMISSION");
    assertThat(auditCaptor.getValue().getFinalOutcome()).isEqualTo("canceled");
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("runtime_scope_changed");
    assertThat(auditCaptor.getValue().getRegionId()).isEqualTo("region-old");
    assertThat(auditCaptor.getValue().getRegionEpoch()).isEqualTo(11L);
    assertThat(auditCaptor.getValue().getWorldSlug()).isEqualTo("demo");
    assertThat(auditCaptor.getValue().getRealmSlug()).isEqualTo("production");
    assertThat(auditCaptor.getValue().getPointerVersion()).isEqualTo("17");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> scheduleCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(scheduleCaptor.capture());
    assertThat(scheduleCaptor.getValue())
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getRuntimeRegionId()).isEqualTo("region-new");
              assertThat(instance.getRuntimeRegionEpoch()).isEqualTo(12L);
              assertThat(instance.getNextDueAt()).isNull();
            });
    assertThat(
            meterRegistry
                .get("automation_script_timer_runtime_fence_dropped_total")
                .tag("eventType", "onTimerExpire")
                .tag("reason", "runtime_scope_changed")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void observeRuntimeTickProgressRecordsCatchUpTruncationWithReasonLabels() {
    ScriptSchedulerProperties schedulerProperties = new ScriptSchedulerProperties();
    schedulerProperties.setMaxCatchUpFiringsPerObservation(1);
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
            gameDesignControlPlaneClient,
            schedulerProperties,
            meterRegistry);
    ScriptScheduleInstance first =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 20L, 120L);
    first.setPluginId("plugin-1");
    first.setPluginVersionId("plugin-v1");
    ScriptScheduleInstance second =
        tickSchedule("guard-2", "npc-scout", "guard.scout.v1", 20L, 120L);
    second.setPluginId("plugin-1");
    second.setPluginVersionId("plugin-v1");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(first, second));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());
    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 130L, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(result.truncatedFiringCount()).isEqualTo(1);
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository, org.mockito.Mockito.times(2)).save(auditCaptor.capture());
    assertThat(auditCaptor.getAllValues())
        .anySatisfy(
            audit -> {
              assertThat(audit.getFinalReason()).isEqualTo("catch_up_truncated");
              assertThat(audit.getPluginId()).isEqualTo("plugin-1");
              assertThat(audit.getPluginVersionId()).isEqualTo("plugin-v1");
            });
    assertThat(
            meterRegistry
                .get("automation_script_timer_catchup_truncated_total")
                .tag("eventType", "onInterval")
                .tag("reason", "catch_up_truncated")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void listTimerAuditEventsReturnsBoundedSummariesFromAuditRows() {
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId("1");
    audit.setGameInstanceId("game-1");
    audit.setRegionId("region-1");
    audit.setRegionEpoch(12L);
    audit.setEntityId("guard-1");
    audit.setPlayableStateScope("SHARED");
    audit.setWorldSlug("demo");
    audit.setRealmSlug("production");
    audit.setPointerVersion("17");
    audit.setScriptId("npc-guard");
    audit.setPluginId("plugin-1");
    audit.setPluginVersionId("plugin-v1");
    audit.setEventType("onInterval");
    audit.setScriptPatchVersion("patch-1");
    audit.setScriptEventId("timer-1");
    audit.setTriggerMode("TRIGGER_MODE_CATCH_UP");
    audit.setSourceKind("SCHEDULE_TIMER");
    audit.setSourceState("SCHEDULE_DROPPED");
    audit.setSourceOrdinal(130L);
    audit.setSourceDueTickId(130L);
    audit.setFinalStage("ADMISSION");
    audit.setFinalOutcome("canceled");
    audit.setFinalReason("catch_up_truncated");
    audit.setCreatedAt(Instant.ofEpochMilli(1234L));
    audit.setUpdatedAt(Instant.ofEpochMilli(1235L));
    when(eventAuditRepository.findTimerAuditEvents(
            eq("1"),
            eq("game-1"),
            eq("patch-1"),
            eq("npc-guard"),
            eq("onInterval"),
            eq("catch_up_truncated"),
            any(),
            any(),
            any()))
        .thenReturn(List.of(audit));

    List<ScriptScheduleInstanceService.TimerAuditEventSummary> result =
        service.listTimerAuditEvents(
            "1",
            "game-1",
            "patch-1",
            "npc-guard",
            "onInterval",
            "catch_up_truncated",
            100L,
            200L,
            25);

    assertThat(result)
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.pluginId()).isEqualTo("plugin-1");
              assertThat(summary.pluginVersionId()).isEqualTo("plugin-v1");
              assertThat(summary.finalReason()).isEqualTo("catch_up_truncated");
              assertThat(summary.sourceDueTickId()).isEqualTo(130L);
              assertThat(summary.publication().versionId()).isEqualTo(17L);
            });
  }

  @Test
  void listInstancesAddsScriptPatchPublicationMetadata() {
    ScriptScheduleInstance instance = new ScriptScheduleInstance();
    instance.setTenantId("1");
    instance.setGameInstanceId("game-1");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptId("npc-guard");
    instance.setPlayableStateScope("SHARED");
    instance.setWorldSlug("demo");
    instance.setRealmSlug("production");
    instance.setPointerVersion("17");
    instance.setPluginId("plugin-1");
    instance.setPluginVersionId("plugin-v1");
    instance.setEventType("onTimerExpire");
    instance.setScheduleDefinitionId("guard.alert.expire.v1");
    instance.setScheduleKind("TIMER");
    instance.setCadenceValue(5000L);
    instance.setCadenceUnit("MILLISECONDS");
    instance.setPriorityTag("normal");
    instance.setTargetScopeType("ENTITY");
    instance.setTargetScopeId("guard-1");
    instance.setBindingPriority(10);
    instance.setRequiresExclusiveEvent(false);
    instance.setMaterializationStatus("READY");
    instance.setNextDueAt(Instant.ofEpochMilli(5555L));
    instance.setObservedRuntimeVersionId("runtime-v2");
    instance.setLastObservedControlPlaneRequestId("req-9");
    instance.setPinObservedAt(Instant.ofEpochMilli(1234L));
    instance.setMaterializedAt(Instant.ofEpochMilli(1235L));
    instance.setUpdatedAt(Instant.ofEpochMilli(1236L));
    instance.setRuntimeRegionId("region-1");
    instance.setRuntimeRegionEpoch(12L);
    instance.setLastObservedTickId(100L);
    instance.setLastRuntimeProgressObservedAt(Instant.ofEpochMilli(1237L));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdAndScriptPatchVersionOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1", "patch-1"))
        .thenReturn(List.of(instance));

    List<ScriptScheduleInstanceService.ScheduleInstanceSummary> result =
        service.listInstances("1", "game-1", "patch-1", 25);

    assertThat(result)
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.scheduleDefinitionId()).isEqualTo("guard.alert.expire.v1");
              assertThat(summary.pluginId()).isEqualTo("plugin-1");
              assertThat(summary.pluginVersionId()).isEqualTo("plugin-v1");
              assertThat(summary.publication().versionId()).isEqualTo(17L);
            });
  }

  @Test
  void observeRuntimeTickProgressHandlesMixedTickAndWallClockCandidates() {
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
    tickInstance.setPlayableStateScope("SHARED");
    tickInstance.setWorldSlug("demo");
    tickInstance.setRealmSlug("production");
    tickInstance.setPointerVersion("17");
    tickInstance.setMaterializationStatus("READY");
    tickInstance.setRuntimeRegionId("region-1");
    tickInstance.setRuntimeRegionEpoch(12L);
    tickInstance.setLastObservedTickId(100L);
    tickInstance.setNextDueTickId(130L);
    tickInstance.setScheduleMetadataJson("{}");
    tickInstance.setScheduleSemanticsHash("hash-ticks");

    ScriptScheduleInstance timerInstance = new ScriptScheduleInstance();
    timerInstance.setTenantId("1");
    timerInstance.setGameInstanceId("game-1");
    timerInstance.setScriptPatchVersion("patch-1");
    timerInstance.setScriptId("npc-guard");
    timerInstance.setEventType("onTimerExpire");
    timerInstance.setScheduleDefinitionId("guard.alert.expire.v1");
    timerInstance.setScheduleKind("TIMER");
    timerInstance.setCadenceUnit("MILLISECONDS");
    timerInstance.setCadenceValue(5_000L);
    timerInstance.setPriorityTag("normal");
    timerInstance.setTargetScopeType("ENTITY");
    timerInstance.setTargetScopeId("guard-1");
    timerInstance.setPlayableStateScope("SHARED");
    timerInstance.setWorldSlug("demo");
    timerInstance.setRealmSlug("production");
    timerInstance.setPointerVersion("17");
    timerInstance.setMaterializationStatus("READY");
    timerInstance.setNextDueAt(Instant.ofEpochMilli(5_000L));
    timerInstance.setScheduleMetadataJson("{}");
    timerInstance.setScheduleSemanticsHash("hash-ms");

    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(timerInstance));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(2);
    assertThat(result.firedScheduleCount()).isEqualTo(2);
    assertThat(result.truncatedFiringCount()).isZero();
    verify(workItemRepository, org.mockito.Mockito.times(2))
        .saveAndFlush(org.mockito.Mockito.any());
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

  private static ScriptScheduleInstance tickSchedule(
      String targetScopeId,
      String scriptId,
      String scheduleDefinitionId,
      long cadence,
      long dueTick) {
    ScriptScheduleInstance instance = new ScriptScheduleInstance();
    instance.setTenantId("1");
    instance.setGameInstanceId("game-1");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptId(scriptId);
    instance.setEventType("onInterval");
    instance.setScheduleDefinitionId(scheduleDefinitionId);
    instance.setScheduleKind("INTERVAL");
    instance.setCadenceUnit("TICKS");
    instance.setCadenceValue(cadence);
    instance.setPriorityTag("normal");
    instance.setTargetScopeType("ENTITY");
    instance.setTargetScopeId(targetScopeId);
    instance.setPlayableStateScope("SHARED");
    instance.setWorldSlug("demo");
    instance.setRealmSlug("production");
    instance.setPointerVersion("17");
    instance.setMaterializationStatus("READY");
    instance.setRuntimeRegionId("region-1");
    instance.setRuntimeRegionEpoch(12L);
    instance.setLastObservedTickId(90L);
    instance.setNextDueTickId(dueTick);
    instance.setScheduleMetadataJson("{}");
    instance.setScheduleSemanticsHash("hash-" + scheduleDefinitionId);
    return instance;
  }
}
