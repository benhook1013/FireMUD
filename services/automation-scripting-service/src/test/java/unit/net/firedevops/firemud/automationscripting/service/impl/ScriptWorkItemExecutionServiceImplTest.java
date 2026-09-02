package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueWorkItemPointer;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemExecutionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunCapacityService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptReadinessCapacityService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptTenantBudgetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class ScriptWorkItemExecutionServiceImplTest {
  @Test
  void claimsQueueIndexedWorkItemsBeforeFallingBackToDurableScan() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem indexed = workItem();
    indexed.setId(99L);
    ScriptWorkItem fallback = workItem();
    fallback.setId(100L);
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(automationQueueService.drainIndexedWorkItemPointers(20, 10))
        .thenReturn(
            List.of(
                new AutomationQueueWorkItemPointer(1, 99L, "instance-1", "patch-1", "event-1")));
    when(workItemService.claimPendingForEvaluation(List.of(99L), 10)).thenReturn(List.of(indexed));
    when(workItemService.claimPendingForEvaluation(9)).thenReturn(List.of(fallback));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(Mockito.anyLong()))
        .thenAnswer(invocation -> Optional.of(new ScriptEventAudit()));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            automationQueueService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(2);
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(auditRepository, Mockito.times(2)).save(auditCaptor.capture());
    assertThat(auditCaptor.getAllValues())
        .allSatisfy(
            audit -> {
              assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
              assertThat(audit.getFinalOutcome()).isEqualTo("completed_no_commands");
              assertThat(audit.getFinalReason()).isEqualTo("script_emitted_no_commands");
            });
    Mockito.verifyNoInteractions(handoffService);
    verify(workItemService).claimPendingForEvaluation(List.of(99L), 10);
    verify(workItemService).claimPendingForEvaluation(9);
  }

  @Test
  void processesClaimedWorkItemAndHandsOffRenderedCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "eventHandlers": {
            "onCommand": {
              "emitCommands": [
                {
                  "commandText": "say {{payload.commandName}} from {{entityId}}"
                }
              ]
            }
          }
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-1", "", "", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().commandText()).isEqualTo("say LOOK from entity-1");
    assertThat(commandCaptor.getValue().targetEntityId()).isEqualTo("entity-1");
    assertThat(commandCaptor.getValue().targetGameInstanceId()).isEqualTo("7");
    assertThat(commandCaptor.getValue().targetRegionId()).isEqualTo("region-1");
    assertThat(commandCaptor.getValue().targetRegionEpoch()).isEqualTo(12L);
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
    assertThat(audit.getFinalReason()).isEqualTo("commands_handed_off");
  }

  @Test
  void retryableHandoffRequeuesAndPublishesPointerAfterCommit() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\"}]}");
    List<String> operations = new ArrayList<>();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                false, "REMOTE_REJECTED", "", "", "", "QUEUE_UNAVAILABLE"));
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
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            rolloutProjectionService,
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            automationQueueService);

    TransactionSynchronizationManager.initSynchronization();
    try {
      ScriptWorkItemExecutionService.ExecutionBatchResult result =
          service.processPendingWorkItems(10);

      assertThat(result.failedCount()).isEqualTo(1);
      assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
      assertThat(operations).containsExactly("save:PENDING_EVALUATION", "refresh");
      assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
      assertThat(operations).containsExactly("save:PENDING_EVALUATION", "refresh", "enqueue");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @ParameterizedTest(name = "accepts command metadata {0}")
  @CsvSource({
    "'{}', false, 0",
    "'{\"requiresSoloTick\":false}', false, 0",
    "'{\"requiresSoloTick\":true}', true, 0",
    "'{\"dueTickId\":0}', false, 0",
    "'{\"dueTickId\":42}', false, 42"
  })
  void acceptsTypedOptionalCommandMetadata(
      String metadataJson, boolean expectedRequiresSoloTick, long expectedDueTickId) {
    String metadata = metadataJson.substring(1, metadataJson.length() - 1);
    String definitionJson =
        "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\""
            + (metadata.isBlank() ? "" : "," + metadata)
            + "}]}";
    ExecutionFixture fixture = executeDefinition(definitionJson, new ScriptOutputProperties());

    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(fixture.handoffService()).handoff(Mockito.eq(fixture.item()), commandCaptor.capture());
    assertThat(commandCaptor.getValue().requiresSoloTick()).isEqualTo(expectedRequiresSoloTick);
    assertThat(commandCaptor.getValue().dueTickId()).isEqualTo(expectedDueTickId);
  }

  @Test
  void legacyHandoffAuditTagsAreNormalizedBeforeTerminalMetric() {
    ExecutionFixture fixture =
        executeDefinitionWithRejectedHandoff("DEAD_LETTERED", "handoff_failed", "REMOTE_REJECTED");

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "TICK_HANDOFF")
                .tag("outcome", "infrastructure_error")
                .tag("priority", "normal")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "HANDOFF")
                .tag("outcome", "handoff_failed")
                .counter())
        .isNull();
  }

  @Test
  void rejectedHandoffWithNonTerminalWorkItemFailsClosed() {
    ExecutionFixture fixture =
        executeDefinitionWithRejectedHandoff("EVALUATING", "REJECTED", "REMOTE_RESPONSE_INVALID");

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("remote_response_invalid");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("remote_response_invalid");
  }

  @Test
  void defersOutcomeMetricUntilTransactionCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);

      assertThat(meterRegistry.find("automation_script_work_item_outcomes_total").counter())
          .isNull();
      completeSynchronizations(TransactionSynchronization.STATUS_COMMITTED);
      assertThat(outcomeCounter(meterRegistry).count()).isEqualTo(1.0);
    } finally {
      clearSynchronizations();
    }
  }

  @Test
  void doesNotEmitOutcomeMetricAfterTransactionRollback() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);

      completeSynchronizations(TransactionSynchronization.STATUS_ROLLED_BACK);
      assertThat(meterRegistry.find("automation_script_work_item_outcomes_total").counter())
          .isNull();
    } finally {
      clearSynchronizations();
    }
  }

  @Test
  void retryAfterRollbackEmitsOnlyCommittedOutcomeMetric() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TransactionSynchronizationManager.initSynchronization();
    try {
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);
      completeSynchronizations(TransactionSynchronization.STATUS_ROLLED_BACK);
    } finally {
      clearSynchronizations();
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);
      completeSynchronizations(TransactionSynchronization.STATUS_COMMITTED);
    } finally {
      clearSynchronizations();
    }

    assertThat(outcomeCounter(meterRegistry).count()).isEqualTo(1.0);
  }

  @Test
  void rollbackFenceCancellationRecordsCanceledTerminalMetric() {
    ExecutionFixture fixture =
        executeDefinitionWithRejectedHandoff("CANCELED", "canceled", "rollback_epoch_advanced");

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("CANCELED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("rollback_epoch_advanced");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("canceled");
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "TICK_HANDOFF")
                .tag("outcome", "canceled")
                .tag("priority", "normal")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @Test
  void acceptedMultiCommandWorkItemRecordsOneTerminalMetric() {
    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {"commandText": "LOOK", "targetEntityId": "entity-3"}
              ]
            }
            """,
            new ScriptOutputProperties());

    assertThat(fixture.result().completedCount()).isEqualTo(1);
    verify(fixture.handoffService(), Mockito.times(2))
        .handoff(Mockito.eq(fixture.item()), Mockito.any());
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "TICK_HANDOFF")
                .tag("outcome", "handoff_accepted")
                .tag("priority", "normal")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @Test
  void terminalAggregateRejectionWinsOverLaterRetryableSibling() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {"commandText": "A", "targetEntityId": "entity-a"},
            {"commandText": "B", "targetEntityId": "entity-b"},
            {"commandText": "C", "targetEntityId": "entity-c"}
          ]
        }
        """);

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptGameplayCommandHandoffService.EmittedCommand command =
                  invocation.getArgument(1);
              if (command.ordinal() == 0) {
                return new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "RUNTIME_SCOPE_CHANGED", "", "", "", "RUNTIME_SCOPE_CHANGED");
              }
              if (command.ordinal() == 1) {
                return new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "REMOTE_REJECTED", "", "", "", "AUTHORITY_UNAVAILABLE");
              }
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-" + command.ordinal(), "", "", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isZero();
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("runtime_scope_changed");
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(3)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1, 2);
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("A", "B", "C");
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_scope_changed");
  }

  @Test
  void supportsCommandSpecificTargetEntityTemplates() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"commandName\":\"LOOK\",\"target\":\"entity-2\"}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "eventHandlers": {
            "onCommand": {
              "emitCommands": [
                {
                  "targetEntityId": "target-{{payload.target}}",
                  "commandText": "say {{payload.commandName}} for {{entityId}}"
                }
              ]
            }
          }
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-1", "", "", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().commandText()).isEqualTo("say LOOK for entity-1");
    assertThat(commandCaptor.getValue().targetEntityId()).isEqualTo("target-entity-2");
    assertThat(commandCaptor.getValue().targetGameInstanceId()).isEqualTo("7");
    assertThat(commandCaptor.getValue().targetRegionId()).isEqualTo("region-1");
    assertThat(commandCaptor.getValue().targetRegionEpoch()).isEqualTo(12L);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void supportsExplicitTargetRuntimeScopeTemplates() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson(
        "{\"targetInstance\":\"remote-7\",\"targetRegion\":\"region-9\",\"targetEpoch\":44}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "say remote hello",
              "targetGameInstanceId": "{{payload.targetInstance}}",
              "targetRegionId": "{{payload.targetRegion}}",
              "targetRegionEpoch": "{{payload.targetEpoch}}"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "REMOTE_SCHEDULED", "", "coord-1", "followup-1", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().targetGameInstanceId()).isEqualTo("remote-7");
    assertThat(commandCaptor.getValue().targetRegionId()).isEqualTo("region-9");
    assertThat(commandCaptor.getValue().targetRegionEpoch()).isEqualTo(44L);
  }

  @Test
  void supportsStructuredCommandAliasAndArguments() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"direction\":\"north\",\"thing\":\"old chest\"}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandAlias": "MOVE",
              "arguments": ["{{payload.direction}}"]
            },
            {
              "commandAlias": "LOOK",
              "arguments": "AT {{payload.thing}}"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    true,
                    "ENQUEUED",
                    "auto-"
                        + invocation
                            .<ScriptGameplayCommandHandoffService.EmittedCommand>getArgument(1)
                            .ordinal(),
                    "",
                    "",
                    ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("MOVE north", "LOOK AT old chest");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void supportsMultiTargetCommandFanOut() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"targetA\":\"entity-2\",\"targetB\":\"entity-3\"}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandAlias": "LOOK",
              "arguments": ["AT", "idol"],
              "targetEntityIds": ["{{payload.targetA}}", "{{payload.targetB}}"]
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    true,
                    "ENQUEUED",
                    "auto-"
                        + invocation
                            .<ScriptGameplayCommandHandoffService.EmittedCommand>getArgument(1)
                            .ordinal(),
                    "",
                    "",
                    ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("LOOK AT idol", "LOOK AT idol");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::targetEntityId)
        .containsExactly("entity-2", "entity-3");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void rejectsOversizedMultiTargetExpansionBeforeReadingBeyondLimitAndContinuesBatch() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(2);

    ScriptWorkItem oversized = workItem();
    ScriptWorkItem valid = workItem();
    valid.setId(100L);
    valid.setScriptId("valid-script");
    ScriptEventAudit oversizedAudit = new ScriptEventAudit();
    ScriptEventAudit validAudit = new ScriptEventAudit();
    ScriptDefinition oversizedDefinition = new ScriptDefinition();
    oversizedDefinition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "LOOK",
              "targetEntityIds": ["entity-1", "entity-2", {"unexpected": "object"}]
            }
          ]
        }
        """);
    ScriptDefinition validDefinition = new ScriptDefinition();
    validDefinition.setDefinition("{\"emitCommands\":[]}");

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(oversized, valid));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(oversizedDefinition));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "valid-script"))
        .thenReturn(Optional.of(validDefinition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(oversizedAudit));
    when(auditRepository.findByWorkItemId(100L)).thenReturn(Optional.of(validAudit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(oversized.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(oversized.getCancelReason()).isEqualTo("command_count_exceeded");
    assertThat(oversizedAudit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(oversizedAudit.getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(oversizedAudit.getFinalReason()).isEqualTo("command_count_exceeded");
    assertThat(valid.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(validAudit.getFinalOutcome()).isEqualTo("completed_no_commands");
    Mockito.verifyNoInteractions(handoffService);
  }

  @Test
  void countsDuplicateIdsWithinOneMultiTargetExpansionPerEntity() {
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(10);
    outputProperties.setMaxCommandsPerEntityPerTrigger(2);

    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {
                  "commandText": "LOOK",
                  "targetEntityIds": ["entity-2", "entity-2", "entity-2"]
                }
              ]
            }
            """,
            outputProperties);

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("per_entity_command_limit_exceeded");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("per_entity_command_limit_exceeded");
    Mockito.verifyNoInteractions(fixture.handoffService());
  }

  @Test
  void acceptsExactGlobalAndPerEntityBoundaryThenRejectsLaterCommandCount() {
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(2);
    outputProperties.setMaxCommandsPerEntityPerTrigger(2);

    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {"commandText": "LOOK", "targetEntityId": "entity-2"}
              ]
            }
            """,
            outputProperties);

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("command_count_exceeded");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("command_count_exceeded");
    Mockito.verifyNoInteractions(fixture.handoffService());
  }

  @Test
  void accumulatesPerEntityLimitAcrossSingleAndMultiTargetCommands() {
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(10);
    outputProperties.setMaxCommandsPerEntityPerTrigger(2);

    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {
                  "commandText": "LOOK",
                  "targetEntityIds": ["entity-2", "entity-3"]
                },
                {"commandText": "LOOK", "targetEntityId": "entity-2"}
              ]
            }
            """,
            outputProperties);

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("per_entity_command_limit_exceeded");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("per_entity_command_limit_exceeded");
    Mockito.verifyNoInteractions(fixture.handoffService());
  }

  @ParameterizedTest(name = "rejects malformed target fields: {1}")
  @MethodSource("malformedTargetDefinitions")
  void rejectsExplicitMalformedTargetFieldsWithoutFallbackAndContinuesBatch(
      String definition, String expectedReason) {
    assertMalformedDefinitionAndContinuesBatch(definition, "onCommand", expectedReason);
  }

  private static Stream<Arguments> malformedTargetDefinitions() {
    return Stream.of(
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":null}]}",
            "target_entity_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":{\"id\":\"entity-2\"}}]}",
            "target_entity_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\",\"targetGameInstanceId\":null}]}",
            "target_game_instance_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\",\"targetGameInstanceId\":\"game-2\",\"targetRegionId\":{\"id\":\"region-2\"}}]}",
            "target_region_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":null}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":\"entity-2\"}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[42]}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[true]}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[\"  \"]}]}",
            "target_entity_id_blank"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[]}]}",
            "target_entity_ids_empty"));
  }

  @Test
  void supportsConditionalCommandEmissionWithoutOrdinalGaps() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"shouldGreet\":true,\"muted\":false}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "say hello",
              "when": {"payload.shouldGreet": "true"}
            },
            {
              "commandText": "say hidden",
              "when": {"payload.shouldGreet": "false"}
            },
            {
              "commandText": "say audible",
              "unless": {"payload.muted": "true"}
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    true,
                    "ENQUEUED",
                    "auto-"
                        + invocation
                            .<ScriptGameplayCommandHandoffService.EmittedCommand>getArgument(1)
                            .ordinal(),
                    "",
                    "",
                    ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("say hello", "say audible");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void deadLettersWhenStructuredCommandArgumentRendersBlank() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandAlias": "MOVE",
              "arguments": [""]
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("command_argument_blank");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(audit.getFinalReason()).isEqualTo("command_argument_blank");
  }

  @Test
  void deadLettersWhenPerTargetCommandLimitIsExceeded() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(10);
    outputProperties.setMaxCommandsPerEntityPerTrigger(1);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "targetEntityId": "entity-2",
              "commandText": "say first"
            },
            {
              "targetEntityId": "entity-2",
              "commandText": "say second"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("per_entity_command_limit_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(audit.getFinalReason()).isEqualTo("per_entity_command_limit_exceeded");
  }

  @Test
  void deadLettersWhenDefinitionIsMissing() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.empty());
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("script_definition_missing");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_missing");
  }

  @Test
  void deadLettersWorkItemWithNonPositiveTenantIdBeforeDefinitionLookup() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    item.setTenantId("0");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_id_invalid");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(audit.getFinalReason()).isEqualTo("tenant_id_invalid");
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void marksOnLoadWithoutCommandsAsCompletedNoCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setGameInstanceId("");
    item.setRegionId("");
    item.setEntityId("");
    item.setEventType("onLoad");
    item.setQuotaClass(ScriptQuotaClasses.PUBLISH_READINESS);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition("{\"eventHandlers\":{\"onLoad\":{}}}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptReadinessCapacityService readinessCapacityService = allowingReadinessCapacityService();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            denyingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry,
            null,
            null,
            readinessCapacityService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    Mockito.verify(definitionRepository)
        .findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("completed_no_commands");
    assertThat(audit.getFinalReason()).isEqualTo("ready_for_tenant");
    var outcomeCounter =
        meterRegistry
            .find("automation_script_work_item_outcomes_total")
            .tag("service", "automation-scripting-service")
            .tag("stage", "DSL_EVAL")
            .tag("outcome", "completed_no_commands")
            .tag("priority", "normal")
            .tag("source_class", "readiness")
            .counter();
    assertThat(outcomeCounter).isNotNull().extracting(counter -> counter.count()).isEqualTo(1.0);
    assertThat(outcomeCounter.getId().getTags())
        .extracting(Tag::getKey)
        .containsExactlyInAnyOrder("service", "stage", "outcome", "priority", "source_class");
    Mockito.verify(readinessCapacityService)
        .release(Mockito.any(ScriptReadinessCapacityService.Reservation.class));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "{",
        "",
        "   ",
        "null",
        "[]",
        "\"scalar\"",
        "{\"emitCommands\":{}}",
        "{\"emitCommands\":null}",
        "{\"eventHandlers\":[]}",
        "{\"eventHandlers\":{\"onLoad\":[]}}",
        "{\"eventHandlers\":{\"onLoad\":{\"emitCommands\":{}}}}"
      })
  void deadLettersMalformedOnLoadAndContinuesBatch(String malformedDefinitionValue) {
    assertMalformedDefinitionAndContinuesBatch(
        malformedDefinitionValue, "onLoad", "definition_json_invalid");
  }

  @ParameterizedTest(name = "{0} rejects {1} and continues the batch")
  @CsvSource({
    "onCommand, '{', definition_json_invalid",
    "onCommand, '[]', definition_json_invalid",
    "onCommand, '{\"eventHandlers\":[]}', definition_json_invalid",
    "onCommand, '{\"emitCommands\":[{\"when\":[]}]}', command_condition_invalid",
    "onTimerExpire, '{', definition_json_invalid",
    "onTimerExpire, '[]', definition_json_invalid",
    "onTimerExpire, '{\"eventHandlers\":[]}', definition_json_invalid",
    "onTimerExpire, '{\"emitCommands\":[{\"when\":[]}]}', command_condition_invalid"
  })
  void deadLettersMalformedNonOnLoadAndContinuesBatch(
      String eventType, String malformedDefinitionValue, String expectedReason) {
    assertMalformedDefinitionAndContinuesBatch(malformedDefinitionValue, eventType, expectedReason);
  }

  @ParameterizedTest(name = "rejects malformed optional command metadata: {1}")
  @CsvSource({
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":null}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":\"true\"}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":1}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":{}}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":[]}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":null}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":\"1\"}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":{}}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":[]}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":true}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":-1}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":1.5}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":9223372036854775807}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":9223372036854775808}]}', due_tick_id_invalid"
  })
  void rejectsMalformedOptionalCommandMetadataAndContinuesBatch(
      String malformedDefinitionValue, String expectedReason) {
    assertMalformedDefinitionAndContinuesBatch(
        malformedDefinitionValue, "onCommand", expectedReason);
  }

  private void assertMalformedDefinitionAndContinuesBatch(
      String malformedDefinitionValue, String eventType, String expectedReason) {
    boolean onLoad = "onLoad".equals(eventType);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    ScriptWorkItem malformed = workItem();
    malformed.setGameInstanceId("");
    malformed.setRegionId("");
    malformed.setEntityId("");
    malformed.setEventType(eventType);
    malformed.setScriptId("malformed-script");
    malformed.setQuotaClass(
        onLoad ? ScriptQuotaClasses.PUBLISH_READINESS : ScriptQuotaClasses.STANDARD_RUNTIME);
    ScriptWorkItem valid = workItem();
    valid.setId(100L);
    valid.setGameInstanceId("");
    valid.setRegionId("");
    valid.setEntityId("");
    valid.setEventType(eventType);
    valid.setScriptId("valid-script");
    valid.setQuotaClass(
        onLoad ? ScriptQuotaClasses.PUBLISH_READINESS : ScriptQuotaClasses.STANDARD_RUNTIME);
    ScriptEventAudit malformedAudit = new ScriptEventAudit();
    ScriptEventAudit validAudit = new ScriptEventAudit();
    ScriptDefinition malformedDefinition = new ScriptDefinition();
    malformedDefinition.setDefinition(malformedDefinitionValue);
    ScriptDefinition validDefinition = new ScriptDefinition();
    validDefinition.setDefinition("{\"eventHandlers\":{\"" + eventType + "\":{}}}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(malformed, valid));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(
            1L, "patch-1", "malformed-script"))
        .thenReturn(Optional.of(malformedDefinition));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "valid-script"))
        .thenReturn(Optional.of(validDefinition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(malformedAudit));
    when(auditRepository.findByWorkItemId(100L)).thenReturn(Optional.of(validAudit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptReadinessCapacityService readinessCapacityService =
        Mockito.mock(ScriptReadinessCapacityService.class);
    when(readinessCapacityService.tryReserve("1", 99L))
        .thenReturn(
            Optional.of(
                new ScriptReadinessCapacityService.Reservation(
                    "1", 99L, "readiness-tenant-token-99", "readiness-cluster-token-99")));
    when(readinessCapacityService.tryReserve("1", 100L))
        .thenReturn(
            Optional.of(
                new ScriptReadinessCapacityService.Reservation(
                    "1", 100L, "readiness-tenant-token-100", "readiness-cluster-token-100")));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            readinessProjectionService,
            readinessCapacityService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(malformed.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(malformed.getCancelReason()).isEqualTo(expectedReason);
    assertThat(malformedAudit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(malformedAudit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(malformedAudit.getFinalReason()).isEqualTo(expectedReason);
    assertThat(valid.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(validAudit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(validAudit.getFinalOutcome()).isEqualTo("completed_no_commands");
    assertThat(validAudit.getFinalReason())
        .isEqualTo(onLoad ? "ready_for_tenant" : "script_emitted_no_commands");
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    if (onLoad) {
      Mockito.verify(readinessProjectionService, Mockito.times(2))
          .refreshFromOnLoadWorkItems("1", "patch-1");
      ArgumentCaptor<ScriptReadinessCapacityService.Reservation> releaseCaptor =
          ArgumentCaptor.forClass(ScriptReadinessCapacityService.Reservation.class);
      Mockito.verify(readinessCapacityService, Mockito.times(2)).release(releaseCaptor.capture());
      assertThat(releaseCaptor.getAllValues())
          .extracting(ScriptReadinessCapacityService.Reservation::workItemId)
          .containsExactly(99L, 100L);
    } else {
      Mockito.verifyNoInteractions(readinessProjectionService, readinessCapacityService);
    }
  }

  @Test
  void cancelsOnLoadWhenPublishReadinessCapacityIsExhausted() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setGameInstanceId("");
    item.setRegionId("");
    item.setEntityId("");
    item.setEventType("onLoad");
    item.setQuotaClass(ScriptQuotaClasses.PUBLISH_READINESS);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    ScriptReadinessCapacityService readinessCapacityService = denyingReadinessCapacityService();
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            readinessCapacityService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("onload_budget_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("quota_denied");
    assertThat(audit.getFinalReason()).isEqualTo("onload_budget_exceeded");
    Mockito.verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verify(readinessCapacityService, Mockito.never())
        .release(Mockito.any(ScriptReadinessCapacityService.Reservation.class));
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
  }

  @Test
  void rejectsOnLoadThatEmitsCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setGameInstanceId("");
    item.setRegionId("");
    item.setEntityId("");
    item.setEventType("onLoad");
    item.setQuotaClass(ScriptQuotaClasses.PUBLISH_READINESS);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        "{\"eventHandlers\":{\"onLoad\":{\"emitCommands\":[{\"commandText\":\"say nope\",\"targetEntityId\":\"entity-9\"}]}}}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("onload_commands_not_allowed");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(audit.getFinalReason()).isEqualTo("onload_commands_not_allowed");
  }

  @Test
  void dryRunDoesNotAcquireLiveScriptQuotaOrHandoffCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setDryRun(true);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "say dry run"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            denyingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("dry_run_completed");
    assertThat(audit.getFinalReason()).isEqualTo("dry_run_no_handoff");
  }

  @Test
  void dryRunDoesNotReserveLiveTenantBudget() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    ScriptDryRunCapacityService dryRunCapacityService = allowingDryRunCapacityService();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setDryRun(true);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"say dry\"}]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            dryRunCapacityService,
            new ObjectMapper(),
            meterRegistry);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    Mockito.verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verify(dryRunCapacityService)
        .release(Mockito.any(ScriptDryRunCapacityService.Reservation.class));
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("dry_run_completed");
    assertThat(audit.getFinalReason()).isEqualTo("dry_run_no_handoff");
    assertThat(meterRegistry.find("automation_script_work_item_outcomes_total").counter()).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "untrusted", "normal", "high", "background"})
  void metricNormalizesMissingOrInvalidPriorityWithoutChangingAcceptedNormal(String priorityTag) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setPriorityTag(priorityTag);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    service.processPendingWorkItems(10);

    String expectedPriority =
        switch (priorityTag == null ? "" : priorityTag) {
          case "high", "normal", "background" -> priorityTag;
          default -> "unknown";
        };
    String expectedReservationTier =
        "high".equals(priorityTag)
                || "background".equals(priorityTag)
                || "normal".equals(priorityTag)
            ? priorityTag
            : "normal";
    verify(tenantBudgetService).tryReserve("1", expectedReservationTier);
    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "DSL_EVAL")
                .tag("outcome", "completed_no_commands")
                .tag("priority", expectedPriority)
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @ParameterizedTest
  @CsvSource({
    "onCommand, gameplay",
    "onTimerExpire, scheduler",
    "onLoad, readiness",
    "future-event, unknown"
  })
  void metricKeepsSourceClassBoundedToCurrentBuiltInRegistry(
      String eventType, String expectedSourceClass) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setEventType(eventType);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    service.processPendingWorkItems(10);

    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "DSL_EVAL")
                .tag("outcome", "completed_no_commands")
                .tag("priority", "normal")
                .tag("source_class", expectedSourceClass)
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @Test
  void tenantBudgetDenialCancelsBeforeDefinitionEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setPriorityTag("high");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(tenantBudgetService.tryReserve("1", "high")).thenReturn(false);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(tenantBudgetService).tryReserve("1", "high");
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "ADMISSION")
                .tag("outcome", "tenant_budget_exceeded")
                .tag("priority", "high")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull();
  }

  @Test
  void dryRunCapacityDenialCancelsBeforeDefinitionEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setDryRun(true);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            denyingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("dry_run_capacity_exhausted");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("quota_denied");
    assertThat(audit.getFinalReason()).isEqualTo("dry_run_capacity_exhausted");
  }

  @Test
  void tenantBudgetDenialUsesPersistedScheduleTimerSourceAndPriorityTags() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setEventType("onTimerExpire");
    item.setSourceKind("SCHEDULE_TIMER");
    item.setSourceService("automation-scripting-service");
    item.setPriorityTag("background");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(tenantBudgetService.tryReserve("1", "background")).thenReturn(false);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(tenantBudgetService).tryReserve("1", "background");
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalOutcome()).isEqualTo("tenant_budget_exceeded");
    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "ADMISSION")
                .tag("outcome", "tenant_budget_exceeded")
                .tag("priority", "background")
                .tag("source_class", "scheduler")
                .counter())
        .isNotNull();
  }

  private static ExecutionFixture executeDefinition(
      String definitionJson, ScriptOutputProperties outputProperties) {
    return executeDefinition(definitionJson, outputProperties, new SimpleMeterRegistry());
  }

  private static ExecutionFixture executeDefinition(
      String definitionJson,
      ScriptOutputProperties outputProperties,
      SimpleMeterRegistry meterRegistry) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(definitionJson);

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                true, "ENQUEUED", "auto-1", "", "", ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);
    return new ExecutionFixture(
        item, audit, handoffService, meterRegistry, service.processPendingWorkItems(10));
  }

  private static io.micrometer.core.instrument.Counter outcomeCounter(
      SimpleMeterRegistry meterRegistry) {
    return meterRegistry
        .find("automation_script_work_item_outcomes_total")
        .tag("service", "automation-scripting-service")
        .tag("stage", "DSL_EVAL")
        .tag("outcome", "completed_no_commands")
        .tag("priority", "normal")
        .tag("source_class", "gameplay")
        .counter();
  }

  private static void completeSynchronizations(int completionStatus) {
    if (completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    }
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCompletion(completionStatus));
  }

  private static void clearSynchronizations() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private static ExecutionFixture executeDefinitionWithRejectedHandoff(
      String terminalStatus, String finalOutcome, String reason) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\"}]}");

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus(terminalStatus);
              item.setCancelReason(reason);
              audit.setFinalStage("HANDOFF");
              audit.setFinalOutcome(finalOutcome);
              audit.setFinalReason(reason);
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  false, reason, "", "", "", reason);
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);
    return new ExecutionFixture(
        item, audit, handoffService, meterRegistry, service.processPendingWorkItems(10));
  }

  private record ExecutionFixture(
      ScriptWorkItem item,
      ScriptEventAudit audit,
      ScriptGameplayCommandHandoffService handoffService,
      SimpleMeterRegistry meterRegistry,
      ScriptWorkItemExecutionService.ExecutionBatchResult result) {}

  private static ScriptTenantBudgetService allowingTenantBudgetService() {
    ScriptTenantBudgetService service = Mockito.mock(ScriptTenantBudgetService.class);
    when(service.tryReserve(Mockito.any(), Mockito.any())).thenReturn(true);
    return service;
  }

  private static ScriptTenantBudgetService denyingTenantBudgetService() {
    ScriptTenantBudgetService service = Mockito.mock(ScriptTenantBudgetService.class);
    when(service.tryReserve(Mockito.any(), Mockito.any())).thenReturn(false);
    return service;
  }

  private static ScriptDryRunCapacityService allowingDryRunCapacityService() {
    ScriptDryRunCapacityService service = Mockito.mock(ScriptDryRunCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong()))
        .thenReturn(
            Optional.of(
                new ScriptDryRunCapacityService.Reservation(
                    "1", 99L, "tenant-lease-token", "cluster-lease-token")));
    return service;
  }

  private static ScriptDryRunCapacityService denyingDryRunCapacityService() {
    ScriptDryRunCapacityService service = Mockito.mock(ScriptDryRunCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong())).thenReturn(Optional.empty());
    return service;
  }

  private static ScriptReadinessCapacityService allowingReadinessCapacityService() {
    ScriptReadinessCapacityService service = Mockito.mock(ScriptReadinessCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong()))
        .thenReturn(
            Optional.of(
                new ScriptReadinessCapacityService.Reservation(
                    "1", 99L, "readiness-tenant-token", "readiness-cluster-token")));
    return service;
  }

  private static ScriptReadinessCapacityService denyingReadinessCapacityService() {
    ScriptReadinessCapacityService service = Mockito.mock(ScriptReadinessCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong())).thenReturn(Optional.empty());
    return service;
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
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setQuotaClass(ScriptQuotaClasses.STANDARD_RUNTIME);
    item.setScriptPatchVersion("patch-1");
    item.setScriptEventId("event-1");
    item.setSourceKind("GAMEPLAY_EVENT");
    item.setSourceService("game-session-service");
    item.setPayloadJson("{\"commandName\":\"LOOK\"}");
    item.setStatus("EVALUATING");
    item.setCreatedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
