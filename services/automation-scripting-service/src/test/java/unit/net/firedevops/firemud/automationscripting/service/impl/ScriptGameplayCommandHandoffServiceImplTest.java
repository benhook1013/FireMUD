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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScriptGameplayCommandHandoffServiceImplTest {
  @Test
  void beginAggregateFanoutUsesShortIntentBoundary() throws NoSuchMethodException {
    Method method =
        ScriptGameplayCommandHandoffServiceImpl.class.getMethod(
            "beginAggregateFanout", ScriptWorkItem.class);
    assertThat(method.getAnnotation(Transactional.class)).isNull();
  }

  @Test
  void preflightsAdmissionBeforeRuntimeReadAndRemoteAdmission() throws NoSuchMethodException {
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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

    org.mockito.InOrder ordering = Mockito.inOrder(admissionService, gameSessionClient, dsl);
    ordering.verify(admissionService).getState("1", "7", "region-1");
    ordering.verify(gameSessionClient).getGameInstanceRuntimeState("1", "7", "region-1");
    ordering.verify(dsl).execute(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt());
    ordering.verify(gameSessionClient).enqueueAutomationCommandIfAbsent(Mockito.any());
    assertThat(
            ScriptGameplayCommandHandoffServiceImpl.class
                .getMethod(
                    "handoff",
                    ScriptWorkItem.class,
                    ScriptGameplayCommandHandoffService.EmittedCommand.class)
                .getAnnotation(Transactional.class))
        .isNull();
    assertThat(
            Arrays.stream(ScriptGameplayCommandHandoffServiceImpl.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count())
        .isEqualTo(1L);
  }

  @Test
  void admissionPreflightFailureIsRetryableBeforeIntentOrRuntimeRead() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionService.getState("1", "7", "region-1"))
        .thenThrow(new IllegalStateException("admission store unavailable"));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("UNAVAILABLE");
    assertThat(ScriptHandoffOutcomeSupport.isRetryable(result)).isTrue();
    verify(admissionService).getState("1", "7", "region-1");
    verifyNoInteractions(gameSessionClient, workItemRepository, auditRepository);
    verify(handoffEventRepository).findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0);
    verify(handoffEventRepository, never()).save(Mockito.any());
  }

  @Test
  void intentCommitsBeforeGameSessionRpcAndRpcRunsWithoutLocalTransaction() {
    List<String> operations = new ArrayList<>();
    RecordingTransactionManager transactionManager = new RecordingTransactionManager(operations);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenAnswer(
            invocation -> {
              operations.add(
                  "runtime:" + TransactionSynchronizationManager.isActualTransactionActive());
              return currentRuntimeState();
            });
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenAnswer(
            invocation -> {
              operations.add(
                  "enqueue:" + TransactionSynchronizationManager.isActualTransactionActive());
              return EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                  .setAccepted(true)
                  .setAdmissionOutcome("ENQUEUED")
                  .setCommandId("command-1")
                  .build();
            });
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    List<ScriptHandoffEvent> savedEvents = new ArrayList<>();
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenAnswer(
            invocation ->
                savedEvents.isEmpty() ? Optional.empty() : Optional.of(savedEvents.getLast()));
    when(handoffEventRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptHandoffEvent event = invocation.getArgument(0);
              if (event.getId() == null) {
                event.setId(44L);
                event.setRowVersion(0);
              } else {
                event.setRowVersion(event.getRowVersion() + 1);
              }
              savedEvents.add(event);
              operations.add(
                  "handoff-save:"
                      + ("handoff_in_flight".equals(event.getHandoffOutcome())
                          ? "intent"
                          : "response"));
              return event;
            });

    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            null,
            null,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            transactionManager);

    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.accepted()).isTrue();
      assertThat(savedEvents.getLast().getId()).isEqualTo(44L);
      assertThat(savedEvents.getLast().getRowVersion()).isEqualTo(1);
      assertThat(savedEvents.getLast().getGameSessionCommandId()).isEqualTo("command-1");
      assertThat(operations)
          .contains(
              "runtime:false", "handoff-save:intent", "enqueue:false", "handoff-save:response");
      assertThat(operations.indexOf("handoff-save:intent"))
          .isLessThan(operations.indexOf("tx-commit-2"));
      assertThat(operations.indexOf("tx-commit-2")).isLessThan(operations.indexOf("enqueue:false"));
      assertThat(operations.indexOf("enqueue:false"))
          .isLessThan(operations.indexOf("handoff-save:response"));
      assertThat(transactionManager.propagations())
          .containsExactly(
              TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
              TransactionDefinition.PROPAGATION_REQUIRES_NEW,
              TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
              TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void concurrentAcceptedResultAfterResponseCheckCannotBeReplacedByStaleResponse() {
    List<String> operations = new ArrayList<>();
    RecordingTransactionManager transactionManager = new RecordingTransactionManager(operations);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("late-command")
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    AtomicReference<ScriptHandoffEvent> persisted = new AtomicReference<>();
    AtomicReference<ScriptHandoffEvent> attemptedResponseWrite = new AtomicReference<>();
    AtomicBoolean intentSaved = new AtomicBoolean();
    AtomicBoolean concurrentWinnerInjected = new AtomicBoolean();
    AtomicInteger saveCount = new AtomicInteger();
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenAnswer(
            invocation -> {
              if (intentSaved.get() && concurrentWinnerInjected.compareAndSet(false, true)) {
                ScriptHandoffEvent intent = persisted.get();
                ScriptHandoffEvent checkedIntent =
                    handoffRow(intent.getId(), intent.getRowVersion(), "handoff_in_flight", "");
                // Model a retry that commits after this response transaction has read the intent
                // but before it attempts to append its older downstream response.
                persisted.set(
                    handoffRow(
                        intent.getId(),
                        intent.getRowVersion() + 1,
                        "duplicate_noop",
                        "winner-command"));
                return Optional.of(checkedIntent);
              }
              return Optional.ofNullable(persisted.get());
            });
    when(handoffEventRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptHandoffEvent event = invocation.getArgument(0);
              saveCount.incrementAndGet();
              ScriptHandoffEvent current = persisted.get();
              if (event.getId() == null) {
                event.setId(44L);
                event.setRowVersion(5);
                persisted.set(event);
                intentSaved.set(true);
                return event;
              }
              attemptedResponseWrite.set(event);
              if (current == null
                  || !event.getId().equals(current.getId())
                  || event.getRowVersion() != current.getRowVersion()) {
                throw new IllegalStateException("stale handoff event write");
              }
              event.setRowVersion(event.getRowVersion() + 1);
              persisted.set(event);
              return event;
            });
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            null,
            null,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            transactionManager);

    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.accepted()).isFalse();
      assertThat(result.outcome()).isEqualTo("HANDOFF_IN_FLIGHT");
      assertThat(persisted.get().getHandoffOutcome()).isEqualTo("duplicate_noop");
      assertThat(persisted.get().getGameSessionCommandId()).isEqualTo("winner-command");
      assertThat(persisted.get().getRowVersion()).isEqualTo(6);
      assertThat(attemptedResponseWrite.get().getId()).isEqualTo(44L);
      assertThat(attemptedResponseWrite.get().getRowVersion()).isEqualTo(5);
      assertThat(saveCount.get()).isEqualTo(2);
      assertThat(concurrentWinnerInjected.get()).isTrue();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @ParameterizedTest
  @CsvSource({"PENDING_EVALUATION", "HANDOFF_IN_FLIGHT"})
  void failedIntentCommitIsRetryableWhenCapturedStateMatchesAndNeverCallsCommandRpc(
      String capturedStatus) {
    FailingIntentCommitTransactionManager transactionManager =
        new FailingIntentCommitTransactionManager();
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("sibling-command")
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem persistedWorkItem = workItem();
    persistedWorkItem.setStatus(capturedStatus);
    when(workItemRepository.findById(99L)).thenReturn(Optional.of(persistedWorkItem));
    List<Integer> savedRowVersions = new ArrayList<>();
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptWorkItem saved = invocation.getArgument(0);
              savedRowVersions.add(saved.getRowVersion());
              saved.setRowVersion(saved.getRowVersion() + 1);
              return saved;
            });
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    AtomicReference<ScriptHandoffEvent> siblingIntent = new AtomicReference<>();
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal(
            Mockito.eq("1"), Mockito.eq(99L), Mockito.anyInt()))
        .thenAnswer(
            invocation ->
                invocation.<Integer>getArgument(2) == 1
                    ? Optional.ofNullable(siblingIntent.get())
                    : Optional.empty());
    when(handoffEventRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptHandoffEvent event = invocation.getArgument(0);
              if (event.getCommandOrdinal() == 1) {
                if (event.getId() == null) {
                  event.setId(45L);
                  event.setRowVersion(0);
                } else {
                  event.setRowVersion(event.getRowVersion() + 1);
                }
                siblingIntent.set(event);
              }
              return event;
            });
    ScriptWorkItem item = workItem();
    item.setStatus(capturedStatus);
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            null,
            null,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            transactionManager);

    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              item, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.accepted()).isFalse();
      assertThat(result.outcome()).isEqualTo("REMOTE_REJECTED");
      assertThat(result.errorCode()).isEqualTo("UNAVAILABLE");
      assertThat(ScriptHandoffOutcomeSupport.isRetryable(result)).isTrue();
      assertThat(item.getStatus()).isEqualTo(capturedStatus);
      assertThat(item.getUpdatedAt()).isEqualTo(Instant.EPOCH);
      assertThat(item.getRowVersion()).isZero();
      assertThat(persistedWorkItem.getStatus()).isEqualTo(capturedStatus);
      assertThat(persistedWorkItem.getRowVersion()).isZero();
      verify(gameSessionClient).getGameInstanceRuntimeState("1", "7", "region-1");
      verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
      verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());

      ScriptGameplayCommandHandoffService.HandoffResult siblingResult =
          service.handoff(
              item, emittedCommand("say sibling", "entity-2", "7", "region-1", 12L, 35L, 1));

      assertThat(siblingResult.accepted()).isTrue();
      assertThat(siblingIntent.get().getHandoffOutcome()).isEqualTo("enqueued");
      assertThat(siblingIntent.get().getRowVersion()).isEqualTo(1);
      assertThat(savedRowVersions).containsExactly(0, 0);
      verify(gameSessionClient, Mockito.times(2)).getGameInstanceRuntimeState("1", "7", "region-1");
      verify(gameSessionClient).enqueueAutomationCommandIfAbsent(Mockito.any());
      assertThat(transactionManager.propagations())
          .containsExactly(
              TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
              TransactionDefinition.PROPAGATION_REQUIRES_NEW,
              TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
              TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
              TransactionDefinition.PROPAGATION_REQUIRES_NEW,
              TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
              TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void recoveredAdmissionAfterSkippedRuntimePreflightRetriesWithoutMarkingMalformed() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionService.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "CORRUPT", 1L, "", "", 100L),
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "NORMAL", 1L, "", "", 100L),
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "NORMAL", 1L, "", "", 100L));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            null,
            null,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new RecordingTransactionManager(new ArrayList<>()));
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);

    service.beginAggregateFanout(item);
    ScriptGameplayCommandHandoffService.HandoffResult result = service.handoff(item, command);
    service.endAggregateFanout(item);

    assertThat(ScriptHandoffOutcomeSupport.isRetryable(result)).isTrue();
    assertThat(result.errorCode()).isEqualTo("UNAVAILABLE");
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    verify(gameSessionClient, never())
        .getGameInstanceRuntimeState(Mockito.any(), Mockito.any(), Mockito.any());
    verify(handoffEventRepository, never()).save(Mockito.any());
    verify(workItemRepository, never()).save(Mockito.any());
  }

  @ParameterizedTest
  @CsvSource({"PENDING_EVALUATION, 1", "EVALUATING, 0", "HANDOFF_IN_FLIGHT, 1"})
  void failedIntentCommitWithoutIntentAndChangedStateRequiresReconciliation(
      String persistedStatus, int persistedRowVersion) {
    FailingIntentCommitTransactionManager transactionManager =
        new FailingIntentCommitTransactionManager();
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem persistedWorkItem = workItem();
    persistedWorkItem.setStatus(persistedStatus);
    persistedWorkItem.setRowVersion(persistedRowVersion);
    when(workItemRepository.findById(99L)).thenReturn(Optional.of(persistedWorkItem));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptWorkItem saved = invocation.getArgument(0);
              saved.setRowVersion(saved.getRowVersion() + 1);
              return saved;
            });
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.empty());
    when(handoffEventRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            null,
            null,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            transactionManager);
    ScriptWorkItem item = workItem();

    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              item, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.errorCode()).isEqualTo("HANDOFF_IN_FLIGHT");
      assertThat(ScriptHandoffOutcomeSupport.isRetryable(result)).isFalse();
      assertThat(persistedWorkItem.getStatus()).isEqualTo(persistedStatus);
      assertThat(persistedWorkItem.getRowVersion()).isEqualTo(persistedRowVersion);
      verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
      verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void failedIntentCommitWithMismatchedDurableIdentityStillRequiresReconciliation() {
    FailingIntentCommitTransactionManager transactionManager =
        new FailingIntentCommitTransactionManager();
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem persistedWorkItem = workItem();
    persistedWorkItem.setStatus("EVALUATING");
    when(workItemRepository.findById(99L)).thenReturn(Optional.of(persistedWorkItem));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptWorkItem saved = invocation.getArgument(0);
              saved.setRowVersion(saved.getRowVersion() + 1);
              return saved;
            });
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);
    ScriptHandoffEvent mismatchedIntent = acceptedHandoffEvent(item, command, "handoff_in_flight");
    mismatchedIntent.setId(44L);
    mismatchedIntent.setRowVersion(5);
    mismatchedIntent.setEmittedCommandText("different command identity");
    AtomicBoolean intentSaved = new AtomicBoolean();
    AtomicBoolean mismatchedReadReturned = new AtomicBoolean();
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenAnswer(
            invocation -> {
              if (intentSaved.get() && mismatchedReadReturned.compareAndSet(false, true)) {
                return Optional.of(mismatchedIntent);
              }
              return Optional.empty();
            });
    when(handoffEventRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              intentSaved.set(true);
              return invocation.getArgument(0);
            });
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            null,
            null,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            transactionManager);

    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result = service.handoff(item, command);

      assertThat(result.errorCode()).isEqualTo("HANDOFF_IN_FLIGHT");
      assertThat(result.errorMessage())
          .contains("persisted handoff intent identity does not match request");
      assertThat(ScriptHandoffOutcomeSupport.isRetryable(result)).isFalse();
      assertThat(mismatchedReadReturned.get()).isTrue();
      assertThat(mismatchedIntent.getId()).isEqualTo(44L);
      assertThat(mismatchedIntent.getRowVersion()).isEqualTo(5);
      verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
      verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
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
                "1", "7", "region-1", "CORRUPT", 1L, "", "", 100L));
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
  void missingScriptPatchBaseVersionUsesDedicatedInfrastructureReason() {
    assertThat(
            ScriptHandoffOutcomeSupport.canonicalInfrastructureReason(
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    false,
                    "REMOTE_REJECTED",
                    "",
                    "",
                    "",
                    ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE)))
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
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
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
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
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
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
                "1", "7", "region-1", "NORMAL", 1L, "", "", 100L));
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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

    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            item, emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome()).isEqualTo("ENQUEUED");
    ArgumentCaptor<EnqueueAutomationCommandIfAbsentRequest> requestCaptor =
        ArgumentCaptor.forClass(EnqueueAutomationCommandIfAbsentRequest.class);
    verify(gameSessionClient).enqueueAutomationCommandIfAbsent(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getAutomationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(requestCaptor.getValue().getAutomationWorkItemId()).isEqualTo("99");
    assertThat(requestCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(requestCaptor.getValue().getScriptPatchBaseVersionId()).isEqualTo(7L);
    assertThat(requestCaptor.getValue().getScriptPinEpoch()).isEqualTo(2L);
    assertThat(requestCaptor.getValue().getScriptPinControlPlaneRequestId())
        .isEqualTo("pin-request-1");
    assertThat(requestCaptor.getValue().getCommand()).isEqualTo("say hello");
    assertThat(requestCaptor.getValue().getTargetEntityId()).isEqualTo("target-entity-1");
    assertThat(requestCaptor.getValue().getDueTickId()).isEqualTo(34L);
    assertThat(requestCaptor.getValue().getPluginId()).isEqualTo("plugin-1");
    assertThat(requestCaptor.getValue().getPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(requestCaptor.getValue().getBindingId()).isEqualTo("binding-1");
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
    verify(handoffEventRepository, Mockito.times(2)).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getAllValues().getFirst().getHandoffOutcome())
        .isEqualTo("handoff_in_flight");
    ScriptHandoffEvent persistedOutcome = handoffCaptor.getAllValues().getLast();
    assertThat(persistedOutcome.getAutomationDispatchId()).isEqualTo("workItem:99#0");
    assertThat(persistedOutcome.getGameSessionCommandId()).isEqualTo("auto-1");
    assertThat(persistedOutcome.getTargetGameInstanceId()).isEqualTo("7");
    assertThat(persistedOutcome.getTargetRegionId()).isEqualTo("region-1");
    assertThat(persistedOutcome.getTargetRegionEpoch()).isEqualTo(12L);
    assertThat(persistedOutcome.getRemoteCoordinatorId()).isBlank();
    assertThat(persistedOutcome.getRemoteFollowupId()).isBlank();
    assertThat(persistedOutcome.getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(persistedOutcome.getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(persistedOutcome.getSourceOrdinal()).isEqualTo(5000L);
    assertThat(persistedOutcome.getEmittedCommandText()).isEqualTo("say hello");
    assertThat(persistedOutcome.getHandoffOutcome()).isEqualTo("enqueued");
    assertThat(persistedOutcome.getEventId()).isEqualTo("she-work-item-99-command-0");
  }

  @ParameterizedTest
  @CsvSource({
    "enqueued, ENQUEUED",
    "duplicate_noop, DUPLICATE_NOOP",
    "remote_scheduled, REMOTE_SCHEDULED"
  })
  void persistedAcceptedOutcomeSurvivesLaterAdmissionFence(
      String persistedOutcome, String expectedOutcome) {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);
    ScriptHandoffEvent acceptedEvent = acceptedHandoffEvent(item, command, persistedOutcome);
    acceptedEvent.setGameSessionCommandId("command-accepted");
    acceptedEvent.setRemoteCoordinatorId("coordinator-accepted");
    acceptedEvent.setRemoteFollowupId("followup-accepted");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.of(acceptedEvent));
    when(admissionService.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "NORMAL", 2L, "", "", 200L));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionService,
            rolloutProjectionService);

    ScriptGameplayCommandHandoffService.HandoffResult result = service.handoff(item, command);

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome()).isEqualTo(expectedOutcome);
    if ("REMOTE_SCHEDULED".equals(expectedOutcome)) {
      assertThat(result.remoteCoordinatorId()).isEqualTo("coordinator-accepted");
      assertThat(result.remoteFollowupId()).isEqualTo("followup-accepted");
    } else {
      assertThat(result.commandId()).isEqualTo("command-accepted");
    }
    verifyNoInteractions(gameSessionClient);
    verifyNoInteractions(admissionService);
    verify(handoffEventRepository, never()).save(Mockito.any());
    verify(workItemRepository, never()).save(Mockito.any());
    verifyNoInteractions(auditRepository, rolloutProjectionService);
    assertThat(acceptedEvent.getHandoffOutcome()).isEqualTo(persistedOutcome);
  }

  @ParameterizedTest
  @CsvSource({"enqueued", "duplicate_noop", "remote_scheduled"})
  void retainedAcceptedOutcomeWithoutDurableOwnerIdentityFailsClosed(String persistedOutcome) {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);
    ScriptHandoffEvent retained = acceptedHandoffEvent(item, command, persistedOutcome);
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.of(retained));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            Mockito.mock(AutomationAdmissionStateService.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result = service.handoff(item, command);

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verifyNoInteractions(gameSessionClient);
    verify(handoffEventRepository, never()).save(Mockito.any());
  }

  @Test
  void persistedAcceptedOutcomeSurvivesLaterRuntimeOwnerFence() {
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
                        .setRegionEpoch(13L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);
    ScriptHandoffEvent acceptedEvent = acceptedHandoffEvent(item, command, "enqueued");
    acceptedEvent.setGameSessionCommandId("command-accepted");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.of(acceptedEvent));
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionStateService(),
            rolloutProjectionService);

    ScriptGameplayCommandHandoffService.HandoffResult result = service.handoff(item, command);

    assertThat(result.accepted()).isTrue();
    assertThat(result.outcome()).isEqualTo("ENQUEUED");
    verifyNoInteractions(gameSessionClient);
    verify(handoffEventRepository, never()).save(Mockito.any());
    verify(workItemRepository, never()).save(Mockito.any());
    verifyNoInteractions(auditRepository, rolloutProjectionService);
  }

  @ParameterizedTest
  @CsvSource({"command", "target", "pin"})
  void acceptedReplayRejectsChangedIdentityWithoutMutation(String changedIdentity) {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);
    ScriptHandoffEvent acceptedEvent = acceptedHandoffEvent(item, command, "enqueued");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.of(acceptedEvent));
    if ("command".equals(changedIdentity)) {
      command = emittedCommand("say goodbye", "entity-1", "7", "region-1", 12L, 34L, 0);
    } else if ("target".equals(changedIdentity)) {
      command = emittedCommand("say hello", "entity-2", "7", "region-1", 12L, 34L, 0);
    } else {
      item.setScriptPinControlPlaneRequestId("pin-request-2");
    }
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionService,
            rolloutProjectionService);

    ScriptGameplayCommandHandoffService.HandoffResult result = service.handoff(item, command);

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_IDEMPOTENCY_CONFLICT);
    verifyNoInteractions(
        gameSessionClient, admissionService, auditRepository, rolloutProjectionService);
    verify(handoffEventRepository, never()).save(Mockito.any());
    verify(workItemRepository, never()).save(Mockito.any());
  }

  @Test
  void nonAcceptedPersistedOutcomeStillProcessesLaterAdmissionFence() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    ScriptHandoffEvent retryableEvent = new ScriptHandoffEvent();
    retryableEvent.setHandoffOutcome("game_session_unavailable");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.of(retryableEvent));
    when(admissionService.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "PAUSED_FOR_ROLLBACK", 1L, "admin", "rollback", 200L));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            handoffEventRepository,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED);
    verify(admissionService).getState("1", "7", "region-1");
    verifyNoInteractions(gameSessionClient);
    verify(workItemRepository).save(Mockito.any());
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("runtime_paused");
  }

  @Test
  void duplicateNoopRetryReusesStableHandoffEventIdentity() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("command-1")
                .build())
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("DUPLICATE_NOOP")
                .setCommandId("command-1")
                .build());
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);

    assertThat(service.handoff(item, command).accepted()).isTrue();
    assertThat(service.handoff(item, command).accepted()).isTrue();

    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository, Mockito.times(4)).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getAllValues())
        .extracting(ScriptHandoffEvent::getEventId)
        .containsExactly(
            "she-work-item-99-command-0",
            "she-work-item-99-command-0",
            "she-work-item-99-command-0",
            "she-work-item-99-command-0");
    assertThat(handoffCaptor.getAllValues().get(0).getHandoffOutcome())
        .isEqualTo("handoff_in_flight");
    assertThat(handoffCaptor.getAllValues().get(1).getHandoffOutcome()).isEqualTo("enqueued");
    assertThat(handoffCaptor.getAllValues().get(2).getHandoffOutcome())
        .isEqualTo("handoff_in_flight");
    assertThat(handoffCaptor.getAllValues().get(3).getHandoffOutcome()).isEqualTo("duplicate_noop");
  }

  @Test
  void lostLocalResponseRetryConvergesToDuplicateNoopWithStableIdentity() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("GAME_SESSION_UNAVAILABLE")
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("GAME_SESSION_UNAVAILABLE")
                        .setMessage("response lost after owner commit")
                        .build())
                .build())
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("DUPLICATE_NOOP")
                .setCommandId("command-1")
                .build());
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0);

    assertThat(service.handoff(item, command).accepted()).isFalse();
    assertThat(service.handoff(item, command).accepted()).isTrue();

    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository, Mockito.times(4)).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getAllValues())
        .extracting(ScriptHandoffEvent::getEventId)
        .containsExactly(
            "she-work-item-99-command-0",
            "she-work-item-99-command-0",
            "she-work-item-99-command-0",
            "she-work-item-99-command-0");
    assertThat(handoffCaptor.getAllValues().get(0).getHandoffOutcome())
        .isEqualTo("handoff_in_flight");
    assertThat(handoffCaptor.getAllValues().get(1).getHandoffOutcome())
        .isEqualTo("game_session_unavailable");
    assertThat(handoffCaptor.getAllValues().get(2).getHandoffOutcome())
        .isEqualTo("handoff_in_flight");
    assertThat(handoffCaptor.getAllValues().get(3).getHandoffOutcome()).isEqualTo("duplicate_noop");
  }

  @Test
  void unpinnedHandoffRetainsExplicitUnpinnedTupleInHandoffEvidence() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptWorkItem unpinned = workItem();
    unpinned.setScriptPinEpoch(0L);
    unpinned.setScriptPinControlPlaneRequestId(null);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            unpinned, emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("REMOTE_REJECTED");
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getScriptPinEpoch()).isZero();
    assertThat(handoffCaptor.getValue().getScriptPinControlPlaneRequestId()).isBlank();
  }

  @Test
  void reusedHandoffIdentityCarriesExistingRowVersionIntoOptimisticSave() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(true)
                .setAdmissionOutcome("ENQUEUED")
                .setCommandId("auto-retry")
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
                .build());
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0);
    ScriptHandoffEvent existing = new ScriptHandoffEvent();
    existing.setId(44L);
    existing.setRowVersion(7);
    existing.setEventId("she-work-item-99-command-0");
    existing.setTenantId(item.getTenantId());
    existing.setGameInstanceId(item.getGameInstanceId());
    existing.setScriptPatchVersion(item.getScriptPatchVersion());
    existing.setScriptPinEpoch(item.getScriptPinEpoch());
    existing.setScriptPinControlPlaneRequestId(item.getScriptPinControlPlaneRequestId());
    existing.setScriptId(item.getScriptId());
    existing.setBindingId(item.getBindingId());
    existing.setPluginId(item.getPluginId());
    existing.setPluginVersionId(item.getPluginVersionId());
    existing.setPluginActivationEpoch(item.getPluginActivationEpoch());
    existing.setLifecycleRevision(item.getLifecycleRevision());
    existing.setWorkItemId(item.getId());
    existing.setCommandOrdinal(command.ordinal());
    existing.setAutomationDispatchId("workItem:99#0");
    existing.setTargetGameInstanceId(command.targetGameInstanceId());
    existing.setTargetRegionId(command.targetRegionId());
    existing.setTargetRegionEpoch(command.targetRegionEpoch());
    existing.setTargetEntityId(command.targetEntityId());
    existing.setPlayableStateScope(item.getPlayableStateScope());
    existing.setWorldSlug(item.getWorldSlug());
    existing.setRealmSlug(item.getRealmSlug());
    existing.setPointerVersion(item.getPointerVersion());
    existing.setSourceKind(item.getSourceKind());
    existing.setSourceState(item.getSourceState());
    existing.setSourceOrdinal(item.getSourceOrdinal());
    existing.setSourceDueTickId(item.getSourceDueTickId());
    existing.setSourceDueAtMs(item.getSourceDueAtMs());
    existing.setEmittedCommandText(command.commandText());
    existing.setHandoffOutcome("handoff_in_flight");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 0))
        .thenReturn(Optional.of(existing));
    List<Integer> savedRowVersions = new ArrayList<>();
    List<Long> savedIds = new ArrayList<>();
    List<String> savedEventIds = new ArrayList<>();
    Mockito.doAnswer(
            invocation -> {
              ScriptHandoffEvent saved = invocation.getArgument(0);
              savedRowVersions.add(saved.getRowVersion());
              savedIds.add(saved.getId());
              savedEventIds.add(saved.getEventId());
              int nextRowVersion = saved.getRowVersion() + 1;
              saved.setRowVersion(nextRowVersion);
              existing.setRowVersion(nextRowVersion);
              return saved;
            })
        .when(handoffEventRepository)
        .save(Mockito.any());
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    service.handoff(item, command);

    verify(gameSessionClient).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository, Mockito.times(2)).save(handoffCaptor.capture());
    assertThat(savedRowVersions).containsExactly(7, 8);
    assertThat(savedIds).containsExactly(44L, 44L);
    assertThat(savedEventIds)
        .containsExactly("she-work-item-99-command-0", "she-work-item-99-command-0");
  }

  @Test
  void recordsUnattemptedFenceDispositionWithoutExternalHandoff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));
    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.EmittedCommand command =
        emittedCommand("WAIT", "target-entity-1", "7", "region-1", 12L, 34L, 2);

    service.recordUnattempted(item, command, "plugin_disabled");

    verifyNoInteractions(gameSessionClient);
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getEventId()).isEqualTo("she-work-item-99-command-2");
    assertThat(handoffCaptor.getValue().getAutomationDispatchId()).isEqualTo("workItem:99#2");
    assertThat(handoffCaptor.getValue().getCommandOrdinal()).isEqualTo(2);
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("unattempted");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("plugin_disabled");
  }

  @Test
  void recordUnattemptedLocksAdmissionScopeBeforeReadingPriorHandoff() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    DSLContext dsl = Mockito.mock(DSLContext.class);
    when(dsl.dialect()).thenReturn(SQLDialect.POSTGRES);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            dsl,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    service.recordUnattempted(
        workItem(),
        emittedCommand("WAIT", "target-entity-1", "7", "region-1", 12L, 34L, 2),
        "plugin_disabled");

    org.mockito.InOrder ordering = Mockito.inOrder(dsl, handoffEventRepository);
    ordering.verify(dsl).execute(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt());
    ordering
        .verify(handoffEventRepository, Mockito.times(2))
        .findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 2);
    verifyNoInteractions(gameSessionClient);
  }

  @ParameterizedTest
  @CsvSource({"enqueued", "duplicate_noop"})
  void preservesPriorAttemptedChildWhenLaterFanoutIsFenced(String attemptedOutcome) {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptHandoffEvent acceptedEvent = new ScriptHandoffEvent();
    acceptedEvent.setHandoffOutcome(attemptedOutcome);
    acceptedEvent.setHandoffReason("game_session_accepted");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 2))
        .thenReturn(Optional.of(acceptedEvent));
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    service.recordUnattempted(
        workItem(),
        emittedCommand("WAIT", "target-entity-1", "7", "region-1", 12L, 34L, 2),
        "plugin_disabled");

    verifyNoInteractions(gameSessionClient);
    verify(handoffEventRepository, never()).save(Mockito.any());
    assertThat(acceptedEvent.getHandoffOutcome()).isEqualTo(attemptedOutcome);
    assertThat(acceptedEvent.getHandoffReason()).isEqualTo("game_session_accepted");
  }

  @Test
  void rewritesExistingUnattemptedChildWithLatestFenceReason() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptHandoffEvent existingEvent = new ScriptHandoffEvent();
    existingEvent.setId(44L);
    existingEvent.setRowVersion(7);
    existingEvent.setHandoffOutcome("unattempted");
    existingEvent.setHandoffReason("runtime_paused");
    when(handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal("1", 99L, 2))
        .thenReturn(Optional.of(existingEvent));
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    service.recordUnattempted(
        workItem(),
        emittedCommand("WAIT", "target-entity-1", "7", "region-1", 12L, 34L, 2),
        "plugin_disabled");

    verifyNoInteractions(gameSessionClient);
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getId()).isEqualTo(44L);
    assertThat(handoffCaptor.getValue().getRowVersion()).isEqualTo(7);
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("unattempted");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("plugin_disabled");
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
      verify(handoffEventRepository, Mockito.times(2)).save(Mockito.any(ScriptHandoffEvent.class));
      verify(auditRepository, never()).save(Mockito.any(ScriptEventAudit.class));

      service.handoff(
          item, emittedCommand("say hello again", "target-entity-2", "7", "region-1", 12L, 35L, 1));
      verify(gameSessionClient, Mockito.times(1)).getGameInstanceRuntimeState("1", "7", "region-1");
      verify(admissionService, Mockito.times(3)).getState("1", "7", "region-1");
    } finally {
      service.endAggregateFanout(item);
    }

    service.handoff(
        item,
        emittedCommand("say hello after fanout", "target-entity-3", "7", "region-1", 12L, 35L, 2));
    verify(gameSessionClient, Mockito.times(2)).getGameInstanceRuntimeState("1", "7", "region-1");
    verify(admissionService, Mockito.times(4)).getState("1", "7", "region-1");
  }

  @Test
  void aggregateChildRereadsAdmissionFenceBeforeIntentCommit() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    AutomationAdmissionStateService admissionService =
        Mockito.mock(AutomationAdmissionStateService.class);
    when(admissionService.getState("1", "7", "region-1"))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "NORMAL", 1L, "", "", 100L),
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1", "7", "region-1", "PAUSED_FOR_ROLLBACK", 1L, "admin", "pause", 101L));
    ScriptHandoffEventRepository handoffEventRepository =
        Mockito.mock(ScriptHandoffEventRepository.class);
    ScriptGameplayCommandHandoffServiceImpl service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            handoffEventRepository,
            admissionService,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));
    ScriptWorkItem item = workItem();

    service.beginAggregateFanout(item);
    try {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          service.handoff(
              item, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

      assertThat(result.accepted()).isFalse();
      assertThat(result.errorCode()).isEqualTo("RUNTIME_PAUSED");
      verify(admissionService, Mockito.times(2)).getState("1", "7", "region-1");
      verify(gameSessionClient, Mockito.never()).enqueueAutomationCommandIfAbsent(Mockito.any());
      verify(gameSessionClient, Mockito.never()).scheduleRemoteFollowup(Mockito.any());
      ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
          ArgumentCaptor.forClass(ScriptHandoffEvent.class);
      verify(handoffEventRepository).save(handoffCaptor.capture());
      assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("runtime_paused");
      assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("runtime_paused");
    } finally {
      service.endAggregateFanout(item);
    }
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
      ScriptGameplayCommandHandoffService.HandoffResult ambiguous =
          service.handoff(
              item, emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));
      assertThat(ambiguous.accepted()).isFalse();
      assertThat(ambiguous.outcome()).isEqualTo("HANDOFF_IN_FLIGHT");
      assertThat(item.getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
    } finally {
      service.endAggregateFanout(item);
    }

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            item,
            emittedCommand("say hello again", "target-entity-2", "7", "region-1", 12L, 35L, 1));

    assertThat(result.accepted()).isTrue();
    verify(gameSessionClient, Mockito.times(2)).getGameInstanceRuntimeState("1", "7", "region-1");
    verify(admissionService, Mockito.times(3)).getState("1", "7", "region-1");
  }

  @Test
  void malformedAcceptedGameSessionOutcomeRetainsInFlightReconciliationEvidence() {
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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

    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            item, emittedCommand("say hello", "target-entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(item.getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(item.getFailureGeneration()).isZero();
    assertThat(result.outcome()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(audit.getFinalStage()).isNull();
    assertThat(audit.getFinalOutcome()).isNull();
    assertThat(audit.getFinalReason()).isNull();
    verify(handoffEventRepository)
        .save(
            Mockito.argThat(
                event ->
                    "handoff_in_flight".equals(event.getHandoffOutcome())
                        && "handoff_in_flight".equals(event.getHandoffReason())));
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
  void nullLocalOwnerResponseFailsClosedWithoutSchedulingRemoteFollowup() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any())).thenReturn(null);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(result.errorCode()).isEqualTo("HANDOFF_IN_FLIGHT");
    verify(gameSessionClient).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void unavailableLocalRuntimeOwnerFailsClosedBeforeLocalEnqueue() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1")).thenReturn(null);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("AUTHORITY_UNAVAILABLE");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(workItemCaptor.getValue().getStatus()).isNotEqualTo("HANDOFF_IN_FLIGHT");
  }

  @Test
  void malformedLocalRuntimeOwnerFailsClosedBeforeLocalEnqueue() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItemCaptor.getValue().getFailureGeneration()).isEqualTo(1L);
    assertThat(workItemCaptor.getValue().getCancelReason()).isEqualTo("remote_response_invalid");
  }

  @Test
  void staleLocalRuntimeOwnerCancelsBeforeLocalEnqueue() {
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
                        .setRegionEpoch(11L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_REGION_SCOPE_ADVANCED);
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("CANCELED");
  }

  @Test
  void mismatchedLocalRuntimeOwnerFailsClosedBeforeLocalEnqueue() {
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItemCaptor.getValue().getCancelReason()).isEqualTo("remote_response_invalid");
  }

  @Test
  void legacyRuntimeBaseVersionFailsClosedBeforeLocalEnqueue() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    GameInstanceRuntimeState legacyRuntimeState =
        currentRuntimeState().getRuntimeState().toBuilder()
            .clearPinnedScriptPatchBaseVersionId()
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(legacyRuntimeState)
                .build());
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void missingWorkItemBaseVersionFailsClosedBeforeLocalEnqueue() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
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
    ScriptWorkItem workItem = workItem();
    workItem.setScriptPatchBaseVersionId(null);

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    assertThat(workItem.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItem.getCancelReason())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    assertThat(audit.getFinalStage()).isEqualTo(ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF);
    assertThat(audit.getFinalOutcome())
        .isEqualTo(ScriptHandoffOutcomeSupport.OUTCOME_INFRASTRUCTURE_ERROR);
    assertThat(audit.getFinalReason())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    verify(handoffEventRepository)
        .save(
            Mockito.argThat(
                event ->
                    "remote_rejected".equals(event.getHandoffOutcome())
                        && ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE
                            .equals(event.getHandoffReason())));
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
  }

  @ParameterizedTest
  @CsvSource({"0", "-1"})
  void nonPositiveWorkItemBaseVersionFailsClosedBeforeRuntimeOwnerLookup(
      long scriptPatchBaseVersionId) {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
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
    ScriptWorkItem workItem = workItem();
    workItem.setScriptPatchBaseVersionId(scriptPatchBaseVersionId);

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    assertThat(workItem.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(workItem.getCancelReason())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    assertThat(audit.getFinalStage()).isEqualTo(ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF);
    assertThat(audit.getFinalOutcome())
        .isEqualTo(ScriptHandoffOutcomeSupport.OUTCOME_INFRASTRUCTURE_ERROR);
    assertThat(audit.getFinalReason())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    verify(handoffEventRepository)
        .save(
            Mockito.argThat(
                event ->
                    "remote_rejected".equals(event.getHandoffOutcome())
                        && ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE
                            .equals(event.getHandoffReason())));
    verifyNoInteractions(gameSessionClient);
  }

  @Test
  void missingWorkItemBaseVersionFailsClosedBeforeRemoteSchedule() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));
    ScriptWorkItem workItem = workItem();
    workItem.setScriptPatchBaseVersionId(null);

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem, emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_SCRIPT_PATCH_BASE_VERSION_UNAVAILABLE);
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void mismatchedRuntimeBaseVersionFailsClosedBeforeRemoteSchedule() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    currentRuntimeState().getRuntimeState().toBuilder()
                        .setPinnedScriptPatchBaseVersionId(8L)
                        .build())
                .build());
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_REGION_SCOPE_ADVANCED);
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void nullRemoteOwnerResponseFailsClosedWithoutLocalEnqueue() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.scheduleRemoteFollowup(Mockito.any())).thenReturn(null);
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(result.errorCode()).isEqualTo("HANDOFF_IN_FLIGHT");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    verify(gameSessionClient).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void blankLocalErrorMetadataMapsToRemoteResponseInvalid() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.enqueueAutomationCommandIfAbsent(Mockito.any()))
        .thenReturn(
            EnqueueAutomationCommandIfAbsentResponse.newBuilder()
                .setAccepted(false)
                .setAdmissionOutcome("REJECTED")
                .build());
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, never()).scheduleRemoteFollowup(Mockito.any());
  }

  @Test
  void blankRemoteErrorMetadataMapsToRemoteResponseInvalid() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(currentRuntimeState());
    when(gameSessionClient.scheduleRemoteFollowup(Mockito.any()))
        .thenReturn(
            ScheduleRemoteFollowupResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().build())
                .build());
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            Mockito.mock(ScriptWorkItemRepository.class),
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            workItem(), emittedCommand("say hello", "entity-remote", "8", "region-2", 77L, 45L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.errorCode()).isEqualTo("REMOTE_RESPONSE_INVALID");
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
  }

  @Test
  void staleScriptPinTupleCancelsBeforeLocalEnqueue() {
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(3L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-2")
                        .build())
                .build());
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    ScriptGameplayCommandHandoffService service =
        new ScriptGameplayCommandHandoffServiceImpl(
            gameSessionClient,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptHandoffEventRepository.class),
            admissionStateService(),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class));

    ScriptWorkItem item = workItem();
    ScriptGameplayCommandHandoffService.HandoffResult result =
        service.handoff(
            item, emittedCommand("say hello", "entity-1", "7", "region-1", 12L, 34L, 0));

    assertThat(result.accepted()).isFalse();
    assertThat(result.outcome())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_REGION_SCOPE_ADVANCED);
    verify(gameSessionClient, never()).enqueueAutomationCommandIfAbsent(Mockito.any());
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("CANCELED");
    assertThat(workItemCaptor.getValue().getCancelReason())
        .isEqualTo(ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED);
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
    verify(handoffEventRepository, Mockito.times(2)).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getAllValues().getLast().getHandoffReason())
        .isEqualTo("idempotency_conflict");
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
    verify(handoffEventRepository, Mockito.times(2)).save(handoffCaptor.capture());
    ScriptHandoffEvent persistedOutcome = handoffCaptor.getAllValues().getLast();
    assertThat(persistedOutcome.getSourceKind()).isEqualTo("SCHEDULE_TIMER");
    assertThat(persistedOutcome.getSourceState()).isEqualTo("SCHEDULE_DUE_CLAIMED");
    assertThat(persistedOutcome.getEmittedCommandText()).isEqualTo("say hello");
    assertThat(persistedOutcome.getHandoffOutcome()).isEqualTo("rejected");
    assertThat(persistedOutcome.getHandoffReason()).isEqualTo("runtime_scope_changed");
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
                "1", "7", "region-1", "PAUSED_FOR_ROLLBACK", 2L, "admin", "rollback", 200L));
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
                "1", "7", "region-1", "PAUSED_FOR_ROLLBACK", 1L, "admin", "pause", 100L));
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
                "1", "7", "region-1", "NORMAL", 2L, "admin", "resume", 100L));
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
    assertThat(requestCaptor.getValue().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(requestCaptor.getValue().getScriptPinEpoch()).isEqualTo(2L);
    assertThat(requestCaptor.getValue().getScriptPinControlPlaneRequestId())
        .isEqualTo("pin-request-1");
    assertThat(requestCaptor.getValue().getScriptPatchBaseVersionId()).isEqualTo(7L);
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository, Mockito.times(2)).save(handoffCaptor.capture());
    ScriptHandoffEvent persistedOutcome = handoffCaptor.getAllValues().getLast();
    assertThat(persistedOutcome.getRemoteCoordinatorId())
        .isEqualTo("remote-coordinator:workItem:99#0");
    assertThat(persistedOutcome.getRemoteFollowupId()).isEqualTo("remote-followup:workItem:99#0");
    assertThat(persistedOutcome.getTargetGameInstanceId()).isEqualTo("8");
    assertThat(persistedOutcome.getTargetRegionId()).isEqualTo("region-2");
    assertThat(persistedOutcome.getTargetRegionEpoch()).isEqualTo(77L);
    assertThat(persistedOutcome.getScriptPinEpoch()).isEqualTo(2L);
    assertThat(persistedOutcome.getScriptPinControlPlaneRequestId()).isEqualTo("pin-request-1");
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
  void remoteTargetRetainsInFlightEvidenceWhenResponseOmitsDurableIds() {
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
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
    assertThat(result.outcome()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(result.errorCode()).isEqualTo("HANDOFF_IN_FLIGHT");
    ArgumentCaptor<ScriptWorkItem> workItemCaptor = ArgumentCaptor.forClass(ScriptWorkItem.class);
    verify(workItemRepository).save(workItemCaptor.capture());
    assertThat(workItemCaptor.getValue().getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(audit.getFinalStage()).isNull();
    assertThat(audit.getFinalOutcome()).isNull();
    assertThat(audit.getFinalReason()).isNull();
    ArgumentCaptor<ScriptHandoffEvent> handoffCaptor =
        ArgumentCaptor.forClass(ScriptHandoffEvent.class);
    verify(handoffEventRepository).save(handoffCaptor.capture());
    assertThat(handoffCaptor.getValue().getRemoteCoordinatorId()).isBlank();
    assertThat(handoffCaptor.getValue().getRemoteFollowupId()).isBlank();
    assertThat(handoffCaptor.getValue().getHandoffOutcome()).isEqualTo("handoff_in_flight");
    assertThat(handoffCaptor.getValue().getHandoffReason()).isEqualTo("handoff_in_flight");
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
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPinnedScriptPatchVersion("patch-1")
                        .setScriptPinEpoch(2L)
                        .setPinnedScriptPatchBaseVersionId(7L)
                        .setScriptPatchPinnedControlPlaneRequestId("pin-request-1"))
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
    verify(handoffEventRepository, Mockito.times(2)).save(handoffCaptor.capture());
    ScriptHandoffEvent persistedOutcome = handoffCaptor.getAllValues().getLast();
    assertThat(persistedOutcome.getWorldSlug()).isBlank();
    assertThat(persistedOutcome.getRealmSlug()).isBlank();
    assertThat(persistedOutcome.getPointerVersion()).isBlank();
  }

  private static ScriptHandoffEvent handoffRow(
      Long id, int rowVersion, String outcome, String commandId) {
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setId(id);
    event.setRowVersion(rowVersion);
    event.setHandoffOutcome(outcome);
    event.setGameSessionCommandId(commandId);
    return event;
  }

  private static ScriptHandoffEvent acceptedHandoffEvent(
      ScriptWorkItem item,
      ScriptGameplayCommandHandoffService.EmittedCommand command,
      String outcome) {
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("she-work-item-" + item.getId() + "-command-" + command.ordinal());
    event.setTenantId(item.getTenantId());
    event.setGameInstanceId(item.getGameInstanceId());
    event.setScriptPatchVersion(item.getScriptPatchVersion());
    event.setScriptPinEpoch(item.getScriptPinEpoch());
    event.setScriptPinControlPlaneRequestId(item.getScriptPinControlPlaneRequestId());
    event.setScriptId(item.getScriptId());
    event.setBindingId(item.getBindingId());
    event.setPluginId(item.getPluginId());
    event.setPluginVersionId(item.getPluginVersionId());
    event.setPluginActivationEpoch(item.getPluginActivationEpoch());
    event.setLifecycleRevision(item.getLifecycleRevision());
    event.setWorkItemId(item.getId());
    event.setCommandOrdinal(command.ordinal());
    event.setAutomationDispatchId("workItem:" + item.getId() + "#" + command.ordinal());
    event.setTargetGameInstanceId(command.targetGameInstanceId());
    event.setTargetRegionId(command.targetRegionId());
    event.setTargetRegionEpoch(command.targetRegionEpoch());
    event.setTargetEntityId(command.targetEntityId());
    event.setPlayableStateScope(item.getPlayableStateScope());
    event.setWorldSlug(item.getWorldSlug());
    event.setRealmSlug(item.getRealmSlug());
    event.setPointerVersion(item.getPointerVersion());
    event.setSourceKind(item.getSourceKind());
    event.setSourceState(item.getSourceState());
    event.setSourceOrdinal(item.getSourceOrdinal());
    event.setSourceDueTickId(item.getSourceDueTickId());
    event.setSourceDueAtMs(item.getSourceDueAtMs());
    event.setEmittedCommandText(command.commandText());
    event.setHandoffOutcome(outcome);
    return event;
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

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final List<String> operations;
    private final List<Integer> propagations = new ArrayList<>();
    private int commitCount;

    private RecordingTransactionManager(List<String> operations) {
      this.operations = operations;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      int propagation =
          definition == null
              ? TransactionDefinition.PROPAGATION_REQUIRED
              : definition.getPropagationBehavior();
      propagations.add(propagation);
      operations.add("tx-begin:" + propagation);
      TransactionSynchronizationManager.setActualTransactionActive(
          propagation != TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      operations.add("tx-commit-" + ++commitCount);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      operations.add("tx-rollback");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private List<Integer> propagations() {
      return propagations;
    }
  }

  private static final class FailingIntentCommitTransactionManager
      implements PlatformTransactionManager {
    private final List<Integer> propagations = new ArrayList<>();
    private boolean failedIntentCommit;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      int propagation =
          definition == null
              ? TransactionDefinition.PROPAGATION_REQUIRED
              : definition.getPropagationBehavior();
      propagations.add(propagation);
      TransactionSynchronizationManager.setActualTransactionActive(
          propagation != TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
      return new TaggedTransactionStatus(
          propagation == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void commit(TransactionStatus status) {
      if (status instanceof TaggedTransactionStatus tagged
          && tagged.requiresNew()
          && !failedIntentCommit) {
        failedIntentCommit = true;
        TransactionSynchronizationManager.setActualTransactionActive(false);
        throw new IllegalStateException("intent commit unavailable");
      }
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private List<Integer> propagations() {
      return propagations;
    }
  }

  private static final class TaggedTransactionStatus extends SimpleTransactionStatus {
    private final boolean requiresNew;

    private TaggedTransactionStatus(boolean requiresNew) {
      this.requiresNew = requiresNew;
    }

    private boolean requiresNew() {
      return requiresNew;
    }
  }

  private static GetGameInstanceRuntimeStateResponse currentRuntimeState() {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setRuntimeState(
            GameInstanceRuntimeState.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("7")
                .setRegionId("region-1")
                .setRegionEpoch(12L)
                .setPinnedScriptPatchVersion("patch-1")
                .setScriptPinEpoch(2L)
                .setPinnedScriptPatchBaseVersionId(7L)
                .setScriptPatchPinnedControlPlaneRequestId("pin-request-1")
                .build())
        .build();
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
    item.setBindingId("binding-1");
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
    item.setScriptPatchBaseVersionId(7L);
    item.setScriptPinEpoch(2L);
    item.setScriptPinControlPlaneRequestId("pin-request-1");
    item.setAdmissionEpoch(1L);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
