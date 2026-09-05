package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
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
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class DefaultDurableRemoteFollowupExecutionServiceTest {
  private RemoteFollowupRepository remoteFollowupRepository;
  private RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private GameInstanceRepository gameInstanceRepository;
  private GameplayCommandRepository gameplayCommandRepository;
  private RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private TickService tickService;
  private RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private AutomationScriptingClient automationScriptingClient;
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
    automationScriptingClient = mock(AutomationScriptingClient.class);
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
            remoteFollowupRuntimeService,
            automationScriptingClient);
  }

  @Test
  void executeFailsClosedForUnsupportedPayloadKind() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setPlayableStateScope("SHARED");
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson("{\"kind\":\"teleport\"}");
    RemoteCommandCoordinator coordinator = remoteCoordinator();
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
    assertEquals(7L, requestCaptor.getValue().originGameInstanceId());
    assertEquals(9L, requestCaptor.getValue().targetGameInstanceId());
    assertEquals("ABANDONED", requestCaptor.getValue().outcome());
  }

  @Test
  void executeRejectsConflictingDurablePayloadKindAndPayloadJson() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setPlayableStateScope("SHARED");
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadKind("enqueue_gameplay_command");
    followup.setRequestedCommand("LOOK");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_automation_command","command":"LOOK"}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
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
    assertEquals("REMOTE_FOLLOWUP_PAYLOAD_INVALID", result.failureCode());
    assertEquals("kind conflicts with durable followup value", result.failureMessage());
    verifyNoInteractions(
        gameplayCommandRepository,
        gameInstanceRepository,
        runtimeRegionStatusRepository,
        tickService,
        automationScriptingClient);
  }

  @Test
  void executeReplaysTerminalAppliedFollowupWithoutDuplicateResultWrite() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
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
    RemoteFollowup followup = remoteFollowup();
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
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    // The payload is the only source of the validated routing bundle for this case.
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_automation_command","command":"LOOK","worldSlug":"demo","realmSlug":"production","pointerVersion":17,"playableStateScope":"SHARED"}
        """);
    followup.setRequiresSoloTick(true);
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setOriginSourceState("TARGET_REGION_EXECUTED");
    followup.setOriginSourceOrdinal(44L);
    followup.setOriginSourceDueTickId(22L);
    followup.setOriginSourceDueAtMs(1700L);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus runtimeStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(9L);
    runtimeStatus.setRegionId("region-b");
    runtimeStatus.setRegionEpoch(8L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-b"))
        .thenReturn(Optional.of(runtimeStatus));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(runtimeStatus));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-b", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.GameplayCommand> commandCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.GameplayCommand.class);
    org.mockito.Mockito.verify(gameplayCommandRepository, org.mockito.Mockito.atLeastOnce())
        .insertIfAbsentByIdempotencyIdentity(commandCaptor.capture());
    net.firedevops.firemud.gamesession.entity.GameplayCommand admittedCommand =
        commandCaptor.getAllValues().get(0);
    assertEquals("AUTOMATION", admittedCommand.getSourceType());
    assertEquals("coord-1", admittedCommand.getRemoteCoordinatorId());
    assertEquals("followup-1", admittedCommand.getRemoteFollowupId());
    assertEquals("dispatch-1", admittedCommand.getAutomationDispatchId());
    assertEquals("REMOTE_FOLLOWUP", admittedCommand.getOriginSourceKind());
    assertEquals("TARGET_REGION_EXECUTED", admittedCommand.getOriginSourceState());
    assertEquals(Long.valueOf(44L), admittedCommand.getOriginSourceOrdinal());
    assertEquals(Long.valueOf(22L), admittedCommand.getOriginSourceDueTickId());
    assertEquals(Long.valueOf(1700L), admittedCommand.getOriginSourceDueAtMs());
    org.mockito.Mockito.verify(tickService)
        .enqueueCommand(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(9L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.Mockito.eq("LOOK"),
            org.mockito.Mockito.eq(true));
    ArgumentCaptor<RemoteFollowupRuntimeService.ResultRequest> requestCaptor =
        ArgumentCaptor.forClass(RemoteFollowupRuntimeService.ResultRequest.class);
    org.mockito.Mockito.verify(remoteFollowupRuntimeService).recordResult(requestCaptor.capture());
    assertEquals("remote-result:followup-1", requestCaptor.getValue().resultId());
    assertEquals(7L, requestCaptor.getValue().originGameInstanceId());
    assertEquals(9L, requestCaptor.getValue().targetGameInstanceId());
    assertEquals("APPLIED", requestCaptor.getValue().outcome());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("divergentScopeMutations")
  void executeRejectsDivergentScopeBeforeTargetCommandOrResultSideEffects(
      String divergentScope, Consumer<RemoteFollowup> mutation) {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup =
        triggerScriptEventFollowup("{\"kind\":\"enqueue_gameplay_command\",\"command\":\"LOOK\"}");
    followup.setPayloadKind("enqueue_gameplay_command");
    followup.setRequestedCommand("LOOK");
    mutation.accept(followup);
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_FOLLOWUP_SCOPE_INVALID", result.failureCode());
    verify(remoteFollowupRuntimeService)
        .abandonFollowup(
            1L,
            "followup-1",
            "REMOTE_FOLLOWUP_SCOPE_INVALID",
            "Remote followup execution scope does not match its coordinator");
    verify(remoteFollowupRuntimeService, never()).recordResult(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(
        gameplayCommandRepository,
        gameInstanceRepository,
        runtimeRegionStatusRepository,
        tickService,
        automationScriptingClient);
  }

  private static Stream<Arguments> divergentScopeMutations() {
    return Stream.of(
        Arguments.of(
            "originGameInstanceId",
            (Consumer<RemoteFollowup>) followup -> followup.setOriginGameInstanceId(8L)),
        Arguments.of(
            "originRegionId",
            (Consumer<RemoteFollowup>) followup -> followup.setOriginRegionId("region-other")),
        Arguments.of(
            "originRegionEpoch",
            (Consumer<RemoteFollowup>) followup -> followup.setOriginRegionEpoch(5L)),
        Arguments.of(
            "targetGameInstanceId",
            (Consumer<RemoteFollowup>) followup -> followup.setTargetGameInstanceId(8L)),
        Arguments.of(
            "targetRegionId",
            (Consumer<RemoteFollowup>) followup -> followup.setTargetRegionId("region-other")),
        Arguments.of(
            "targetRegionEpoch",
            (Consumer<RemoteFollowup>) followup -> followup.setTargetRegionEpoch(9L)));
  }

  @ParameterizedTest
  @MethodSource("incompleteScopeMutations")
  void executeRejectsIncompleteScopeBeforeTargetCommandOrResultSideEffects(
      String incompleteScope, ScopeMutator mutation) {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup =
        triggerScriptEventFollowup("{\"kind\":\"enqueue_gameplay_command\",\"command\":\"LOOK\"}");
    followup.setPayloadKind("enqueue_gameplay_command");
    followup.setRequestedCommand("LOOK");
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    mutation.apply(followup, coordinator);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_FOLLOWUP_SCOPE_INVALID", result.failureCode());
    assertEquals(
        "Remote followup execution requires a complete origin and target scope",
        result.failureMessage());
    verify(remoteFollowupRuntimeService)
        .abandonFollowup(
            1L,
            "followup-1",
            "REMOTE_FOLLOWUP_SCOPE_INVALID",
            "Remote followup execution requires a complete origin and target scope");
    verify(remoteFollowupRuntimeService, never()).recordResult(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(
        gameplayCommandRepository,
        gameInstanceRepository,
        runtimeRegionStatusRepository,
        tickService,
        automationScriptingClient);
  }

  private static Stream<Arguments> incompleteScopeMutations() {
    return Stream.of(
            scopeMutation(
                "originGameInstanceId.null",
                followup -> followup.setOriginGameInstanceId(null),
                coordinator -> coordinator.setOriginGameInstanceId(null)),
            scopeMutation(
                "originGameInstanceId.zero",
                followup -> followup.setOriginGameInstanceId(0L),
                coordinator -> coordinator.setOriginGameInstanceId(0L)),
            scopeMutation(
                "targetGameInstanceId.null",
                followup -> followup.setTargetGameInstanceId(null),
                coordinator -> coordinator.setTargetGameInstanceId(null)),
            scopeMutation(
                "targetGameInstanceId.zero",
                followup -> followup.setTargetGameInstanceId(0L),
                coordinator -> coordinator.setTargetGameInstanceId(0L)),
            scopeMutation(
                "originRegionId.null",
                followup -> followup.setOriginRegionId(null),
                coordinator -> coordinator.setOriginRegionId(null)),
            scopeMutation(
                "originRegionId.blank",
                followup -> followup.setOriginRegionId(" "),
                coordinator -> coordinator.setOriginRegionId(" ")),
            scopeMutation(
                "targetRegionId.null",
                followup -> followup.setTargetRegionId(null),
                coordinator -> coordinator.setTargetRegionId(null)),
            scopeMutation(
                "targetRegionId.blank",
                followup -> followup.setTargetRegionId(" "),
                coordinator -> coordinator.setTargetRegionId(" ")),
            scopeMutation(
                "originRegionEpoch.zero",
                followup -> followup.setOriginRegionEpoch(0L),
                coordinator -> coordinator.setOriginRegionEpoch(0L)),
            scopeMutation(
                "targetRegionEpoch.zero",
                followup -> followup.setTargetRegionEpoch(0L),
                coordinator -> coordinator.setTargetRegionEpoch(0L)))
        .flatMap(
            mutation ->
                Stream.of(
                    Arguments.of(
                        "followup." + mutation.name(),
                        (ScopeMutator)
                            (followup, coordinator) ->
                                mutation.followupMutation().accept(followup)),
                    Arguments.of(
                        "coordinator." + mutation.name(),
                        (ScopeMutator)
                            (followup, coordinator) ->
                                mutation.coordinatorMutation().accept(coordinator))));
  }

  private static ScopeMutation scopeMutation(
      String name,
      Consumer<RemoteFollowup> followupMutation,
      Consumer<RemoteCommandCoordinator> coordinatorMutation) {
    return new ScopeMutation(name, followupMutation, coordinatorMutation);
  }

  @FunctionalInterface
  private interface ScopeMutator {
    void apply(RemoteFollowup followup, RemoteCommandCoordinator coordinator);
  }

  private record ScopeMutation(
      String name,
      Consumer<RemoteFollowup> followupMutation,
      Consumer<RemoteCommandCoordinator> coordinatorMutation) {}

  @Test
  void executeUsesDurablePayloadAuthorityWhenPayloadJsonIsMalformed() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson("{not-json");
    followup.setPayloadKind("enqueue_automation_command");
    followup.setRequestedCommand("LOOK");
    followup.setRequiresSoloTick(true);
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setOriginSourceState("TARGET_REGION_EXECUTED");
    followup.setOriginSourceOrdinal(44L);
    followup.setOriginSourceDueTickId(22L);
    followup.setOriginSourceDueAtMs(1700L);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus runtimeStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(9L);
    runtimeStatus.setRegionId("region-b");
    runtimeStatus.setRegionEpoch(8L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-b"))
        .thenReturn(Optional.of(runtimeStatus));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(runtimeStatus));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-b", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.GameplayCommand> commandCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.GameplayCommand.class);
    verify(gameplayCommandRepository, org.mockito.Mockito.atLeastOnce())
        .insertIfAbsentByIdempotencyIdentity(commandCaptor.capture());
    net.firedevops.firemud.gamesession.entity.GameplayCommand admittedCommand =
        commandCaptor.getAllValues().get(0);
    assertEquals("REMOTE_FOLLOWUP", admittedCommand.getOriginSourceKind());
    assertEquals("TARGET_REGION_EXECUTED", admittedCommand.getOriginSourceState());
    assertEquals(Long.valueOf(44L), admittedCommand.getOriginSourceOrdinal());
    assertEquals(Long.valueOf(22L), admittedCommand.getOriginSourceDueTickId());
    assertEquals(Long.valueOf(1700L), admittedCommand.getOriginSourceDueAtMs());
    verify(tickService)
        .enqueueCommand(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(9L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.Mockito.eq("LOOK"),
            org.mockito.Mockito.eq(true));
  }

  @Test
  void executeRejectsNonBooleanRequiresSoloTickPayloadValue() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_gameplay_command","command":"LOOK","requiresSoloTick":"sometimes"}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_FOLLOWUP_PAYLOAD_INVALID", result.failureCode());
    assertEquals("requiresSoloTick must be boolean", result.failureMessage());
    verifyNoInteractions(
        gameplayCommandRepository, gameInstanceRepository, runtimeRegionStatusRepository);
    verifyNoInteractions(tickService);
  }

  @Test
  void executeRejectsNonIntegralPointerVersionPayloadValue() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_gameplay_command","command":"LOOK","pointerVersion":1.5}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_GAMEPLAY_PAYLOAD_INVALID", result.failureCode());
    assertEquals("pointerVersion must be a positive integer", result.failureMessage());
    verifyNoInteractions(
        gameplayCommandRepository, gameInstanceRepository, runtimeRegionStatusRepository);
    verifyNoInteractions(tickService);
  }

  @Test
  void executeRejectsNonTextualRequestedCommandPayloadValue() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_gameplay_command","command":true}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_FOLLOWUP_PAYLOAD_INVALID", result.failureCode());
    assertEquals("command must be text", result.failureMessage());
    verifyNoInteractions(
        gameplayCommandRepository, gameInstanceRepository, runtimeRegionStatusRepository);
    verifyNoInteractions(tickService);
  }

  @Test
  void executeRejectsScriptEventUntilDurablePinTuplesAreAvailable() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson("{\"kind\":\"trigger_script_event\",\"isDryRun\":true}");
    followup.setPayloadKind("trigger_script_event");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("remote-enter-1");
    followup.setTriggerMode("TRIGGER_MODE_NORMAL");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:remote-enter-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"R-101\",\"toRegionId\":\"R-102\"}");
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PIN_TUPLE_UNAVAILABLE", result.failureCode());
    org.mockito.Mockito.verifyNoInteractions(automationScriptingClient);
    ArgumentCaptor<RemoteFollowupRuntimeService.ResultRequest> requestCaptor =
        ArgumentCaptor.forClass(RemoteFollowupRuntimeService.ResultRequest.class);
    org.mockito.Mockito.verify(remoteFollowupRuntimeService).recordResult(requestCaptor.capture());
    assertEquals("remote-result:followup-1", requestCaptor.getValue().resultId());
    assertEquals(7L, requestCaptor.getValue().originGameInstanceId());
    assertEquals(9L, requestCaptor.getValue().targetGameInstanceId());
    assertEquals("ABANDONED", requestCaptor.getValue().outcome());
  }

  @Test
  void executePropagatesUnexpectedRuntimeExceptionFromRemoteEnqueue() {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup =
        triggerScriptEventFollowup(
            "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}");
    followup.setPayloadKind("enqueue_automation_command");
    followup.setRequestedCommand("LOOK");
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L))
        .thenThrow(new RuntimeException("database connection reset"));

    assertThrows(RuntimeException.class, () -> service.execute(effect));
    verify(remoteFollowupRepository, never()).save(followup);
    verify(remoteFollowupRuntimeService, never()).recordResult(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void executeAbandonsRemoteEnqueueWhenAdmissionPointerDoesNotMatchTarget() {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup =
        triggerScriptEventFollowup(
            "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}");
    followup.setPayloadKind("enqueue_automation_command");
    followup.setRequestedCommand("LOOK");
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    GameplayAdmissionPointerAuthorityService pointerAuthority =
        mock(GameplayAdmissionPointerAuthorityService.class);
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-b", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(pointerAuthority.listByRuntimeTarget(1L, 9L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "demo",
                    "Demo",
                    "production",
                    "Production",
                    1L,
                    10L,
                    17L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW")));
    service =
        new DefaultDurableRemoteFollowupExecutionService(
            remoteFollowupRepository,
            remoteCommandCoordinatorRepository,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService,
            remoteFollowupRuntimeService,
            automationScriptingClient,
            pointerAuthority);

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("ADMISSION_POINTER_UNAVAILABLE", result.failureCode());
    verify(remoteFollowupRuntimeService).recordResult(org.mockito.ArgumentMatchers.any());
    verify(gameplayCommandRepository, never())
        .insertIfAbsentByIdempotencyIdentity(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void executeRejectsTriggerScriptEventWithoutDurablePinTuples() {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup = triggerScriptEventFollowup("{\"kind\":\"trigger_script_event\"}");
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PIN_TUPLE_UNAVAILABLE", result.failureCode());
    verify(remoteFollowupRuntimeService).recordResult(org.mockito.ArgumentMatchers.any());
    verify(remoteFollowupRepository, never()).save(followup);
    verifyNoInteractions(automationScriptingClient);
  }

  @Test
  void executeRejectsMalformedTriggerScriptEventPayloadBeforeRequestConstruction() {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup = triggerScriptEventFollowup("{not-json");
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_FOLLOWUP_PAYLOAD_INVALID", result.failureCode());
    assertEquals("Target-side remote followup payload is not valid JSON", result.failureMessage());
    verifyNoInteractions(automationScriptingClient);
  }

  @Test
  void executeRejectsUnreadableTriggerScriptEventDryRunValueBeforeRequestConstruction() {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup =
        triggerScriptEventFollowup("{\"kind\":\"trigger_script_event\",\"isDryRun\":\"false\"}");
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", result.failureCode());
    assertEquals("isDryRun must be boolean", result.failureMessage());
    verifyNoInteractions(automationScriptingClient);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "UNSPECIFIED", "UNKNOWN"})
  void executeRejectsTriggerScriptEventWhenPlayableStateScopeIsNotExplicit(
      String playableStateScope) {
    TickEffect effect = triggerScriptEventEffect();
    RemoteFollowup followup = triggerScriptEventFollowup("{\"kind\":\"trigger_script_event\"}");
    followup.setPlayableStateScope(playableStateScope);
    RemoteCommandCoordinator coordinator = triggerScriptEventCoordinator();
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", result.failureCode());
    assertEquals(
        "playableStateScope must be explicitly SHARED or ISOLATED", result.failureMessage());
    verifyNoInteractions(automationScriptingClient);
  }

  @Test
  void executeRejectsConflictingDurableGameplayRowAndPayloadJson() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_gameplay_command","command":"DROP ALL","targetEntityId":"999","worldSlug":"wrong","realmSlug":"wrong","pointerVersion":2,"originSourceKind":"BROKEN","originSourceState":"BROKEN"}
        """);
    followup.setPayloadKind("enqueue_gameplay_command");
    followup.setRequestedCommand("LOOK");
    followup.setRequiresSoloTick(true);
    followup.setOriginSourceKind("REMOTE_FOLLOWUP");
    followup.setOriginSourceState("TARGET_REGION_EXECUTED");
    followup.setOriginSourceOrdinal(44L);
    followup.setOriginSourceDueTickId(22L);
    followup.setOriginSourceDueAtMs(1700L);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus runtimeStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(9L);
    runtimeStatus.setRegionId("region-b");
    runtimeStatus.setRegionEpoch(8L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-b"))
        .thenReturn(Optional.of(runtimeStatus));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(runtimeStatus));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
                1L, 9L, "region-b", 8L, "followup-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_GAMEPLAY_PAYLOAD_INVALID", result.failureCode());
    assertEquals("worldSlug conflicts with durable followup value", result.failureMessage());
    verify(gameplayCommandRepository, never()).save(org.mockito.ArgumentMatchers.any());
    verify(tickService, never())
        .enqueueCommand(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void executeRejectsRemoteGameplayCommandWithPartialRoutingBundle() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_gameplay_command","command":"LOOK"}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_GAMEPLAY_PAYLOAD_INVALID", result.failureCode());
    assertEquals(
        "worldSlug, realmSlug, pointerVersion, and playableStateScope must be provided together",
        result.failureMessage());
    verifyNoInteractions(
        gameplayCommandRepository, gameInstanceRepository, runtimeRegionStatusRepository);
    verifyNoInteractions(tickService);
  }

  @Test
  void executeRejectsTriggerScriptEventWithPartialRoutingBundle() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson("{\"kind\":\"trigger_script_event\"}");
    followup.setPayloadKind("trigger_script_event");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("remote-enter-1");
    followup.setTriggerMode("TRIGGER_MODE_NORMAL");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:remote-enter-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"R-101\",\"toRegionId\":\"R-102\"}");
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", result.failureCode());
    assertEquals(
        "worldSlug, realmSlug, pointerVersion, and playableStateScope must be provided together",
        result.failureMessage());
    verifyNoInteractions(automationScriptingClient);
  }

  @Test
  void executeRejectsConflictingDurableTriggerEventAndPayloadJson() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"trigger_script_event","entityId":"999","eventType":"badEvent","scriptEventId":"bad-id","triggerMode":"CATCH_UP","readSnapshotToken":"bad-token","eventSchemaVersion":"v9","eventPayload":{"bad":true},"worldSlug":"wrong","realmSlug":"wrong","pointerVersion":2}
        """);
    followup.setPayloadKind("trigger_script_event");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("remote-enter-1");
    followup.setTriggerMode("TRIGGER_MODE_NORMAL");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:remote-enter-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"R-101\",\"toRegionId\":\"R-102\"}");
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("ABANDONED", result.effectStatus());
    assertEquals("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", result.failureCode());
    assertEquals("worldSlug conflicts with durable followup value", result.failureMessage());
    verify(automationScriptingClient, never())
        .triggerScriptEvent(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void executeEnqueuesAutomationCommandUsingRegionScopedOwnershipAuthority() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_automation_command","command":"LOOK"}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setAutomationDispatchId("dispatch-1");
    coordinator.setAutomationWorkItemId("work-1");
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus regionScopedStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    regionScopedStatus.setTenantId(1L);
    regionScopedStatus.setGameInstanceId(9L);
    regionScopedStatus.setRegionId("region-b");
    regionScopedStatus.setRegionEpoch(8L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus staleInstanceStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    staleInstanceStatus.setTenantId(1L);
    staleInstanceStatus.setGameInstanceId(9L);
    staleInstanceStatus.setRegionId("region-stale");
    staleInstanceStatus.setRegionEpoch(7L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-b"))
        .thenReturn(Optional.of(regionScopedStatus));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(staleInstanceStatus));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                1L, 9L, "region-b", 8L, "dispatch-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    verify(runtimeRegionStatusRepository).findByTenantIdAndRegionId(1L, "region-b");
    verify(runtimeRegionStatusRepository, never()).findByTenantIdAndGameInstanceId(1L, 9L);
    verify(tickService)
        .enqueueCommand(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(9L),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.Mockito.eq("LOOK"),
            org.mockito.Mockito.eq(false));
  }

  @Test
  void executeEnqueuesGameplayCommandForSupportedPayloadKind() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(
        """
        {"kind":"enqueue_gameplay_command","command":"LOOK"}
        """);
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    coordinator.setPluginId("plugin-1");
    coordinator.setPluginVersionId("plugin-v1");
    GameInstance instance = new GameInstance();
    instance.setId(9L);
    instance.setTenantId(1L);
    net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus runtimeStatus =
        new net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus();
    runtimeStatus.setTenantId(1L);
    runtimeStatus.setGameInstanceId(9L);
    runtimeStatus.setRegionId("region-b");
    runtimeStatus.setRegionEpoch(8L);
    when(remoteFollowupRepository.findByFollowupId("followup-1")).thenReturn(Optional.of(followup));
    when(remoteCommandCoordinatorRepository.findByTenantIdAndFollowupId(1L, "followup-1"))
        .thenReturn(Optional.of(coordinator));
    when(gameInstanceRepository.findById(9L)).thenReturn(Optional.of(instance));
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-b"))
        .thenReturn(Optional.of(runtimeStatus));
    when(runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(1L, 9L))
        .thenReturn(Optional.of(runtimeStatus));
    when(gameplayCommandRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
                1L, 9L, "region-b", 8L, "followup-1"))
        .thenReturn(Optional.empty());
    when(gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation ->
                new GameplayCommandRepository.IdempotentInsertResult(
                    invocation.getArgument(0), true));

    DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
        service.execute(effect);

    assertEquals("APPLIED", result.effectStatus());
    ArgumentCaptor<net.firedevops.firemud.gamesession.entity.GameplayCommand> commandCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.gamesession.entity.GameplayCommand.class);
    org.mockito.Mockito.verify(gameplayCommandRepository, org.mockito.Mockito.atLeastOnce())
        .insertIfAbsentByIdempotencyIdentity(commandCaptor.capture());
    net.firedevops.firemud.gamesession.entity.GameplayCommand admittedCommand =
        commandCaptor.getAllValues().get(0);
    assertEquals("REMOTE_FOLLOWUP", admittedCommand.getSourceType());
    assertEquals("coord-1", admittedCommand.getRemoteCoordinatorId());
    assertEquals("followup-1", admittedCommand.getRemoteFollowupId());
    assertEquals("rfcmd-followup-1", admittedCommand.getCommandId());
    assertEquals("321", admittedCommand.getTargetEntityId());
    assertEquals(Long.valueOf(321L), admittedCommand.getCharacterId());
    assertNull(admittedCommand.getAutomationDispatchId());
    org.mockito.Mockito.verify(tickService)
        .enqueueCommand(
            org.mockito.Mockito.eq(1L),
            org.mockito.Mockito.eq(9L),
            org.mockito.Mockito.eq("rfcmd-followup-1"),
            org.mockito.Mockito.eq("LOOK"),
            org.mockito.Mockito.eq(false));
    ArgumentCaptor<RemoteFollowupRuntimeService.ResultRequest> requestCaptor =
        ArgumentCaptor.forClass(RemoteFollowupRuntimeService.ResultRequest.class);
    org.mockito.Mockito.verify(remoteFollowupRuntimeService).recordResult(requestCaptor.capture());
    assertEquals("remote-result:followup-1", requestCaptor.getValue().resultId());
    assertEquals(7L, requestCaptor.getValue().originGameInstanceId());
    assertEquals(9L, requestCaptor.getValue().targetGameInstanceId());
    assertEquals("APPLIED", requestCaptor.getValue().outcome());
  }

  private static TickEffect triggerScriptEventEffect() {
    TickEffect effect = new TickEffect();
    effect.setTickBatchId("tb-1");
    effect.setEffectKey("followup-1");
    return effect;
  }

  private static RemoteFollowup triggerScriptEventFollowup(String payloadJson) {
    RemoteFollowup followup = remoteFollowup();
    followup.setFollowupId("followup-1");
    followup.setTenantId(1L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    followup.setTargetEntityId("321");
    followup.setDueTickId(55L);
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(17L);
    followup.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    followup.setClaimedTickBatchId("tb-1");
    followup.setPayloadJson(payloadJson);
    followup.setPayloadKind("trigger_script_event");
    followup.setEventType("onEnterRegion");
    followup.setEventSchemaVersion("v1");
    followup.setScriptEventId("remote-enter-1");
    followup.setTriggerMode("TRIGGER_MODE_NORMAL");
    followup.setReadSnapshotToken("game-session:onEnterRegion:9:8:remote-enter-1");
    followup.setEventPayloadJson("{\"fromRegionId\":\"R-101\",\"toRegionId\":\"R-102\"}");
    return followup;
  }

  private static RemoteCommandCoordinator triggerScriptEventCoordinator() {
    RemoteCommandCoordinator coordinator = remoteCoordinator();
    coordinator.setCoordinatorId("coord-1");
    coordinator.setTenantId(1L);
    coordinator.setFollowupId("followup-1");
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setScriptId("script-1");
    coordinator.setScriptPatchVersion("patch-1");
    return coordinator;
  }

  private static RemoteFollowup remoteFollowup() {
    RemoteFollowup followup = new RemoteFollowup();
    followup.setOriginGameInstanceId(7L);
    followup.setOriginRegionId("region-a");
    followup.setOriginRegionEpoch(4L);
    followup.setTargetGameInstanceId(9L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(8L);
    return followup;
  }

  private static RemoteCommandCoordinator remoteCoordinator() {
    RemoteCommandCoordinator coordinator = new RemoteCommandCoordinator();
    coordinator.setOriginGameInstanceId(7L);
    coordinator.setOriginRegionId("region-a");
    coordinator.setOriginRegionEpoch(4L);
    coordinator.setTargetGameInstanceId(9L);
    coordinator.setTargetRegionId("region-b");
    coordinator.setTargetRegionEpoch(8L);
    return coordinator;
  }
}
