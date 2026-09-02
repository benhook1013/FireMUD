package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScriptGameplayCommandHandoffServiceImplTest {
  @Test
  void beginAggregateFanoutRequiresExistingTransaction() throws NoSuchMethodException {
    Method method =
        ScriptGameplayCommandHandoffServiceImpl.class.getMethod(
            "beginAggregateFanout", ScriptWorkItem.class);
    assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    assertThat(method.getAnnotation(Transactional.class).propagation())
        .isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
  }

  @Test
  void locksAdmissionScopeBeforeFenceReadAndRemoteAdmissionInsideTransactionalBoundary()
      throws NoSuchMethodException {
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
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("command-1")
                .build());
    DSLContext dsl = Mockito.mock(DSLContext.class);
    when(dsl.dialect()).thenReturn(SQLDialect.POSTGRES);
    AutomationAdmissionStateService admissionService = admissionStateService();
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            dsl,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    service.handoff(
        workItem(), emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    org.mockito.InOrder ordering = Mockito.inOrder(dsl, admissionService, gameSessionClient);
    ordering.verify(dsl).execute(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt());
    ordering.verify(admissionService).getState("1", "7", "region-1");
    ordering.verify(gameSessionClient).enqueueAutomationCommandIfAbsent(Mockito.any());
    assertThat(
            ScriptGameplayCommandHandoffServiceImpl.class
                .getMethod(
                    "handoff",
                    ScriptWorkItem.class,
                    ScriptGameplayCommandHandoffService.EmittedCommand.class)
                .getAnnotation(Transactional.class))
        .isNotNull();
    assertThat(
            Arrays.stream(ScriptGameplayCommandHandoffServiceImpl.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count())
        .isEqualTo(1L);
  }

  @Test
  void corruptAdmissionModeFailsClosedBeforeRuntimeOrGameSessionAdmission() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionService.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "CORRUPT", 1L, "", "", "", 100L));
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            Mockito.mock(AutomationAdmissionStateRepository.class),
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(),
            emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("AUTHORITY_UNAVAILABLE");
    verifyNoInteractions(gameSessionClient);
  }

  @Test
  void emittedCancellationOutcomesMapToCanonicalReasons() {
    assertThat(
            ScriptHandoffOutcomeSupport.canonicalHandoffReason("runtime_paused", "runtime_paused"))
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED);
    assertThat(
            ScriptHandoffOutcomeSupport.canonicalHandoffReason(
                "rollback_epoch_advanced", "rollback_epoch_advanced"))
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED);
    assertThat(
            ScriptHandoffOutcomeSupport.canonicalHandoffReason(
                "runtime-region-scope-advanced", "runtime-region-scope-advanced"))
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED);
  }

  @Test
  void rollbackEpochAdvanceUsesCanonicalInfrastructureReason() {
    assertThat(
            ScriptHandoffOutcomeSupport.canonicalInfrastructureReason(
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "infrastructure_error", "", "", "", "ROLLBACK_EPOCH_ADVANCED")))
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED);
  }

  @Test
  void runtimeRegionScopeAdvanceUsesRuntimeScopeCancellationReason() {
    assertThat(
            ScriptHandoffOutcomeSupport.canonicalInfrastructureReason(
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "runtime-region-scope-advanced", "", "", "", "")))
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED);
  }

  @Test
  void recognizesOnlyExplicitTransientHandoffCodesAsRetryable() {
    for (String code :
        List.of(
            "AUTH_UNAVAILABLE",
            "AUTHORITY_UNAVAILABLE",
            "GAME_SESSION_UNAVAILABLE",
            "UNAVAILABLE",
            "QUEUE_UNAVAILABLE")) {
      assertThat(
              ScriptHandoffOutcomeSupport.isRetryable(
                  new ScriptGameplayCommandHandoffService.HandoffResult(
                      false, "REMOTE_REJECTED", "", "", "", code)))
          .as(code)
          .isTrue();
    }
    assertThat(
            ScriptHandoffOutcomeSupport.isRetryable(
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "RETRY_QUEUED", "", "", "", "")))
        .isTrue();
    assertThat(
            ScriptHandoffOutcomeSupport.isRetryable(
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "REMOTE_REJECTED", "", "", "", "ROLLBACK_EPOCH_ADVANCED")))
        .isFalse();
    assertThat(
            ScriptHandoffOutcomeSupport.isRetryable(
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "REMOTE_REJECTED", "", "", "", "RUNTIME_PAUSED")))
        .isFalse();
  }

  @Test
  void retryableHandoffPublishesPointerAfterPendingStateAndProjection() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .setError(ErrorDetail.newBuilder().setCode("QUEUE_UNAVAILABLE").build())
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    List<String> operations = new ArrayList<>();
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptWorkItem saved = invocation.getArgument(0);
              operations.add("save:" + saved.getStatus());
              return saved;
            });
    Mockito.doAnswer(
            invocation -> {
              operations.add("refresh");
              return null;
            })
        .when(rolloutProjectionService)
        .refreshForWorkItem(Mockito.any());
    Mockito.doAnswer(
            invocation -> {
              operations.add("enqueue");
              return null;
            })
        .when(automationQueueService)
        .enqueueWorkItem(Mockito.any());
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            null,
            automationQueueService,
            admissionStateService(),
            rolloutProjectionService);

    TransactionSynchronizationManager.initSynchronization();
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              item, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.accepted()).isFalse();
      assertThat(result.errorCode()).isEqualTo("QUEUE_UNAVAILABLE");
      assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
      assertThat(operations)
          .containsExactly(
              "save:HANDOFF_IN_FLIGHT", "refresh", "save:PENDING_EVALUATION", "refresh");
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
      assertThat(operations)
          .containsExactly(
              "save:HANDOFF_IN_FLIGHT", "refresh", "save:PENDING_EVALUATION", "refresh", "enqueue");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void retryableHandoffKeepsDurablePendingStateWhenPointerPublicationFails() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .setError(ErrorDetail.newBuilder().setCode("GAME_SESSION_UNAVAILABLE").build())
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    Mockito.doThrow(new IllegalStateException("redis unavailable"))
        .when(automationQueueService)
        .enqueueWorkItem(Mockito.any());
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            null,
            automationQueueService,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            item, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("GAME_SESSION_UNAVAILABLE");
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    verify(automationQueueService).enqueueWorkItem(item);
  }

  private static AutomationAdmissionStateService admissionStateService() {
    AutomationAdmissionStateService service = Mockito.mock(AutomationAdmissionStateService.class);
    when(service.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "NORMAL", 1L, "", "", "", 100L));
    return service;
  }

  @Test
  void acceptedGameSessionOutcomeLeavesAggregateTerminalizationToExecutor() {
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
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(audit.getFinalStage()).isNull();
    assertThat(audit.getFinalOutcome()).isNull();
    assertThat(audit.getFinalReason()).isNull();
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
  void fanoutRejectionRecordsChildButDefersAggregateTerminalizationToExecutor() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .setError(ErrorDetail.newBuilder().setCode("AUTHORITY_UNAVAILABLE").build())
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
    AutomationAdmissionStateService admissionService = admissionStateService();
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));
    ScriptWorkItem item = workItem();
    service.beginAggregateFanout(item);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              item, emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.accepted()).isFalse();
      assertThat(item.getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
      assertThat(item.getCancelReason()).isNull();
      assertThat(audit.getFinalStage()).isNull();
      assertThat(audit.getFinalOutcome()).isNull();
      verify(workItemRepository).save(Mockito.any(ScriptWorkItem.class));
      verify(handoffEventRepository).save(Mockito.any(ScriptHandoffEvent.class));
      verify(auditRepository, never()).save(Mockito.any(ScriptEventAudit.class));

      service.handoff(
          item, emittedCommand("say hello again", "target-entity-2", "7", "region-1", 12L, 35L, 1));
      verify(gameSessionClient, Mockito.times(1)).getGameInstanceRuntimeState("1", "7", "region-1");
      verify(admissionService, Mockito.times(1)).getState("1", "7", "region-1");
    } finally {
      service.endAggregateFanout(item);
    }

    service.handoff(
        item,
        emittedCommand("say hello after fanout", "target-entity-3", "7", "region-1", 12L, 35L, 2));
    verify(gameSessionClient, Mockito.times(2)).getGameInstanceRuntimeState("1", "7", "region-1");
    verify(admissionService, Mockito.times(2)).getState("1", "7", "region-1");
  }

  @Test
  void fanoutExceptionCleanupAllowsLaterHandoffToRereadAuthority() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenThrow(new IllegalStateException("queue unavailable"))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("auto-2")
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
    AutomationAdmissionStateService admissionService = admissionStateService();
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));
    ScriptWorkItem item = workItem();

    service.beginAggregateFanout(item);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  service.handoff(
                      item,
                      emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("queue unavailable");
    } finally {
      service.endAggregateFanout(item);
    }

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            item,
            emittedCommand("say hello again", "target-entity-2", "7", "region-1", 12L, 35L, 1));

    assertThat(result.accepted()).isTrue();
    verify(gameSessionClient, Mockito.times(2)).getGameInstanceRuntimeState("1", "7", "region-1");
    verify(admissionService, Mockito.times(2)).getState("1", "7", "region-1");
  }

  @Test
  void malformedAcceptedGameSessionOutcomeIsRejectedWithoutAggregateAcceptance() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("REJECTED")
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
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

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    assertThat(result.outcome()).isEqualTo("REMOTE_REJECTED");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(audit.getFinalReason()).isEqualTo("remote_response_invalid");
    verify(handoffEventRepository)
        .save(
            Mockito.argThat(
                event ->
                    "remote_rejected".equals(event.getHandoffOutcome())
                        && "remote_response_invalid".equals(event.getHandoffReason())));
  }

  @Test
  void persistsBoundedReasonInsteadOfRemoteHumanMessage() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1")).thenReturn(null);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("AUTHORITY_UNAVAILABLE");
    assertThat(result.errorMessage()).isEqualTo("runtime owner authority unavailable");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("authority_unavailable");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isNotEqualTo(result.errorMessage());
  }

  @Test
  void persistsIdempotencyConflictAsBoundedHandoffReason() {
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
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("IDEMPOTENCY_CONFLICT")
                        .setMessage("request payload differs")
                        .build())
                .build());
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    service.handoff(
        workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("idempotency_conflict");
  }

  @Test
  void staleTimelineGameSessionOutcomeCancelsWorkItem() {
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
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("CANCELED");
    assertThat(workItemCaptor.getAllValues().get(1).getCancelReason())
        .isEqualTo("runtime_scope_changed");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_scope_changed");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(handoffCaptor.getValue().getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(handoffCaptor.getValue().getEmittedCommandText()).isEqualTo("say hello");
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("rejected");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("runtime_scope_changed");
  }

  @Test
  void pausedGameSessionOutcomeCancelsWithRuntimePausedTaxonomy() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .setError(ErrorDetail.newBuilder().setCode("RUNTIME_PAUSED").build())
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
    ScriptEventAudit audit = new ScriptEventAudit();
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("RUNTIME_PAUSED");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_paused");
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
  void pausedAdmissionCancelsSameEpochWorkBeforeGameSessionHandoff() {
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
                "1", "7", "region-1", "PAUSED_FOR_ROLLBACK", 1L, "req", "admin", "pause", 100L));
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
    assertThat(result.outcome()).isEqualTo("runtime_paused");
    assertThat(result.errorCode()).isEqualTo("RUNTIME_PAUSED");
    verify(gameSessionClient, Mockito.never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("CANCELED");
    assertThat(workItemCaptor.getValue().getCancelReason()).isEqualTo("runtime_paused");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_paused");
  }

  @Test
  void advancedNormalAdmissionEpochCancelsBeforeGameSessionHandoff() {
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
                "1", "7", "region-1", "NORMAL", 2L, "req", "admin", "resume", 100L));
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
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("CANCELED");
    assertThat(workItemCaptor.getValue().getCancelReason()).isEqualTo("rollback_epoch_advanced");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("rollback_epoch_advanced");
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
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
    assertThat(workItemCaptor.getValue().getCancelReason()).isEqualTo("runtime_scope_changed");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_scope_changed");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getHandoffOutcome())
        .isEqualTo("runtime_region_scope_advanced");
    assertThat(handoffCaptor.getValue().getHandoffReason())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED);
  }

  @Test
  void rejectsRuntimeStateFromDifferentScopeBeforeClassifyingCurrent() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("other-tenant")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    when(gameSessionClient.scheduleRemoteFollowup(Mockito.any()))
        .thenReturn(ScheduleRemoteFollowupResponse.newBuilder().build());
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "8", "region-2", 77L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, Mockito.never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient, Mockito.never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void remoteTargetSchedulesDurableFollowupWithoutAggregateTerminalization() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
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
  void remoteDeadlineAtMaxMinusOneDoesNotOverflow() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    when(gameSessionClient.scheduleRemoteFollowup(Mockito.any()))
        .thenReturn(
            ScheduleRemoteFollowupResponse.newBuilder()
                .setCoordinatorId("coordinator")
                .setFollowupId("followup")
                .build());
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            auditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(),
            emittedCommand(
                "say hello", "entity-remote", "8", "region-2", 77L, Long.MAX_VALUE - 1, 0));

    assertThat(result.accepted()).isTrue();
    ArgumentCaptor<ScheduleRemoteFollowupRequest> requestCaptor =
        ArgumentCaptor.forClass(ScheduleRemoteFollowupRequest.class);
    verify(gameSessionClient).scheduleRemoteFollowup(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getOriginDeadlineTickId()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void remoteDeadlineAtMaxIsRejectedBeforeScheduling() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
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
            emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, Long.MAX_VALUE, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENT");
    verify(gameSessionClient, Mockito.never()).scheduleRemoteFollowup(Mockito.any());
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(audit.getFinalReason()).isEqualTo("invalid_argument");
  }

  @Test
  void remoteTargetRejectsMissingTargetRegionEpochBeforeScheduling() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
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
            emittedCommand("say hello", "entity-remote", "8", "region-2", null, 45L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("REMOTE_REJECTED");
    assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENT");
    verify(gameSessionClient, Mockito.never()).scheduleRemoteFollowup(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItemCaptor.getAllValues().get(1).getCancelReason())
        .isEqualTo("invalid_argument");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(audit.getFinalReason()).isEqualTo("invalid_argument");
  }

  @Test
  void remoteTargetRefusesUnavailableOrMalformedRuntimeOwnerEvidence() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(null, GetGameInstanceRuntimeStateResponse.newBuilder().build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult unavailable =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));
    ScriptGameplayCommandHandoffService.HandoffResult malformed =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(unavailable.accepted()).isFalse();
    assertThat(unavailable.errorCode()).isEqualTo("AUTHORITY_UNAVAILABLE");
    assertThat(malformed.accepted()).isFalse();
    assertThat(malformed.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, Mockito.never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void remoteTargetDeadLettersWhenRemoteScheduleResponseOmitsDurableIds() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());
    when(gameSessionClient.scheduleRemoteFollowup(Mockito.any()))
        .thenReturn(ScheduleRemoteFollowupResponse.newBuilder().build());
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

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("REMOTE_REJECTED");
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository, Mockito.times(2)).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getAllValues().get(1).getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItemCaptor.getAllValues().get(1).getCancelReason())
        .isEqualTo("remote_response_invalid");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(audit.getFinalReason()).isEqualTo("remote_response_invalid");
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getRemoteCoordinatorId()).isBlank();
    assertThat(handoffCaptor.getValue().getRemoteFollowupId()).isBlank();
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("remote_rejected");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("remote_response_invalid");
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
