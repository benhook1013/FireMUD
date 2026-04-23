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
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunCapacityService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptTenantBudgetService;
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
    ScriptWorkItem item = workItem();
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
            denyingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalReason()).isEqualTo("tenant_budget_exceeded");
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
            Optional.of(new ScriptDryRunCapacityService.Reservation("1", 99L, "lease-token")));
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
    item.setPayloadJson("{\"commandName\":\"LOOK\"}");
    item.setStatus("EVALUATING");
    item.setCreatedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }
}
