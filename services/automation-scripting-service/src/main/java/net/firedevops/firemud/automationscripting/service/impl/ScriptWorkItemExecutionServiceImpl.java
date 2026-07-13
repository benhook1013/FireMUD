package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
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
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are retained internally by Spring services.")
public class ScriptWorkItemExecutionServiceImpl implements ScriptWorkItemExecutionService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(ScriptWorkItemExecutionServiceImpl.class);
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String STAGE_ADMISSION = "ADMISSION";
  private static final String STAGE_DSL_EVAL = "DSL_EVAL";
  private static final String STAGE_TICK_HANDOFF = "TICK_HANDOFF";
  private static final String PRIORITY_HIGH = "high";
  private static final String PRIORITY_NORMAL = "normal";
  private static final String PRIORITY_BACKGROUND = "background";
  private static final String EVENT_ON_LOAD = "onLoad";

  private final ScriptWorkItemService workItemService;
  private final ScriptDefinitionRepository scriptDefinitionRepository;
  private final ScriptGameplayCommandHandoffService handoffService;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;
  private final ScriptPatchReadinessProjectionService readinessProjectionService;
  private final ScriptOutputProperties outputProperties;
  private final ScriptTenantBudgetService tenantBudgetService;
  private final ScriptDryRunCapacityService dryRunCapacityService;
  private final ScriptReadinessCapacityService readinessCapacityService;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final AutomationQueueService automationQueueService;

  public ScriptWorkItemExecutionServiceImpl(
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      ScriptOutputProperties outputProperties,
      ScriptTenantBudgetService tenantBudgetService,
      ScriptDryRunCapacityService dryRunCapacityService,
      ObjectMapper objectMapper) {
    this(
        workItemService,
        scriptDefinitionRepository,
        handoffService,
        workItemRepository,
        auditRepository,
        rolloutProjectionService,
        outputProperties,
        tenantBudgetService,
        dryRunCapacityService,
        objectMapper,
        new SimpleMeterRegistry(),
        null,
        null,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ScriptWorkItemExecutionServiceImpl(
      AutomationQueueService automationQueueService,
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      ScriptOutputProperties outputProperties,
      ScriptTenantBudgetService tenantBudgetService,
      ScriptDryRunCapacityService dryRunCapacityService,
      ScriptReadinessCapacityService readinessCapacityService,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this(
        workItemService,
        scriptDefinitionRepository,
        handoffService,
        workItemRepository,
        auditRepository,
        rolloutProjectionService,
        outputProperties,
        tenantBudgetService,
        dryRunCapacityService,
        objectMapper,
        meterRegistry,
        automationQueueService,
        readinessProjectionService,
        readinessCapacityService);
  }

  public ScriptWorkItemExecutionServiceImpl(
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      ScriptOutputProperties outputProperties,
      ScriptTenantBudgetService tenantBudgetService,
      ScriptDryRunCapacityService dryRunCapacityService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this(
        workItemService,
        scriptDefinitionRepository,
        handoffService,
        workItemRepository,
        auditRepository,
        rolloutProjectionService,
        outputProperties,
        tenantBudgetService,
        dryRunCapacityService,
        objectMapper,
        meterRegistry,
        null,
        null,
        null);
  }

  public ScriptWorkItemExecutionServiceImpl(
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      ScriptOutputProperties outputProperties,
      ScriptTenantBudgetService tenantBudgetService,
      ScriptDryRunCapacityService dryRunCapacityService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      AutomationQueueService automationQueueService) {
    this(
        workItemService,
        scriptDefinitionRepository,
        handoffService,
        workItemRepository,
        auditRepository,
        rolloutProjectionService,
        outputProperties,
        tenantBudgetService,
        dryRunCapacityService,
        objectMapper,
        meterRegistry,
        automationQueueService,
        null,
        null);
  }

  public ScriptWorkItemExecutionServiceImpl(
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      ScriptOutputProperties outputProperties,
      ScriptTenantBudgetService tenantBudgetService,
      ScriptDryRunCapacityService dryRunCapacityService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      AutomationQueueService automationQueueService,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      ScriptReadinessCapacityService readinessCapacityService) {
    this.workItemService = workItemService;
    this.scriptDefinitionRepository = scriptDefinitionRepository;
    this.handoffService = handoffService;
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.rolloutProjectionService = rolloutProjectionService;
    this.outputProperties = outputProperties;
    this.tenantBudgetService = tenantBudgetService;
    this.dryRunCapacityService = dryRunCapacityService;
    this.readinessCapacityService = readinessCapacityService;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.automationQueueService = automationQueueService;
    this.readinessProjectionService = readinessProjectionService;
  }

  @Override
  @Transactional
  public ExecutionBatchResult processPendingWorkItems(int maxItems) {
    List<ScriptWorkItem> claimed = claimWorkItems(maxItems);
    int completedCount = 0;
    int failedCount = 0;
    for (ScriptWorkItem workItem : claimed) {
      if (processClaimedWorkItem(workItem)) {
        completedCount++;
      } else {
        failedCount++;
      }
    }
    return new ExecutionBatchResult(claimed.size(), completedCount, failedCount);
  }

  private List<ScriptWorkItem> claimWorkItems(int maxItems) {
    if (automationQueueService == null) {
      return workItemService.claimPendingForEvaluation(maxItems);
    }
    List<AutomationQueueWorkItemPointer> pointers;
    try {
      pointers =
          automationQueueService.drainIndexedWorkItemPointers(Math.max(1, maxItems * 2), maxItems);
    } catch (RuntimeException ex) {
      LOGGER.warn("Automation queue pointer discovery failed; falling back to durable scan", ex);
      meterRegistry.counter("script_outbox_queue_pointer_discovery_failed_total").increment();
      return workItemService.claimPendingForEvaluation(maxItems);
    }
    List<ScriptWorkItem> queueClaimed =
        workItemService.claimPendingForEvaluation(
            pointers.stream().map(AutomationQueueWorkItemPointer::outboxWorkItemId).toList(),
            maxItems);
    if (queueClaimed.size() >= maxItems) {
      return queueClaimed;
    }
    List<ScriptWorkItem> fallbackClaimed =
        workItemService.claimPendingForEvaluation(maxItems - queueClaimed.size());
    if (fallbackClaimed.isEmpty()) {
      return queueClaimed;
    }
    List<ScriptWorkItem> combined = new ArrayList<>(queueClaimed.size() + fallbackClaimed.size());
    combined.addAll(queueClaimed);
    combined.addAll(fallbackClaimed);
    return List.copyOf(combined);
  }

  private boolean processClaimedWorkItem(ScriptWorkItem workItem) {
    Instant now = Instant.now();
    if (!workItem.isDryRun()
        && ScriptQuotaClasses.consumesLiveTenantBudget(workItem.getQuotaClass())
        && !tenantBudgetService.tryReserve(
            workItem.getTenantId(), normalizePriorityTag(workItem.getPriorityTag()))) {
      cancel(workItem, STAGE_ADMISSION, "tenant_budget_exceeded", "tenant_budget_exceeded", now);
      return false;
    }
    if (!workItem.isDryRun()
        && ScriptQuotaClasses.usesPublishReadinessCapacity(workItem.getQuotaClass())
        && readinessCapacityService != null) {
      Optional<ScriptReadinessCapacityService.Reservation> reservation =
          readinessCapacityService.tryReserve(workItem.getTenantId(), requireWorkItemId(workItem));
      if (reservation.isEmpty()) {
        cancel(workItem, STAGE_ADMISSION, "quota_denied", "onload_budget_exceeded", now);
        return false;
      }
      try {
        return evaluateClaimedWorkItem(workItem, now);
      } finally {
        readinessCapacityService.release(reservation.get());
      }
    }
    if (workItem.isDryRun()) {
      Optional<ScriptDryRunCapacityService.Reservation> reservation =
          dryRunCapacityService.tryReserve(workItem.getTenantId(), requireWorkItemId(workItem));
      if (reservation.isEmpty()) {
        cancel(workItem, STAGE_ADMISSION, "quota_denied", "dry_run_capacity_exhausted", now);
        return false;
      }
      try {
        return evaluateClaimedWorkItem(workItem, now);
      } finally {
        dryRunCapacityService.release(reservation.get());
      }
    }

    return evaluateClaimedWorkItem(workItem, now);
  }

  private boolean evaluateClaimedWorkItem(ScriptWorkItem workItem, Instant now) {
    final long tenantId;
    try {
      tenantId = parseTenantId(workItem);
    } catch (IllegalArgumentException ex) {
      deadLetter(workItem, STAGE_DSL_EVAL, "definition_invalid", "tenant_id_invalid", now);
      return false;
    }
    Optional<ScriptDefinition> definition =
        scriptDefinitionRepository.findByTenantIdAndScriptVersionAndName(
            tenantId, workItem.getScriptPatchVersion(), workItem.getScriptId());
    if (definition.isEmpty()) {
      deadLetter(workItem, STAGE_DSL_EVAL, "definition_missing", "script_definition_missing", now);
      return false;
    }

    if (isOnLoad(workItem)
        && declaresCommands(definition.get().getDefinition(), workItem.getEventType())) {
      deadLetter(
          workItem, STAGE_DSL_EVAL, "definition_invalid", "onload_commands_not_allowed", now);
      return false;
    }

    List<ScriptGameplayCommandHandoffService.EmittedCommand> commands;
    try {
      commands = parseCommands(definition.get().getDefinition(), workItem);
    } catch (IllegalArgumentException ex) {
      deadLetter(workItem, STAGE_DSL_EVAL, "definition_invalid", ex.getMessage(), now);
      return false;
    }

    if (commands.size() > outputProperties.getMaxCommandsPerRun()) {
      deadLetter(workItem, STAGE_DSL_EVAL, "command_count_exceeded", "command_count_exceeded", now);
      return false;
    }
    if (exceedsPerEntityCommandLimit(commands)) {
      deadLetter(
          workItem,
          STAGE_DSL_EVAL,
          "per_entity_command_limit_exceeded",
          "per_entity_command_limit_exceeded",
          now);
      return false;
    }

    if (commands.isEmpty() || workItem.isDryRun()) {
      markTerminalSuccess(
          workItem,
          STAGE_DSL_EVAL,
          workItem.isDryRun()
              ? "dry_run_completed"
              : isOnLoad(workItem) ? "readiness_success" : "no_commands_emitted",
          workItem.isDryRun()
              ? "dry_run_no_handoff"
              : isOnLoad(workItem) ? "ready_for_tenant" : "script_emitted_no_commands",
          now);
      return true;
    }

    for (ScriptGameplayCommandHandoffService.EmittedCommand command : commands) {
      ScriptGameplayCommandHandoffService.HandoffResult result =
          handoffService.handoff(workItem, command);
      if (!result.accepted()) {
        return false;
      }
    }
    markTerminalSuccess(
        workItem, STAGE_TICK_HANDOFF, "success", "commands_handed_off", Instant.now());
    return true;
  }

  private static long requireWorkItemId(ScriptWorkItem workItem) {
    if (workItem.getId() == null) {
      throw new IllegalArgumentException("work_item_id is required for dry-run capacity");
    }
    return workItem.getId();
  }

  private List<ScriptGameplayCommandHandoffService.EmittedCommand> parseCommands(
      String definition, ScriptWorkItem workItem) {
    JsonNode root;
    try {
      root = objectMapper.readTree(definition);
    } catch (Exception ex) {
      throw new IllegalArgumentException("definition_json_invalid");
    }
    JsonNode commandsNode = selectCommandsNode(root, workItem.getEventType());
    if (!commandsNode.isArray()) {
      return List.of();
    }
    Map<String, String> variables = templateVariables(workItem);
    List<ScriptGameplayCommandHandoffService.EmittedCommand> commands = new ArrayList<>();
    int ordinal = 0;
    for (JsonNode node : commandsNode) {
      if (!commandNodeEnabled(node, variables)) {
        continue;
      }
      String commandText = renderedCommandText(node, variables);
      if (commandText.isBlank()) {
        throw new IllegalArgumentException("command_text_blank");
      }
      for (String targetEntityId : targetEntityIds(node, variables, workItem)) {
        commands.add(
            new ScriptGameplayCommandHandoffService.EmittedCommand(
                commandText,
                targetEntityId,
                renderedTargetGameInstanceId(node, variables, workItem),
                renderedTargetRegionId(node, variables, workItem),
                renderedTargetRegionEpoch(node, variables, workItem),
                node.path("requiresSoloTick").asBoolean(false),
                Math.max(0L, node.path("dueTickId").asLong(0L)),
                ordinal++));
      }
    }
    return List.copyOf(commands);
  }

  private static boolean commandNodeEnabled(JsonNode node, Map<String, String> variables) {
    if (node.isTextual()) {
      return true;
    }
    JsonNode whenNode = node.path("when");
    if (!whenNode.isMissingNode() && !whenNode.isNull() && !conditionsMatch(whenNode, variables)) {
      return false;
    }
    JsonNode unlessNode = node.path("unless");
    return unlessNode.isMissingNode()
        || unlessNode.isNull()
        || !conditionsMatch(unlessNode, variables);
  }

  private static boolean conditionsMatch(JsonNode conditionsNode, Map<String, String> variables) {
    if (!conditionsNode.isObject()) {
      throw new IllegalArgumentException("command_condition_invalid");
    }
    var fields = conditionsNode.properties().iterator();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> condition = fields.next();
      if (!condition.getValue().isValueNode()) {
        throw new IllegalArgumentException("command_condition_invalid");
      }
      String expected = render(condition.getValue().asText(""), variables);
      String actual = variables.getOrDefault(condition.getKey(), "");
      if (!actual.equals(expected)) {
        return false;
      }
    }
    return true;
  }

  private JsonNode selectCommandsNode(JsonNode root, String eventType) {
    JsonNode eventHandlers = root.path("eventHandlers");
    if (eventHandlers.isObject()) {
      JsonNode eventNode = eventHandlers.path(eventType);
      if (eventNode.has("emitCommands")) {
        return eventNode.path("emitCommands");
      }
    }
    return root.path("emitCommands");
  }

  private boolean declaresCommands(String definition, String eventType) {
    JsonNode root;
    try {
      root = objectMapper.readTree(definition);
    } catch (Exception ex) {
      throw new IllegalArgumentException("definition_json_invalid");
    }
    JsonNode commandsNode = selectCommandsNode(root, eventType);
    return commandsNode.isArray() && !commandsNode.isEmpty();
  }

  private Map<String, String> templateVariables(ScriptWorkItem workItem) {
    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("tenantId", workItem.getTenantId());
    variables.put("gameInstanceId", workItem.getGameInstanceId());
    variables.put("regionId", workItem.getRegionId());
    variables.put("regionEpoch", Long.toString(workItem.getRegionEpoch()));
    variables.put("entityId", workItem.getEntityId());
    variables.put("worldSlug", workItem.getWorldSlug() == null ? "" : workItem.getWorldSlug());
    variables.put("realmSlug", workItem.getRealmSlug() == null ? "" : workItem.getRealmSlug());
    variables.put(
        "pointerVersion", workItem.getPointerVersion() == null ? "" : workItem.getPointerVersion());
    variables.put("scriptId", workItem.getScriptId());
    variables.put("eventType", workItem.getEventType());
    variables.put("scriptPatchVersion", workItem.getScriptPatchVersion());
    variables.put("scriptEventId", workItem.getScriptEventId());
    if (workItem.getPayloadJson() == null || workItem.getPayloadJson().isBlank()) {
      return variables;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload =
          objectMapper.readValue(workItem.getPayloadJson(), LinkedHashMap.class);
      payload.forEach(
          (key, value) -> {
            if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
              variables.put("payload." + key, value == null ? "" : value.toString());
            }
          });
    } catch (Exception ex) {
      throw new IllegalArgumentException("payload_json_invalid");
    }
    return variables;
  }

  private static String commandText(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    return node.path("commandText").asText("");
  }

  private static String renderedCommandText(JsonNode node, Map<String, String> variables) {
    if (node.isTextual() || node.hasNonNull("commandText")) {
      return render(commandText(node), variables);
    }
    if (!node.has("commandAlias")) {
      return "";
    }
    String commandAlias = render(node.path("commandAlias").asText(""), variables).trim();
    if (commandAlias.isBlank()) {
      throw new IllegalArgumentException("command_alias_blank");
    }
    List<String> arguments = renderedArguments(node.path("arguments"), variables);
    if (arguments.isEmpty()) {
      return commandAlias;
    }
    return commandAlias + " " + String.join(" ", arguments);
  }

  private static List<String> renderedArguments(
      JsonNode argumentsNode, Map<String, String> variables) {
    if (argumentsNode == null || argumentsNode.isMissingNode() || argumentsNode.isNull()) {
      return List.of();
    }
    if (argumentsNode.isTextual()) {
      String rendered = render(argumentsNode.asText(), variables).trim();
      if (rendered.isBlank()) {
        return List.of();
      }
      return List.of(rendered);
    }
    if (!argumentsNode.isArray()) {
      throw new IllegalArgumentException("command_arguments_invalid");
    }
    List<String> rendered = new ArrayList<>();
    for (JsonNode argumentNode : argumentsNode) {
      if (!argumentNode.isValueNode()) {
        throw new IllegalArgumentException("command_argument_invalid");
      }
      String value = render(argumentNode.asText(""), variables).trim();
      if (value.isBlank()) {
        throw new IllegalArgumentException("command_argument_blank");
      }
      rendered.add(value);
    }
    return List.copyOf(rendered);
  }

  private static List<String> targetEntityIds(
      JsonNode node, Map<String, String> variables, ScriptWorkItem workItem) {
    JsonNode targetEntityIdsNode = node.path("targetEntityIds");
    if (!targetEntityIdsNode.isMissingNode() && !targetEntityIdsNode.isNull()) {
      if (!targetEntityIdsNode.isArray()) {
        throw new IllegalArgumentException("target_entity_ids_invalid");
      }
      List<String> renderedTargetIds = new ArrayList<>();
      for (JsonNode targetNode : targetEntityIdsNode) {
        if (!targetNode.isValueNode()) {
          throw new IllegalArgumentException("target_entity_ids_invalid");
        }
        String renderedTargetId = render(targetNode.asText(""), variables).trim();
        if (renderedTargetId.isBlank()) {
          throw new IllegalArgumentException("target_entity_id_blank");
        }
        renderedTargetIds.add(renderedTargetId);
      }
      if (renderedTargetIds.isEmpty()) {
        throw new IllegalArgumentException("target_entity_ids_empty");
      }
      return List.copyOf(renderedTargetIds);
    }
    String targetEntityId =
        render(node.path("targetEntityId").asText(workItem.getEntityId()), variables);
    if (targetEntityId.isBlank()) {
      throw new IllegalArgumentException("target_entity_id_blank");
    }
    return List.of(targetEntityId);
  }

  private static String renderedTargetGameInstanceId(
      JsonNode node, Map<String, String> variables, ScriptWorkItem workItem) {
    String value =
        render(node.path("targetGameInstanceId").asText(workItem.getGameInstanceId()), variables)
            .trim();
    if (value.isBlank()) {
      throw new IllegalArgumentException("target_game_instance_id_blank");
    }
    return value;
  }

  private static String renderedTargetRegionId(
      JsonNode node, Map<String, String> variables, ScriptWorkItem workItem) {
    String value =
        render(node.path("targetRegionId").asText(workItem.getRegionId()), variables).trim();
    if (value.isBlank()) {
      throw new IllegalArgumentException("target_region_id_blank");
    }
    return value;
  }

  private static long renderedTargetRegionEpoch(
      JsonNode node, Map<String, String> variables, ScriptWorkItem workItem) {
    if (!node.has("targetRegionEpoch")) {
      if (workItem.getRegionEpoch() <= 0) {
        throw new IllegalArgumentException("target_region_epoch_invalid");
      }
      return workItem.getRegionEpoch();
    }
    String rendered = render(node.path("targetRegionEpoch").asText(""), variables).trim();
    if (rendered.isBlank()) {
      throw new IllegalArgumentException("target_region_epoch_blank");
    }
    try {
      long value = Long.parseLong(rendered);
      if (value <= 0) {
        throw new IllegalArgumentException("target_region_epoch_invalid");
      }
      return value;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("target_region_epoch_invalid");
    }
  }

  private boolean exceedsPerEntityCommandLimit(
      List<ScriptGameplayCommandHandoffService.EmittedCommand> commands) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (ScriptGameplayCommandHandoffService.EmittedCommand command : commands) {
      int count = counts.merge(command.targetEntityId(), 1, Integer::sum);
      if (count > outputProperties.getMaxCommandsPerEntityPerTrigger()) {
        return true;
      }
    }
    return false;
  }

  private static String render(String template, Map<String, String> variables) {
    String rendered = template;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
      rendered =
          rendered.replace(
              "{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
    }
    return rendered;
  }

  private void deadLetter(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    workItem.setStatus(STATUS_DEAD_LETTERED);
    workItem.setCancelReason(reason);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(workItem.getId(), stage, outcome, reason, now);
    refreshPatchReadiness(workItem);
    recordOutcome(workItem, stage, outcome);
  }

  private void cancel(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason(reason);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(workItem.getId(), stage, outcome, reason, now);
    refreshPatchReadiness(workItem);
    recordOutcome(workItem, stage, outcome);
  }

  private void markTerminalSuccess(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    workItem.setStatus(STATUS_HANDED_OFF);
    workItem.setCancelReason(null);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(workItem.getId(), stage, outcome, reason, now);
    refreshPatchReadiness(workItem);
    recordOutcome(workItem, stage, outcome);
  }

  private void updateAudit(
      Long workItemId, String stage, String outcome, String reason, Instant now) {
    auditRepository
        .findByWorkItemId(workItemId)
        .ifPresent(
            audit -> {
              audit.setFinalStage(stage);
              audit.setFinalOutcome(outcome);
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  private static long parseTenantId(ScriptWorkItem workItem) {
    return RequestIdValidation.requirePositiveLong(workItem.getTenantId(), "tenant_id");
  }

  private static boolean isOnLoad(ScriptWorkItem workItem) {
    return EVENT_ON_LOAD.equals(workItem.getEventType());
  }

  private void refreshPatchReadiness(ScriptWorkItem workItem) {
    if (readinessProjectionService != null && isOnLoad(workItem)) {
      readinessProjectionService.refreshFromOnLoadWorkItems(
          workItem.getTenantId(), workItem.getScriptPatchVersion());
    }
  }

  private void recordOutcome(ScriptWorkItem workItem, String stage, String outcome) {
    meterRegistry
        .counter(
            "automation_script_work_item_outcomes_total",
            "stage",
            stage,
            "outcome",
            outcome,
            "dryRun",
            Boolean.toString(workItem.isDryRun()),
            "priorityTag",
            normalizePriorityTag(workItem.getPriorityTag()),
            "sourceKind",
            normalizeSourceKind(workItem.getSourceKind()),
            "sourceService",
            normalizeSourceService(workItem.getSourceService()),
            "eventType",
            normalizeEventType(workItem.getEventType()))
        .increment();
  }

  private static String normalizeSourceKind(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String normalizeSourceService(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String normalizeEventType(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String normalizePriorityTag(String value) {
    if (value == null || value.isBlank()) {
      return PRIORITY_NORMAL;
    }
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case PRIORITY_HIGH, PRIORITY_NORMAL, PRIORITY_BACKGROUND -> normalized;
      default -> PRIORITY_NORMAL;
    };
  }
}
