package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemExecutionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class ScriptWorkItemExecutionServiceImplTest {
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
                  true, "ENQUEUED", "auto-1", "");
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
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().commandText()).isEqualTo("say LOOK from entity-1");
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("success");
    assertThat(audit.getFinalReason()).isEqualTo("commands_handed_off");
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
    item.setPayloadJson("{\"commandName\":\"LOOK\"}");
    item.setStatus("EVALUATING");
    item.setCreatedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
