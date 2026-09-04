package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
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
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private GameSessionControlPlaneClient gameSessionControlPlaneClient;
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
    gameSessionControlPlaneClient = mock(GameSessionControlPlaneClient.class);
    meterRegistry = new SimpleMeterRegistry();
    when(automationAdmissionStateService.getState(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new AutomationAdmissionStateService.AdmissionStateSummary(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    "NORMAL",
                    4L,
                    "",
                    "",
                    "",
                    0L));
    when(automationAdmissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "NORMAL", 4L, "", "", "", 0L));
    when(workItemRepository.insertIfAbsentByTriggerIdentity(org.mockito.Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptWorkItemRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));
    when(eventAuditRepository.insertIfAbsentByHandlerIdentity(org.mockito.Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptEventAuditRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));
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
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState(any(), any()))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                        .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
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
            gameSessionControlPlaneClient,
            new ScriptSchedulerProperties(),
            meterRegistry);
  }

  @Test
  void reconcileObservedRuntimeStateRejectsZeroTenantIdBeforeScheduleLookup() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.reconcileObservedRuntimeState(
                "0",
                "game-1",
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("0")
                    .setGameInstanceId("game-1")
                    .setPinnedScriptPatchVersion("patch-1")
                    .build()));

    verifyNoInteractions(
        scheduleDefinitionRepository,
        scheduleInstanceRepository,
        pinProjectionRepository,
        pluginRuntimeStateRepository,
        bindingRepository,
        workItemRepository,
        eventAuditRepository,
        automationQueueService,
        automationAdmissionStateService,
        gameDesignControlPlaneClient);
  }

  @Test
  void reconcileObservedRuntimeStateRejectsNullTransitionPluginIdBeforeLookup() {
    assertThatThrownBy(
            () ->
                service.reconcileObservedRuntimeState(
                    "1", "game-1", GameInstanceRuntimeState.getDefaultInstance(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("transition_plugin_id must not be null");

    verifyNoInteractions(
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
        gameSessionControlPlaneClient);
  }

  @Test
  void reconcileObservedRuntimeStateDoesNotMaterializeWithoutScriptPinEpoch() {
    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build());

    verify(scheduleInstanceRepository, never()).saveAll(any());
    verifyNoInteractions(scheduleDefinitionRepository, bindingRepository);
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
            .setScriptPinEpoch(1L)
            .setRuntimeVersionId("runtime-v2")
            .setScriptPatchPinnedControlPlaneRequestId("req-1")
            .setScriptPatchPinnedAtMs(1_000L)
            .setRegionId("region-1")
            .setRegionEpoch(7L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
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
  void usesLoadedPluginRuntimeStateToPopulateLifecycleFence() {
    ScriptScheduleDefinition pluginDefinition = pluginDefinition("plugin-1", "plugin-v1");
    ScriptScheduleInstance existing =
        tickSchedule("", "plugin-town-crier", "town-crier.market.pulse.v1", 12L, 120L);
    existing.setId(81L);
    existing.setPluginId("plugin-1");
    existing.setPluginVersionId("plugin-v1");
    existing.setTargetScopeType("GLOBAL");
    existing.setTargetScopeId("");
    existing.setPluginActivationEpoch(19L);
    existing.setLifecycleRevision(27L);
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(pluginDefinition));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(existing));
    PluginRuntimeState runtimeState = enabledPluginRuntimeState("plugin-1", "plugin-v1");
    runtimeState.setPluginActivationEpoch(37L);
    runtimeState.setLifecycleRevision(43L);
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(runtimeState));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("plugin-town-crier", "onInterval", "GLOBAL", "", 0, false)));

    service.reconcileObservedRuntimeState(
        "1", "game-1", runtimeStateResponse("patch-1").getRuntimeState());

    assertThat(existing.getPluginActivationEpoch()).isEqualTo(37L);
    assertThat(existing.getLifecycleRevision()).isEqualTo(43L);
    verify(scheduleInstanceRepository).saveAll(List.of(existing));
  }

  @Test
  void reconcileObservedRuntimeStateCollapsesPartialRoutingBundle() {
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

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setWorldSlug("demo")
            .build());

    verify(scheduleInstanceRepository, never()).deleteByTenantIdAndGameInstanceId("1", "game-1");
    verify(scheduleInstanceRepository, never()).saveAll(any());
  }

  @ParameterizedTest(name = "ignores runtime authority from {0}/{1}")
  @MethodSource("mismatchedRuntimeScopes")
  void reconcileObservedRuntimeStateIgnoresDifferentRuntimeScope(
      String runtimeTenantId, String runtimeGameInstanceId) {
    ScriptScheduleInstance retained = wallClockTimerInstance();
    retained.setId(71L);
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(retained));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId(runtimeTenantId)
            .setGameInstanceId(runtimeGameInstanceId)
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build());

    verifyNoInteractions(
        scheduleDefinitionRepository,
        pinProjectionRepository,
        pluginRuntimeStateRepository,
        bindingRepository,
        workItemRepository,
        eventAuditRepository,
        automationQueueService,
        gameDesignControlPlaneClient);
    verify(scheduleInstanceRepository, never()).saveAll(any());
    verify(scheduleInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(any(), any());
  }

  private static Stream<Arguments> mismatchedRuntimeScopes() {
    return Stream.of(Arguments.of("2", "game-1"), Arguments.of("1", "game-2"));
  }

  @Test
  void reconcilePinnedPatchInstancesPreservesRowsWhenProjectionScopeIsIncomplete() {
    ScriptPatchPinProjection projection = new ScriptPatchPinProjection();
    projection.setTenantId("1");
    projection.setGameInstanceId("game-1");
    projection.setObservedPinnedScriptPatchVersion("patch-1");
    projection.setObservedAt(Instant.ofEpochMilli(3_000L));
    projection.setRuntimeRegionId("");
    projection.setRuntimeRegionEpoch(0L);
    when(pinProjectionRepository.findByTenantIdAndObservedPinnedScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(projection));

    service.reconcilePinnedPatchInstances("1", "patch-1");

    verify(scheduleInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(any(), any());
    verify(scheduleInstanceRepository, never()).saveAll(any());
  }

  @Test
  void incompleteScopePendsEveryRetainedScheduleRow() {
    ScriptScheduleInstance core = wallClockTimerInstance();
    core.setId(50L);
    ScriptScheduleInstance plugin = wallClockTimerInstance();
    plugin.setId(51L);
    plugin.setPluginId("plugin-1");
    plugin.setPluginVersionId("plugin-v1");
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(core, plugin));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setRegionId("")
            .setRegionEpoch(0L)
            .build());

    verify(scheduleInstanceRepository).saveAll(List.of(core, plugin));
    assertThat(core.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(plugin.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(core.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    assertThat(plugin.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
  }

  @Test
  void completeReconciliationRestoresRetainedTickAndWallDueOccurrences() {
    ScriptScheduleInstance tick =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    tick.setId(60L);
    ScriptScheduleInstance timer = wallClockTimerInstance();
    timer.setId(61L);
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(tick, timer));

    GameInstanceRuntimeState partial =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setRegionId("")
            .setRegionEpoch(0L)
            .build();
    service.reconcileObservedRuntimeState("1", "game-1", partial);

    assertThat(tick.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(tick.getNextDueTickId()).isEqualTo(130L);
    assertThat(timer.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(timer.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));

    ScriptScheduleDefinition retainedTickDefinition = tickDefinition();
    retainedTickDefinition.setPriorityTag("normal");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition(), retainedTickDefinition));
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 0, false),
                binding("npc-guard", "onInterval", "ENTITY", "guard-1", 0, false)));
    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build());

    assertThat(tick.getMaterializationStatus()).isEqualTo("READY");
    assertThat(tick.getNextDueTickId()).isEqualTo(130L);
    assertThat(timer.getMaterializationStatus()).isEqualTo("READY");
    assertThat(timer.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
  }

  @Test
  void incompleteRoutingPreservesRetainedDueEvidenceUntilCompleteReconciliation() {
    ScriptScheduleInstance tick =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    tick.setId(64L);
    tick.setPriorityTag("high");
    ScriptScheduleInstance timer = wallClockTimerInstance();
    timer.setId(65L);
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(tick, timer));

    GameInstanceRuntimeState incompleteRouting =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build();
    service.reconcileObservedRuntimeState("1", "game-1", incompleteRouting);

    assertThat(tick.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(tick.getNextDueTickId()).isEqualTo(130L);
    assertThat(tick.getWorldSlug()).isEqualTo("demo");
    assertThat(tick.getRealmSlug()).isEqualTo("production");
    assertThat(tick.getPointerVersion()).isEqualTo("17");
    assertThat(timer.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(timer.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    assertThat(timer.getWorldSlug()).isEqualTo("demo");
    assertThat(timer.getRealmSlug()).isEqualTo("production");
    assertThat(timer.getPointerVersion()).isEqualTo("17");

    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition(), tickDefinition()));
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 0, false),
                binding("npc-guard", "onInterval", "ENTITY", "guard-1", 0, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        incompleteRouting.toBuilder()
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build());

    assertThat(tick.getMaterializationStatus()).isEqualTo("READY");
    assertThat(tick.getNextDueTickId()).isEqualTo(130L);
    assertThat(timer.getMaterializationStatus()).isEqualTo("READY");
    assertThat(timer.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
  }

  @Test
  void incompleteScopeThenTickObservationFencesOldCoreAndDisabledPluginRows() {
    ScriptScheduleInstance oldCore = wallClockTimerInstance();
    oldCore.setId(52L);
    ScriptScheduleInstance disabledPlugin = wallClockTimerInstance();
    disabledPlugin.setId(53L);
    disabledPlugin.setScriptPatchVersion("patch-2");
    disabledPlugin.setPluginId("plugin-1");
    disabledPlugin.setPluginVersionId("plugin-v1");
    ScriptScheduleInstance replacedPlugin = wallClockTimerInstance();
    replacedPlugin.setId(54L);
    replacedPlugin.setScriptPatchVersion("patch-2");
    replacedPlugin.setPluginId("plugin-2");
    replacedPlugin.setPluginVersionId("plugin-v1");
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(oldCore, disabledPlugin, replacedPlugin));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-2")
            .setRegionId("")
            .setRegionEpoch(0L)
            .build());

    PluginRuntimeState disabledState = enabledPluginRuntimeState("plugin-1", "plugin-v2");
    disabledState.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(disabledState));
    PluginRuntimeState replacedState = enabledPluginRuntimeState("plugin-2", "plugin-v2");
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "game-1", "plugin-2"))
        .thenReturn(Optional.of(replacedState));
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-2")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                        .build())
                .build());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(oldCore, disabledPlugin, replacedPlugin));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
    assertThat(oldCore.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(oldCore.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    assertThat(disabledPlugin.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(disabledPlugin.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    assertThat(replacedPlugin.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(replacedPlugin.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
  }

  @Test
  void incompleteScopeRetainedTickCannotPromoteWithoutReconciliation() {
    ScriptScheduleInstance retained =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(retained));
    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setRegionId("")
            .setRegionEpoch(0L)
            .build());

    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(retained));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    service.observeRuntimeTickProgress(observation(131L, 6_000L));
    service.observeRuntimeTickProgress(observation(161L, 7_000L));

    assertThat(retained.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(retained.getNextDueTickId()).isEqualTo(130L);
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
  }

  @Test
  void reconcilePinnedPatchInstancesReusesObservedPins() {
    ScriptPatchPinProjection projection = new ScriptPatchPinProjection();
    projection.setTenantId("1");
    projection.setGameInstanceId("game-1");
    projection.setObservedPinnedScriptPatchVersion("patch-1");
    projection.setScriptPinEpoch(1L);
    projection.setLastObservedControlPlaneRequestId("req-3");
    projection.setObservedAt(Instant.ofEpochMilli(3_000L));
    projection.setWorldSlug("demo");
    projection.setRealmSlug("production");
    projection.setPointerVersion("17");
    projection.setRuntimeRegionId("region-1");
    projection.setRuntimeRegionEpoch(7L);
    projection.setPlayableStateScope("SHARED");
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
    ScriptScheduleInstance materialized = captor.getValue().getFirst();
    assertThat(materialized.getScriptPinEpoch()).isEqualTo(1L);
    assertThat(materialized.getPinObservedAt()).isEqualTo(Instant.ofEpochMilli(3_000L));
    assertThat(materialized.getWorldSlug()).isEqualTo("demo");
    assertThat(materialized.getRealmSlug()).isEqualTo("production");
    assertThat(materialized.getPointerVersion()).isEqualTo("17");

    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(materialized));
    assertThat(service.observeRuntimeTickProgress(observation(1L, 9_000L)).firedScheduleCount())
        .isEqualTo(1);
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(automationQueueService).enqueueWorkItem(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptPinEpoch()).isEqualTo(1L);
  }

  @Test
  void reconcilePinnedPatchInstancesKeepsSchedulesPendingForAbsentPinEpoch() {
    ScriptPatchPinProjection projection = new ScriptPatchPinProjection();
    projection.setTenantId("1");
    projection.setGameInstanceId("game-1");
    projection.setObservedPinnedScriptPatchVersion("patch-1");
    projection.setRuntimeRegionId("region-1");
    projection.setRuntimeRegionEpoch(7L);
    when(pinProjectionRepository.findByTenantIdAndObservedPinnedScriptPatchVersion("1", "patch-1"))
        .thenReturn(List.of(projection));
    ScriptScheduleInstance retained = wallClockTimerInstance();
    retained.setId(72L);
    retained.setMaterializationStatus("READY");
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(retained));

    service.reconcilePinnedPatchInstances("1", "patch-1");

    assertThat(retained.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    verify(scheduleInstanceRepository).saveAll(List.of(retained));
    verify(scheduleDefinitionRepository, never())
        .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
            any(), any());
  }

  @Test
  void reconcileObservedRuntimeStatePreservesSettledWallClockRowOnSameGenerationRefresh() {
    ScriptScheduleDefinition definition = millisecondsDefinition();
    ScriptScheduleInstance settled = wallClockTimerInstance();
    settled.setId(41L);
    settled.setObservedRuntimeVersionId("runtime-v1");
    settled.setLastObservedControlPlaneRequestId("pin-1");
    settled.setBindingPriority(10);
    settled.setNextDueAt(null);
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(definition));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(settled));
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 10, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRuntimeVersionId("runtime-v1")
            .setScriptPatchPinnedControlPlaneRequestId("pin-1")
            .setScriptPatchPinnedAtMs(99_000L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build());

    verify(scheduleInstanceRepository).saveAll(List.of(settled));
    assertThat(settled.getNextDueAt()).isNull();
  }

  @Test
  void reconcileObservedRuntimeStateResetsWallClockRowForNewPinGeneration() {
    ScriptScheduleDefinition definition = millisecondsDefinition();
    ScriptScheduleInstance settled = wallClockTimerInstance();
    settled.setId(42L);
    settled.setObservedRuntimeVersionId("runtime-v1");
    settled.setLastObservedControlPlaneRequestId("pin-1");
    settled.setNextDueAt(null);
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(definition));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(settled));
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 10, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRuntimeVersionId("runtime-v1")
            .setScriptPatchPinnedControlPlaneRequestId("pin-2")
            .setScriptPatchPinnedAtMs(99_000L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build());

    assertThat(settled.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(104_000L));
  }

  @Test
  void reconcileObservedRuntimeStateResetsWallClockRowForRuntimeScopeChange() {
    ScriptScheduleDefinition definition = millisecondsDefinition();
    ScriptScheduleInstance settled = wallClockTimerInstance();
    settled.setId(42L);
    settled.setRuntimeRegionId("region-old");
    settled.setRuntimeRegionEpoch(11L);
    settled.setObservedRuntimeVersionId("runtime-v1");
    settled.setLastObservedControlPlaneRequestId("pin-1");
    settled.setNextDueAt(Instant.ofEpochMilli(200_000L));
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(definition));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(settled));
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 10, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRuntimeVersionId("runtime-v1")
            .setScriptPatchPinnedControlPlaneRequestId("pin-1")
            .setScriptPatchPinnedAtMs(99_000L)
            .setRegionId("region-new")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build());

    assertThat(settled.getRuntimeRegionId()).isEmpty();
    assertThat(settled.getRuntimeRegionEpoch()).isNull();
    assertThat(settled.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(104_000L));
  }

  @Test
  void pluginTransitionSeedCreatesWallClockDueAtFromCommittedTransitionTime() {
    ScriptScheduleDefinition plugin = pluginDefinition("town-crier", "town-crier-v3");
    plugin.setEventType("onTimerExpire");
    plugin.setScheduleKind("TIMER");
    plugin.setCadenceUnit("MILLISECONDS");
    plugin.setCadenceValue(5_000L);
    plugin.setScheduleSemanticsHash("hash-plugin-timer");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(plugin));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId("1");
    state.setGameInstanceId("game-1");
    state.setPluginId("town-crier");
    state.setActivePluginVersionId("town-crier-v3");
    state.setRuntimeRegionId("region-1");
    state.setRuntimeRegionEpoch(12L);
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(state));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("plugin-town-crier", "onTimerExpire", "GLOBAL", "", 1, false)));
    Instant transitionSeed = Instant.ofEpochMilli(10_000L);

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setScriptPatchPinnedAtMs(1_000L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build(),
        transitionSeed,
        "town-crier");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .singleElement()
        .satisfies(
            instance ->
                assertThat(instance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(15_000L)));
  }

  @Test
  void pluginTransitionResetsOnlyTheTransitioningPluginTickSchedule() {
    ScriptScheduleDefinition core = tickDefinition();
    ScriptScheduleDefinition pluginOne = pluginDefinition("plugin-one", "plugin-one-v1");
    ScriptScheduleDefinition pluginTwo = pluginDefinition("plugin-two", "plugin-two-v1");
    ScriptScheduleInstance coreInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    coreInstance.setId(61L);
    coreInstance.setPriorityTag("high");
    coreInstance.setBindingPriority(10);
    ScriptScheduleInstance pluginOneInstance =
        tickSchedule("", "plugin-town-crier", "town-crier.market.pulse.v1", 12L, 112L);
    pluginOneInstance.setId(62L);
    pluginOneInstance.setPluginId("plugin-one");
    pluginOneInstance.setPluginVersionId("plugin-one-v1");
    pluginOneInstance.setTargetScopeType("GLOBAL");
    pluginOneInstance.setTargetScopeId("");
    pluginOneInstance.setBindingPriority(5);
    ScriptScheduleInstance pluginTwoInstance =
        tickSchedule("", "plugin-town-crier", "town-crier.market.pulse.v1", 12L, 124L);
    pluginTwoInstance.setId(63L);
    pluginTwoInstance.setPluginId("plugin-two");
    pluginTwoInstance.setPluginVersionId("plugin-two-v1");
    pluginTwoInstance.setTargetScopeType("GLOBAL");
    pluginTwoInstance.setTargetScopeId("");
    pluginTwoInstance.setBindingPriority(5);
    for (ScriptScheduleInstance instance :
        List.of(coreInstance, pluginOneInstance, pluginTwoInstance)) {
      instance.setObservedRuntimeVersionId("runtime-v1");
      instance.setLastObservedControlPlaneRequestId("pin-1");
      instance.setLastObservedTickId(100L);
      instance.setLastRuntimeProgressObservedAt(Instant.ofEpochMilli(1_000L));
    }
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(core, pluginOne, pluginTwo));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(coreInstance, pluginOneInstance, pluginTwoInstance));
    PluginRuntimeState pluginOneState = enabledPluginRuntimeState("plugin-one", "plugin-one-v1");
    PluginRuntimeState pluginTwoState = enabledPluginRuntimeState("plugin-two", "plugin-two-v1");
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(pluginOneState, pluginTwoState));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onInterval", "ENTITY", "guard-1", 10, false),
                binding("plugin-town-crier", "onInterval", "GLOBAL", "", 5, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRuntimeVersionId("runtime-v1")
            .setScriptPatchPinnedControlPlaneRequestId("pin-1")
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build(),
        Instant.ofEpochMilli(10_000L),
        "plugin-one");

    verify(scheduleInstanceRepository).saveAll(any());
    assertThat(coreInstance.getMaterializationStatus()).isEqualTo("READY");
    assertThat(coreInstance.getNextDueTickId()).isEqualTo(130L);
    assertThat(coreInstance.getLastObservedTickId()).isEqualTo(100L);
    assertThat(pluginTwoInstance.getMaterializationStatus()).isEqualTo("READY");
    assertThat(pluginTwoInstance.getNextDueTickId()).isEqualTo(124L);
    assertThat(pluginTwoInstance.getLastObservedTickId()).isEqualTo(100L);
    assertThat(pluginOneInstance.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(pluginOneInstance.getNextDueTickId()).isNull();
    assertThat(pluginOneInstance.getRuntimeRegionId()).isEmpty();
    assertThat(pluginOneInstance.getRuntimeRegionEpoch()).isNull();
    assertThat(pluginOneInstance.getLastObservedTickId()).isNull();
    verify(pluginRuntimeStateRepository).findByTenantIdAndGameInstanceId("1", "game-1");
    verify(pluginRuntimeStateRepository, never())
        .findByTenantIdAndGameInstanceIdAndPluginId(any(), any(), any());
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
    runtimeState.setRuntimeRegionId("region-1");
    runtimeState.setRuntimeRegionEpoch(7L);
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
            .setScriptPinEpoch(1L)
            .setRegionId("region-1")
            .setRegionEpoch(7L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
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
  void reconcileObservedRuntimeStateDoesNotTreatPartialPluginIdentityAsCore() {
    ScriptScheduleDefinition malformed = pluginDefinition("", "town-crier-v3");
    ScriptScheduleDefinition alsoMalformed = pluginDefinition("town-crier", "");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(malformed, alsoMalformed));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(enabledPluginRuntimeState("town-crier", "town-crier-v3")));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(binding("plugin-town-crier", "onInterval", "GLOBAL", "", 5, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .build());

    verify(scheduleInstanceRepository, never()).saveAll(any());
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
            .setScriptPinEpoch(1L)
            .setRegionId("region-live")
            .setRegionEpoch(7L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
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
            .setScriptPinEpoch(1L)
            .setRegionId("region-1")
            .setRegionEpoch(7L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
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
    tickInstance.setScriptPinEpoch(1L);
    tickInstance.setPluginActivationEpoch(1L);
    tickInstance.setLifecycleRevision(1L);
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
    verify(gameSessionControlPlaneClient, never()).getGameInstanceRuntimeState(any(), any());
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
  void observeRuntimeTickProgressLeavesDueStateUntouchedWhileRollbackPaused() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 101L);
    tickInstance.setLastObservedTickId(100L);
    when(automationAdmissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "PAUSED_FOR_ROLLBACK", 5L, "", "", "", 0L));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 5_000L));

    assertThat(result)
        .isEqualTo(new ScriptScheduleInstanceService.RuntimeTickProgressResult(0, 0, 0));
    assertThat(tickInstance.getLastObservedTickId()).isEqualTo(100L);
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(101L);
    verify(scheduleInstanceRepository, never()).saveAll(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verifyNoInteractions(automationQueueService, eventAuditRepository);
  }

  @Test
  void observeRuntimeTickProgressRestoresDueStateWhenAdmissionEvidenceIsUnavailable() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 101L);
    tickInstance.setLastObservedTickId(100L);
    when(automationAdmissionStateService.getState("1", "game-1", "region-1")).thenReturn(null);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 5_000L));

    assertThat(result.updatedScheduleCount()).isZero();
    assertThat(result.firedScheduleCount()).isZero();
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(101L);
    assertThat(tickInstance.getLastObservedTickId()).isEqualTo(100L);
    verify(scheduleInstanceRepository, never()).saveAll(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verifyNoInteractions(automationQueueService, eventAuditRepository);
  }

  @Test
  void observeRuntimeTickProgressLeavesDueStateUntouchedForCorruptAdmissionMode() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 101L);
    tickInstance.setLastObservedTickId(100L);
    when(automationAdmissionStateService.getState("1", "game-1", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "game-1", "region-1", "CORRUPT", 5L, "", "", "", 0L));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 5_000L));

    assertThat(result)
        .isEqualTo(new ScriptScheduleInstanceService.RuntimeTickProgressResult(0, 0, 0));
    assertThat(tickInstance.getLastObservedTickId()).isEqualTo(100L);
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(101L);
    verify(scheduleInstanceRepository, never()).saveAll(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verifyNoInteractions(automationQueueService, eventAuditRepository);
  }

  @Test
  void observeRuntimeTickProgressSkipsOnlyInstancesThatAreNewerThanObservation() {
    ScriptScheduleInstance stale = tickSchedule("stale", "npc-guard", "guard.stale.v1", 30L, 230L);
    stale.setLastObservedTickId(250L);
    ScriptScheduleInstance eligible =
        tickSchedule("eligible", "npc-scout", "guard.eligible.v1", 30L, 130L);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(stale, eligible));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 5_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(stale.getLastObservedTickId()).isEqualTo(250L);
    assertThat(stale.getNextDueTickId()).isEqualTo(230L);
    assertThat(eligible.getLastObservedTickId()).isEqualTo(131L);
    verify(scheduleInstanceRepository).saveAll(List.of(eligible));
  }

  @Test
  void observeRuntimeTickProgressRejectsOlderTickForCurrentRuntimeScope() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 230L);
    tickInstance.setLastObservedTickId(200L);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.observeRuntimeTickProgress(
                new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                    "1", "game-1", "region-1", 12L, 199L, 6_000L)));

    verify(scheduleInstanceRepository, never()).saveAll(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verifyNoInteractions(automationQueueService, eventAuditRepository);
  }

  @Test
  void observeRuntimeTickProgressRejectsOlderRuntimeEpoch() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 230L);
    tickInstance.setRuntimeRegionEpoch(12L);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.observeRuntimeTickProgress(
                new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                    "1", "game-1", "region-1", 11L, 250L, 6_000L)));

    verify(scheduleInstanceRepository, never()).saveAll(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verifyNoInteractions(automationQueueService, eventAuditRepository);
  }

  @Test
  void observeRuntimeTickProgressAllowsLowerEpochWhenRegionChanges() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 230L);
    tickInstance.setRuntimeRegionId("region-old");
    tickInstance.setRuntimeRegionEpoch(12L);
    tickInstance.setLastObservedTickId(200L);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-new", 1L, 10L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    verify(scheduleInstanceRepository).saveAll(List.of(tickInstance));
    assertThat(tickInstance.getRuntimeRegionId()).isEqualTo("region-new");
    assertThat(tickInstance.getRuntimeRegionEpoch()).isEqualTo(1L);
    assertThat(tickInstance.getLastObservedTickId()).isEqualTo(10L);
  }

  @Test
  void observeRuntimeTickProgressResetsFutureDueTickWhenRuntimeScopeChanges() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 230L);
    tickInstance.setRuntimeRegionId("region-old");
    tickInstance.setRuntimeRegionEpoch(12L);
    tickInstance.setLastObservedTickId(200L);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tickInstance));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-new", 1L, 210L, 6_000L));

    assertThat(result.updatedScheduleCount()).isEqualTo(1);
    assertThat(result.firedScheduleCount()).isZero();
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    assertThat(tickInstance.getRuntimeRegionId()).isEqualTo("region-new");
    assertThat(tickInstance.getRuntimeRegionEpoch()).isEqualTo(1L);
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(240L);
  }

  @Test
  void observeRuntimeTickProgressFencesDueTickOverflowAndContinuesWithLaterInstances() {
    ScriptScheduleInstance nullDue =
        tickSchedule("guard-1", "npc-guard", "guard.null-due.v1", 1L, 0L);
    nullDue.setNextDueTickId(null);
    nullDue.setLastObservedTickId(null);
    nullDue.setRuntimeRegionId("");
    nullDue.setRuntimeRegionEpoch(null);
    nullDue.setMaterializationStatus("PENDING_RUNTIME_PROGRESS");
    ScriptScheduleInstance later = wallClockTimerInstance();
    later.setScheduleDefinitionId("guard.later.expire.v1");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(nullDue));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(later));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, Long.MAX_VALUE, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(nullDue.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(later.getNextDueAt()).isNull();
    verify(scheduleInstanceRepository).saveAll(any());
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("schedule_due_tick_overflow");
    assertThat(auditCaptor.getValue().getRegionId()).isEqualTo("region-1");
    assertThat(auditCaptor.getValue().getRegionEpoch()).isEqualTo(12L);
    assertThat(meterRegistry.find("automation_script_timer_runtime_fence_dropped_total").counter())
        .isNull();
  }

  @Test
  void observeRuntimeTickProgressFencesAdjacentDueTickOverflowAndContinuesWithLaterInstances() {
    ScriptScheduleInstance adjacentOverflow =
        tickSchedule("guard-2", "npc-scout", "guard.adjacent.v1", 2L, Long.MAX_VALUE - 1L);
    ScriptScheduleInstance later = wallClockTimerInstance();
    later.setScheduleDefinitionId("guard.later.expire.v1");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(adjacentOverflow));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(later));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, Long.MAX_VALUE, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(adjacentOverflow.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(later.getNextDueAt()).isNull();
    verify(scheduleInstanceRepository).saveAll(any());
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("schedule_due_tick_overflow");
    assertThat(auditCaptor.getValue().getRegionId()).isEqualTo("region-1");
    assertThat(auditCaptor.getValue().getRegionEpoch()).isEqualTo(12L);
    assertThat(meterRegistry.find("automation_script_timer_runtime_fence_dropped_total").counter())
        .isNull();
  }

  @Test
  void reconcileObservedRuntimeStateFencesDueTimeOverflowAndContinuesWithLaterSchedules() {
    ScriptScheduleDefinition overflow = pluginDefinition("plugin-1", "plugin-v1");
    overflow.setEventType("onTimerExpire");
    overflow.setScheduleKind("TIMER");
    overflow.setCadenceUnit("MILLISECONDS");
    overflow.setCadenceValue(Long.MAX_VALUE);
    ScriptScheduleDefinition later = millisecondsDefinition();
    later.setScheduleDefinitionId("guard.later.expire.v1");
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(overflow, later));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(enabledPluginRuntimeState("plugin-1", "plugin-v1")));
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("plugin-town-crier", "onTimerExpire", "GLOBAL", "", 1, false),
                binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 2, false)));

    service.reconcileObservedRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setScriptPatchPinnedAtMs(1_000L)
            .setRegionId("region-1")
            .setRegionEpoch(12L)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
            .build(),
        Instant.MAX,
        "plugin-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> captor = ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(captor.capture());
    assertThat(captor.getValue())
        .filteredOn(
            instance ->
                instance.getScheduleDefinitionId().equals(overflow.getScheduleDefinitionId()))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("FENCED");
              assertThat(instance.getNextDueAt()).isNull();
            });
    assertThat(captor.getValue())
        .filteredOn(
            instance -> instance.getScheduleDefinitionId().equals(later.getScheduleDefinitionId()))
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.getMaterializationStatus()).isEqualTo("READY");
              assertThat(instance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(6_000L));
            });
  }

  @Test
  void observeRuntimeTickProgressEmitsDueTimerWorkItemAndAdvancesPastObservedTick() {
    ScriptScheduleInstance tickInstance = new ScriptScheduleInstance();
    tickInstance.setTenantId("1");
    tickInstance.setGameInstanceId("game-1");
    tickInstance.setScriptPatchVersion("patch-1");
    tickInstance.setScriptPinEpoch(1L);
    tickInstance.setPluginActivationEpoch(1L);
    tickInstance.setLifecycleRevision(1L);
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
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(workItemCaptor.capture());
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
    assertThat(workItem.getEventSchemaVersion()).isEqualTo("v1");
    assertThat(workItem.getTriggerMode()).isEqualTo("TRIGGER_MODE_CATCH_UP");
    // Golden identity: SHA-256 (first 60 hex chars) of length-prefixed UTF-8 values in
    // TimerFiringCandidate.identity(): tenant, instance, playable scope, region/epoch, target
    // scope/entity, script/plugin identity, event/schema, patch, schedule, dueTickId, dry-run,
    // and trigger mode. Changing any value, order, or framing changes persisted scriptEventId
    // dedupe keys, so a migration must backfill existing scheduler work/audit identities together.
    assertThat(workItem.getScriptEventId())
        .isEqualTo("timer-8c9c4db6946a4005b5986cca797153b089b270e022eb41ac9d2adf5dd713");
    assertThat(workItem.getQuotaClass()).isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
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
  void observeRuntimeTickProgressFencesTimerScopeMismatchWithoutReusingIdentity() {
    ScriptScheduleInstance shared =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    shared.setPlayableStateScope("SHARED");
    ScriptScheduleInstance isolated =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    isolated.setPlayableStateScope("ISOLATED");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(shared, isolated));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    service.observeRuntimeTickProgress(
        new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
            "1", "game-1", "region-1", 12L, 131L, 6_000L));

    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, org.mockito.Mockito.times(1))
        .insertIfAbsentByTriggerIdentity(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getScriptEventId()).matches("timer-[0-9a-f]{60}");
    assertThat(workItemCaptor.getValue().getPlayableStateScope()).isEqualTo("SHARED");
    assertThat(workItemCaptor.getValue().getRegionId()).isEqualTo("region-1");
    assertThat(workItemCaptor.getValue().getGameInstanceId()).isEqualTo("game-1");
    assertThat(isolated.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(isolated.getNextDueTickId()).isNull();
    assertThat(workItemCaptor.getValue().getScriptId()).isEqualTo(shared.getScriptId());
    assertThat(workItemCaptor.getValue().getEntityId()).isEqualTo("guard-1");
  }

  @Test
  void observeRuntimeTickProgressSeparatesTimerIdentityWhenFieldsContainDelimiters() {
    ScriptScheduleInstance first =
        tickSchedule("guard-1", "npc|guard", "guard.patrol.v1", 30L, 130L);
    first.setEventType("onInterval");
    ScriptScheduleInstance second = tickSchedule("guard-1", "npc", "guard.patrol.v1", 30L, 130L);
    second.setEventType("guard|onInterval");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(first, second));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    service.observeRuntimeTickProgress(
        new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
            "1", "game-1", "region-1", 12L, 131L, 6_000L));

    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, org.mockito.Mockito.times(2))
        .insertIfAbsentByTriggerIdentity(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues())
        .extracting(ScriptWorkItem::getScriptEventId)
        .doesNotHaveDuplicates()
        .allMatch(scriptEventId -> scriptEventId.matches("timer-[0-9a-f]{60}"));
  }

  @Test
  void observeRuntimeTickProgressCollapsesPartialScheduleRoutingBundleBeforePersistingWorkItem() {
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
    GameInstanceRuntimeState changedRoutingState =
        runtimeStateResponse("patch-1").getRuntimeState().toBuilder()
            .clearCurrentAdmissionPointers()
            .addCurrentAdmissionPointers(currentPointer("demo", "production", 18L))
            .build();
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(changedRoutingState)
                .build());

    service.observeRuntimeTickProgress(
        new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
            "1", "game-1", "region-1", 12L, 131L, 6_000L));

    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("routing_bundle_changed");
    assertThat(meterRegistry.find("automation_script_timer_runtime_fence_dropped_total").counter())
        .isNull();
  }

  @Test
  void observeRuntimeTickProgressLeavesDueTimerWhenPointerIdentityContradictsRuntimeRoot() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    GameInstanceRuntimeState contradictoryRuntimeState =
        runtimeStateResponse("patch-1").getRuntimeState().toBuilder()
            .clearCurrentAdmissionPointers()
            .addCurrentAdmissionPointers(
                AdmissionPointerControlPlaneEntry.newBuilder()
                    .setWorldSlug("demo")
                    .setRealmSlug("production")
                    .setTenantId("2")
                    .setGameInstanceId("game-2")
                    .setPointerVersion(17L)
                    .setStateScope("SHARED")
                    .build())
            .build();
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(contradictoryRuntimeState)
                .build());
    Instant dueAt = timerInstance.getNextDueAt();

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    assertThat(timerInstance.getNextDueAt()).isEqualTo(dueAt);
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
  }

  @Test
  void observeRuntimeTickProgressStampsAndFiresDueWallClockTimer() {
    ScriptScheduleInstance timerInstance = new ScriptScheduleInstance();
    timerInstance.setTenantId("1");
    timerInstance.setGameInstanceId("game-1");
    timerInstance.setScriptPatchVersion("patch-1");
    timerInstance.setScriptPinEpoch(1L);
    timerInstance.setPluginActivationEpoch(1L);
    timerInstance.setLifecycleRevision(1L);
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

    TransactionSynchronizationManager.initSynchronization();
    ScriptScheduleInstanceService.RuntimeTickProgressResult result;
    try {
      result =
          service.observeRuntimeTickProgress(
              new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                  "1", "game-1", "region-1", 12L, 131L, 6_000L));

      assertThat(result.updatedScheduleCount()).isEqualTo(1);
      assertThat(result.firedScheduleCount()).isEqualTo(1);
      assertThat(result.truncatedFiringCount()).isZero();
      ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
      verify(workItemRepository).insertIfAbsentByTriggerIdentity(workItemCaptor.capture());
      ScriptWorkItem workItem = workItemCaptor.getValue();
      assertThat(workItem.getRegionId()).isEqualTo("region-1");
      assertThat(workItem.getRegionEpoch()).isEqualTo(12L);
      assertThat(workItem.getWorldSlug()).isEqualTo("demo");
      assertThat(workItem.getRealmSlug()).isEqualTo("production");
      assertThat(workItem.getPointerVersion()).isEqualTo("17");
      assertThat(workItem.getQuotaClass()).isEqualTo(ScriptQuotaClasses.STANDARD_RUNTIME);
      assertThat(workItem.getPriorityTag()).isEqualTo("normal");
      assertThat(workItem.getSourceKind()).isEqualTo("SCHEDULE_TIMER");
      assertThat(workItem.getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
      assertThat(workItem.getSourceOrdinal()).isEqualTo(5_000L);
      assertThat(workItem.getSourceDueAtMs()).isEqualTo(5_000L);
      assertThat(workItem.getSourceDueTickId()).isNull();
      assertThat(workItem.getPayloadJson())
          .contains("\"scheduleId\":\"guard.alert.expire.v1\"")
          .contains("\"dueAt\":5000");
      assertThat(workItem.getReadSnapshotToken()).startsWith("automation:onTimerExpire:");
      verify(eventAuditRepository).save(any());
      verify(automationQueueService, never()).enqueueWorkItem(any());
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
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
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void observeRuntimeTickProgressDoesNotFenceCaseOnlyRoutingSlugChanges() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            runtimeStateResponse("patch-1").toBuilder()
                .setRuntimeState(
                    runtimeStateResponse("patch-1").getRuntimeState().toBuilder()
                        .clearCurrentAdmissionPointers()
                        .addCurrentAdmissionPointers(currentPointer("DEMO", "PRODUCTION", 17L))
                        .build())
                .build());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(timerInstance.getNextDueAt()).isNull();
    verify(eventAuditRepository).save(any());
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
  }

  @Test
  void transientGameSessionAuthorityRetainsWallClockDuePointForRetry() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    GetGameInstanceRuntimeStateResponse available = runtimeStateResponse("patch-1");
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenThrow(new IllegalStateException("transport unavailable"))
        .thenReturn(available, available);

    ScriptScheduleInstanceService.RuntimeTickProgressResult first =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(first.firedScheduleCount()).isZero();
    assertThat(timerInstance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());

    ScriptScheduleInstanceService.RuntimeTickProgressResult retry =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(retry.firedScheduleCount()).isEqualTo(1);
    assertThat(timerInstance.getNextDueAt()).isNull();
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService).enqueueWorkItem(any());
  }

  @Test
  void transientGameSessionAuthorityDoesNotTruncateUnselectedDueCandidates() {
    List<ScriptScheduleInstance> instances = configureTruncatedCandidates();
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenThrow(new IllegalStateException("transport unavailable"));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(130L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    assertThat(result.truncatedFiringCount()).isZero();
    assertThat(instances).extracting(ScriptScheduleInstance::getNextDueTickId).containsOnly(120L);
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
  }

  @Test
  void transientGameSessionAuthorityRetainsTickDuePointForRetry() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    stubScheduleObservation(tickInstance);
    GetGameInstanceRuntimeStateResponse unavailable =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("GAME_SESSION_UNAVAILABLE")
                    .setMessage("unavailable"))
            .build();
    GetGameInstanceRuntimeStateResponse available = runtimeStateResponse("patch-1");
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(unavailable, available, available);

    ScriptScheduleInstanceService.RuntimeTickProgressResult first =
        service.observeRuntimeTickProgress(observation(130L, 6_000L));

    assertThat(first.firedScheduleCount()).isZero();
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(130L);
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());

    ScriptScheduleInstanceService.RuntimeTickProgressResult retry =
        service.observeRuntimeTickProgress(observation(130L, 6_000L));

    assertThat(retry.firedScheduleCount()).isEqualTo(1);
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(160L);
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService).enqueueWorkItem(any());
  }

  @Test
  void blankRuntimeErrorCodeDoesNotHidePresentRuntimeState() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    stubScheduleObservation(tickInstance);
    GetGameInstanceRuntimeStateResponse response =
        runtimeStateResponse("patch-1").toBuilder()
            .setError(ErrorDetail.newBuilder().build())
            .build();
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(response);

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(130L, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(160L);
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService).enqueueWorkItem(any());
  }

  @Test
  void transientPluginAuthorityRetainsWallClockDuePointForRetry() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    timerInstance.setPluginId("plugin-1");
    timerInstance.setPluginVersionId("plugin-v1");
    stubScheduleObservation(timerInstance);
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "game-1", "plugin-1"))
        .thenThrow(new IllegalStateException("plugin authority unavailable"))
        .thenReturn(Optional.of(enabledPluginRuntimeState("plugin-1", "plugin-v1")));

    ScriptScheduleInstanceService.RuntimeTickProgressResult first =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(first.firedScheduleCount()).isZero();
    assertThat(timerInstance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());

    ScriptScheduleInstanceService.RuntimeTickProgressResult retry =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(retry.firedScheduleCount()).isEqualTo(1);
    assertThat(timerInstance.getNextDueAt()).isNull();
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService).enqueueWorkItem(any());
  }

  @Test
  void explicitPatchMismatchPersistsBoundedSkipAuditBeforeFencing() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(runtimeStateResponse("patch-2"));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    assertThat(timerInstance.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(timerInstance.getNextDueAt()).isNull();
    assertThat(timerInstance.getRuntimeRegionId()).isEmpty();
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalStage()).isEqualTo("ADMISSION");
    assertThat(auditCaptor.getValue().getFinalOutcome()).isEqualTo("canceled");
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("script_patch_mismatch");
    assertThat(meterRegistry.find("automation_script_timer_runtime_fence_dropped_total").counter())
        .isNull();
  }

  @Test
  void permanentDisplacementFencesTickAndWallWithoutReseedingUntilReconciliation() {
    ScriptScheduleInstance tick =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    ScriptScheduleInstance timer = wallClockTimerInstance();
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(tick));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(timer));
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(runtimeStateResponse("patch-2"));

    ScriptScheduleInstanceService.RuntimeTickProgressResult first =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(first.firedScheduleCount()).isZero();
    assertThat(tick.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(tick.getNextDueTickId()).isNull();
    assertThat(timer.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(timer.getNextDueAt()).isNull();
    verify(eventAuditRepository, org.mockito.Mockito.times(2))
        .insertIfAbsentByHandlerIdentity(any());

    ScriptScheduleInstanceService.RuntimeTickProgressResult repeated =
        service.observeRuntimeTickProgress(observation(161L, 7_000L));

    assertThat(repeated)
        .isEqualTo(new ScriptScheduleInstanceService.RuntimeTickProgressResult(0, 0, 0));
    assertThat(tick.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(timer.getMaterializationStatus()).isEqualTo("FENCED");
    verify(eventAuditRepository, org.mockito.Mockito.times(2))
        .insertIfAbsentByHandlerIdentity(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());

    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1"))
        .thenReturn(List.of(tick, timer));
    when(scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                1L, "patch-1"))
        .thenReturn(List.of(millisecondsDefinition(), tickDefinition()));
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of());
    when(bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-1"))
        .thenReturn(
            List.of(
                binding("npc-guard", "onTimerExpire", "ENTITY", "guard-1", 0, false),
                binding("npc-guard", "onInterval", "ENTITY", "guard-1", 0, false)));
    service.reconcileObservedRuntimeState(
        "1", "game-1", runtimeStateResponse("patch-1").getRuntimeState());

    assertThat(tick.getMaterializationStatus()).isEqualTo("PENDING_RUNTIME_PROGRESS");
    assertThat(tick.getNextDueTickId()).isNull();
    assertThat(timer.getMaterializationStatus()).isEqualTo("READY");
    assertThat(timer.getNextDueAt()).isNotNull();
  }

  @Test
  void explicitPlayableScopeMismatchPersistsBoundedSkipAuditBeforeFencing() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            runtimeStateResponse("").toBuilder()
                .setRuntimeState(
                    runtimeStateResponse("").getRuntimeState().toBuilder()
                        .setPinnedScriptPatchVersion("patch-1")
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                        .clearCurrentAdmissionPointers()
                        .addCurrentAdmissionPointers(
                            currentPointer("demo", "production", 17L, "ISOLATED"))
                        .build())
                .build());

    service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(timerInstance.getNextDueAt()).isNull();
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("playable_state_scope_changed");
    assertThat(
            meterRegistry
                .get("automation_script_timer_runtime_fence_dropped_total")
                .tag("service", "automation-scripting-service")
                .tag("scope", "game_instance")
                .tag("script_kind", "SCRIPT")
                .tag("event_class", "timer_expire")
                .tag("reason", "playable_state_scope_changed")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void unspecifiedPlayableScopeRetainsDuePointWithoutSkipAudit() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(1L)
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
                        .build())
                .build());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    assertThat(timerInstance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
  }

  @Test
  void persistedUnspecifiedPlayableScopeIsProvenScopeChange() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    timerInstance.setPlayableStateScope("");
    stubScheduleObservation(timerInstance);

    service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(timerInstance.getMaterializationStatus()).isEqualTo("FENCED");
    assertThat(timerInstance.getNextDueAt()).isNull();
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getFinalReason()).isEqualTo("playable_state_scope_changed");
    assertThat(
            meterRegistry
                .get("automation_script_timer_runtime_fence_dropped_total")
                .tag("service", "automation-scripting-service")
                .tag("scope", "game_instance")
                .tag("script_kind", "SCRIPT")
                .tag("event_class", "timer_expire")
                .tag("reason", "playable_state_scope_changed")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void partialRoutingBundleRetainsDuePointWithoutSkipAudit() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    stubScheduleObservation(timerInstance);
    GameInstanceRuntimeState partialRouting =
        runtimeStateResponse("patch-1").getRuntimeState().toBuilder()
            .clearCurrentAdmissionPointers()
            .build();
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(partialRouting)
                .build());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(observation(131L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    assertThat(timerInstance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
  }

  @Test
  void transientPluginAuthorityRetainsTickDuePointForRetry() {
    ScriptScheduleInstance tickInstance =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 130L);
    tickInstance.setPluginId("plugin-1");
    tickInstance.setPluginVersionId("plugin-v1");
    stubScheduleObservation(tickInstance);
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "game-1", "plugin-1"))
        .thenThrow(new IllegalStateException("plugin authority unavailable"))
        .thenReturn(Optional.of(enabledPluginRuntimeState("plugin-1", "plugin-v1")));

    ScriptScheduleInstanceService.RuntimeTickProgressResult first =
        service.observeRuntimeTickProgress(observation(130L, 6_000L));

    assertThat(first.firedScheduleCount()).isZero();
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(130L);
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());

    ScriptScheduleInstanceService.RuntimeTickProgressResult retry =
        service.observeRuntimeTickProgress(observation(130L, 6_000L));

    assertThat(retry.firedScheduleCount()).isEqualTo(1);
    assertThat(tickInstance.getNextDueTickId()).isEqualTo(160L);
    verify(workItemRepository).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService).enqueueWorkItem(any());
  }

  @Test
  void observeRuntimeTickProgressSettlesWallClockDuePointWhenWorkAlreadyExists() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    timerInstance.setLastObservedTickId(131L);
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of(timerInstance));
    ScriptWorkItem existing = new ScriptWorkItem();
    existing.setId(44L);
    when(workItemRepository.insertIfAbsentByTriggerIdentity(any()))
        .thenReturn(new ScriptWorkItemRepository.IdempotentInsertResult(existing, false));

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 131L, 6_000L));

    assertThat(result.firedScheduleCount()).isZero();
    verify(automationQueueService, never()).enqueueWorkItem(any());
    assertThat(meterRegistry.find("automation_script_timer_fired_total").counter()).isNull();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ScriptScheduleInstance>> scheduleCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(scheduleInstanceRepository).saveAll(scheduleCaptor.capture());
    assertThat(scheduleCaptor.getValue())
        .singleElement()
        .satisfies(instance -> assertThat(instance.getNextDueAt()).isNull());
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
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
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
              assertThat(instance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(11_000L));
            });
    assertThat(
            meterRegistry
                .get("automation_script_timer_runtime_fence_dropped_total")
                .tag("service", "automation-scripting-service")
                .tag("scope", "game_instance")
                .tag("script_kind", "SCRIPT")
                .tag("event_class", "timer_expire")
                .tag("reason", "runtime_scope_changed")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("automation_script_timer_runtime_fence_dropped_total")
                .tag("service", "automation-scripting-service")
                .tag("scope", "game_instance")
                .tag("script_kind", "SCRIPT")
                .tag("event_class", "timer_expire")
                .tag("reason", "runtime_scope_changed")
                .counter()
                .getId()
                .getTags())
        .extracting(Tag::getKey)
        .containsExactlyInAnyOrder("service", "scope", "script_kind", "event_class", "reason");
  }

  @Test
  void observeRuntimeTickProgressFencesFutureWallClockDuePointWhenRuntimeScopeChanges() {
    ScriptScheduleInstance timerInstance = wallClockTimerInstance();
    timerInstance.setRuntimeRegionId("region-old");
    timerInstance.setRuntimeRegionEpoch(11L);
    timerInstance.setLastObservedTickId(120L);
    timerInstance.setNextDueAt(Instant.ofEpochMilli(10_000L));
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
    verify(workItemRepository, never()).insertIfAbsentByTriggerIdentity(any());
    verify(eventAuditRepository, never()).insertIfAbsentByHandlerIdentity(any());
    verify(automationQueueService, never()).enqueueWorkItem(any());
    assertThat(timerInstance.getRuntimeRegionId()).isEqualTo("region-new");
    assertThat(timerInstance.getRuntimeRegionEpoch()).isEqualTo(12L);
    assertThat(timerInstance.getNextDueAt()).isEqualTo(Instant.ofEpochMilli(11_000L));
    assertThat(timerInstance.getMaterializationStatus()).isEqualTo("READY");
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
            gameSessionControlPlaneClient,
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
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(enabledPluginRuntimeState("plugin-1", "plugin-v1")));
    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 130L, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    assertThat(result.truncatedFiringCount()).isEqualTo(1);
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(eventAuditRepository).insertIfAbsentByHandlerIdentity(auditCaptor.capture());
    verify(eventAuditRepository).save(any());
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
                .tag("service", "automation-scripting-service")
                .tag("scope", "game_instance")
                .tag("script_kind", "PLUGIN")
                .tag("event_class", "interval")
                .tag("reason", "resume_window_cap")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("automation_script_timer_catchup_truncated_total")
                .tag("service", "automation-scripting-service")
                .tag("scope", "game_instance")
                .tag("script_kind", "PLUGIN")
                .tag("event_class", "interval")
                .tag("reason", "resume_window_cap")
                .counter()
                .getId()
                .getTags())
        .extracting(Tag::getKey)
        .containsExactlyInAnyOrder("service", "scope", "script_kind", "event_class", "reason");
  }

  @Test
  void skippedTimerMetricPublishesOnlyAfterCommit() {
    configureTruncatedCandidates();
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.observeRuntimeTickProgress(observation(130L, 6_000L));

      assertThat(meterRegistry.find("automation_script_timer_catchup_truncated_total").counter())
          .isNull();
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
      assertThat(
              meterRegistry
                  .get("automation_script_timer_catchup_truncated_total")
                  .tag("service", "automation-scripting-service")
                  .tag("scope", "game_instance")
                  .tag("script_kind", "PLUGIN")
                  .tag("event_class", "interval")
                  .tag("reason", "resume_window_cap")
                  .counter()
                  .count())
          .isEqualTo(1.0);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void skippedTimerMetricDoesNotPublishOnRollback() {
    configureTruncatedCandidates();
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.observeRuntimeTickProgress(observation(130L, 6_000L));

      assertThat(meterRegistry.find("automation_script_timer_catchup_truncated_total").counter())
          .isNull();
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(
              synchronization ->
                  synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      assertThat(meterRegistry.find("automation_script_timer_catchup_truncated_total").counter())
          .isNull();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void skippedTimerMetricDoesNotPublishWhenAuditIdentityAlreadyExists() {
    configureTruncatedCandidates();
    when(eventAuditRepository.insertIfAbsentByHandlerIdentity(any()))
        .thenReturn(
            new ScriptEventAuditRepository.IdempotentInsertResult(new ScriptEventAudit(), false));
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.observeRuntimeTickProgress(observation(130L, 6_000L));

      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      verify(automationQueueService, never()).enqueueWorkItem(any());
      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
      verify(automationQueueService).enqueueWorkItem(any());
      assertThat(meterRegistry.find("automation_script_timer_catchup_truncated_total").counter())
          .isNull();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void firedTimerHasNoUnauthorizedMetric() {
    configureDueWallClockCandidate();
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.observeRuntimeTickProgress(observation(131L, 6_000L));

      assertThat(meterRegistry.find("automation_script_timer_fired_total").counter()).isNull();
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void firedTimerMetricIsNotScheduledWhenScheduleSettlementFails() {
    configureDueWallClockCandidate();
    doThrow(new IllegalStateException("schedule settlement failed"))
        .when(scheduleInstanceRepository)
        .saveAll(any());
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> service.observeRuntimeTickProgress(observation(131L, 6_000L)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("schedule settlement failed");
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
      assertThat(meterRegistry.find("automation_script_timer_fired_total").counter()).isNull();
      verify(automationQueueService, never()).enqueueWorkItem(any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
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
    instance.setScriptPinEpoch(1L);
    instance.setPluginActivationEpoch(1L);
    instance.setLifecycleRevision(1L);
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
  void listInstancesLogsAndContainsPublicationLookupFailures() {
    ScriptScheduleInstance instance = wallClockTimerInstance();
    instance.setPluginId("plugin-1");
    instance.setPluginVersionId("plugin-v1");
    instance.setUpdatedAt(Instant.ofEpochMilli(1236L));
    when(scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdAndScriptPatchVersionOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                "1", "game-1", "patch-1"))
        .thenReturn(List.of(instance));
    when(gameDesignControlPlaneClient.getPublishedScriptPatchVersion("1", "patch-1"))
        .thenThrow(new IllegalStateException("script publication lookup failed"));
    when(gameDesignControlPlaneClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenThrow(new IllegalStateException("plugin publication lookup failed"));

    List<ScriptScheduleInstanceService.ScheduleInstanceSummary> result =
        service.listInstances("1", "game-1", "patch-1", 25);

    assertThat(result)
        .singleElement()
        .satisfies(
            summary -> {
              assertThat(summary.publication().lookupErrorCode())
                  .isEqualTo("GAME_DESIGN_UNAVAILABLE");
              assertThat(summary.pluginPublication().lookupErrorCode())
                  .isEqualTo("GAME_DESIGN_UNAVAILABLE");
            });
  }

  @Test
  void observeRuntimeTickProgressHandlesMixedTickAndWallClockCandidates() {
    ScriptScheduleInstance tickInstance = new ScriptScheduleInstance();
    tickInstance.setTenantId("1");
    tickInstance.setGameInstanceId("game-1");
    tickInstance.setScriptPatchVersion("patch-1");
    tickInstance.setScriptPinEpoch(1L);
    tickInstance.setPluginActivationEpoch(1L);
    tickInstance.setLifecycleRevision(1L);
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
    timerInstance.setScriptPinEpoch(1L);
    timerInstance.setPluginActivationEpoch(1L);
    timerInstance.setLifecycleRevision(1L);
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
        .insertIfAbsentByTriggerIdentity(org.mockito.Mockito.any());
    verify(automationAdmissionStateService).getState("1", "game-1", "region-1");
  }

  @Test
  void observeRuntimeTickProgressDoesNotShareAdmissionStateAcrossGameScopes() {
    ScriptScheduleInstance first =
        tickSchedule("guard-1", "npc-guard", "guard.patrol.v1", 30L, 100L);
    ScriptScheduleInstance second =
        tickSchedule("guard-2", "npc-scout", "guard.scout.v1", 30L, 100L);
    second.setTenantId("2");
    second.setGameInstanceId("game-2");
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn(List.of(first, second));
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(List.of());

    ScriptScheduleInstanceService.RuntimeTickProgressResult result =
        service.observeRuntimeTickProgress(
            new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
                "1", "game-1", "region-1", 12L, 101L, 6_000L));

    assertThat(result.firedScheduleCount()).isEqualTo(1);
    verify(automationAdmissionStateService).getState("1", "game-1", "region-1");
    verify(automationAdmissionStateService).getState("2", "game-2", "region-1");
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

  private void stubScheduleObservation(ScriptScheduleInstance instance) {
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "TICKS"))
        .thenReturn("TICKS".equals(instance.getCadenceUnit()) ? List.of(instance) : List.of());
    when(scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            "1", "game-1", "MILLISECONDS"))
        .thenReturn(
            "MILLISECONDS".equals(instance.getCadenceUnit()) ? List.of(instance) : List.of());
  }

  private List<ScriptScheduleInstance> configureTruncatedCandidates() {
    ScriptSchedulerProperties properties = new ScriptSchedulerProperties();
    properties.setMaxCatchUpFiringsPerObservation(1);
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
            gameSessionControlPlaneClient,
            properties,
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
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(enabledPluginRuntimeState("plugin-1", "plugin-v1")));
    return List.of(first, second);
  }

  private void configureDueWallClockCandidate() {
    stubScheduleObservation(wallClockTimerInstance());
  }

  private ScriptScheduleInstanceService.RuntimeTickProgressObservation observation(
      long tickId, long observedAtMs) {
    return new ScriptScheduleInstanceService.RuntimeTickProgressObservation(
        "1", "game-1", "region-1", 12L, tickId, observedAtMs);
  }

  private static GetGameInstanceRuntimeStateResponse runtimeStateResponse(
      String scriptPatchVersion) {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setRuntimeState(
            GameInstanceRuntimeState.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setPinnedScriptPatchVersion(scriptPatchVersion)
                .setScriptPinEpoch(1L)
                .setRegionId("region-1")
                .setRegionEpoch(12L)
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
                .build())
        .build();
  }

  private static ScriptScheduleInstance wallClockTimerInstance() {
    ScriptScheduleInstance instance = new ScriptScheduleInstance();
    instance.setTenantId("1");
    instance.setGameInstanceId("game-1");
    instance.setScriptPatchVersion("patch-1");
    instance.setScriptPinEpoch(1L);
    instance.setPluginActivationEpoch(1L);
    instance.setLifecycleRevision(1L);
    instance.setScriptId("npc-guard");
    instance.setEventType("onTimerExpire");
    instance.setScheduleDefinitionId("guard.alert.expire.v1");
    instance.setScheduleKind("TIMER");
    instance.setCadenceUnit("MILLISECONDS");
    instance.setCadenceValue(5_000L);
    instance.setPriorityTag("normal");
    instance.setTargetScopeType("ENTITY");
    instance.setTargetScopeId("guard-1");
    instance.setPlayableStateScope("SHARED");
    instance.setWorldSlug("demo");
    instance.setRealmSlug("production");
    instance.setPointerVersion("17");
    instance.setMaterializationStatus("READY");
    instance.setRuntimeRegionId("region-1");
    instance.setRuntimeRegionEpoch(12L);
    instance.setNextDueAt(Instant.ofEpochMilli(5_000L));
    instance.setScheduleMetadataJson("{}");
    instance.setScheduleSemanticsHash("hash-ms");
    return instance;
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

  private static PluginRuntimeState enabledPluginRuntimeState(
      String pluginId, String pluginVersionId) {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId("1");
    state.setGameInstanceId("game-1");
    state.setPluginId(pluginId);
    state.setActivePluginVersionId(pluginVersionId);
    state.setRuntimeRegionId("region-1");
    state.setRuntimeRegionEpoch(12L);
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    state.setPluginActivationEpoch(1L);
    state.setLifecycleRevision(1L);
    return state;
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
    instance.setScriptPinEpoch(1L);
    instance.setPluginActivationEpoch(1L);
    instance.setLifecycleRevision(1L);
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

  private static AdmissionPointerControlPlaneEntry currentPointer(
      String worldSlug, String realmSlug, long pointerVersion) {
    return currentPointer(worldSlug, realmSlug, pointerVersion, "SHARED");
  }

  private static AdmissionPointerControlPlaneEntry currentPointer(
      String worldSlug, String realmSlug, long pointerVersion, String stateScope) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setTenantId("1")
        .setGameInstanceId("game-1")
        .setPointerVersion(pointerVersion)
        .setStateScope(stateScope)
        .build();
  }
}
