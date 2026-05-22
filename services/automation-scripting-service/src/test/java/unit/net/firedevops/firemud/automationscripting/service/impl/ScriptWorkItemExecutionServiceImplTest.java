package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemExecutionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunCapacityService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptTenantBudgetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
        .thenReturn(Optional.of(new ScriptEventAudit()));
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
    assertThat(audit.getFinalOutcome()).isEqualTo("success");
    assertThat(audit.getFinalReason()).isEqualTo("commands_handed_off");
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
    assertThat(audit.getFinalOutcome()).isEqualTo("success");
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
    assertThat(audit.getFinalOutcome()).isEqualTo("success");
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
    assertThat(audit.getFinalOutcome()).isEqualTo("success");
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
    assertThat(audit.getFinalOutcome()).isEqualTo("success");
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
    assertThat(audit.getFinalOutcome()).isEqualTo("per_entity_command_limit_exceeded");
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
  void marksOnLoadWithoutCommandsAsReadinessSuccess() {
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
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = new ScriptDefinition();
    definition.setDefinition("{\"eventHandlers\":{\"onLoad\":{}}}");
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
    Mockito.verify(definitionRepository)
        .findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("readiness_success");
    assertThat(audit.getFinalReason()).isEqualTo("ready_for_tenant");
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
            new ObjectMapper());

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
                .tag("stage", "ADMISSION")
                .tag("outcome", "tenant_budget_exceeded")
                .tag("dryRun", "false")
                .tag("priorityTag", "high")
                .tag("sourceService", "game-session-service")
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
    item.setScriptPatchVersion("patch-1");
    item.setScriptEventId("event-1");
    item.setSourceService("game-session-service");
    item.setPayloadJson("{\"commandName\":\"LOOK\"}");
    item.setStatus("EVALUATING");
    item.setCreatedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
