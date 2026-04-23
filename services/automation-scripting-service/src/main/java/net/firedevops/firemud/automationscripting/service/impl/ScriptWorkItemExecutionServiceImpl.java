package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemExecutionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptQuotaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are retained internally by Spring services.")
public class ScriptWorkItemExecutionServiceImpl implements ScriptWorkItemExecutionService {
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String STAGE_DSL_EVAL = "DSL_EVAL";
  private static final String STAGE_TICK_HANDOFF = "TICK_HANDOFF";

  private final ScriptWorkItemService workItemService;
  private final ScriptDefinitionRepository scriptDefinitionRepository;
  private final ScriptGameplayCommandHandoffService handoffService;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final ScriptQuotaService quotaService;
  private final ScriptOutputProperties outputProperties;
  private final ObjectMapper objectMapper;

  public ScriptWorkItemExecutionServiceImpl(
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository scriptDefinitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptQuotaService quotaService,
      ScriptOutputProperties outputProperties,
      ObjectMapper objectMapper) {
    this.workItemService = workItemService;
    this.scriptDefinitionRepository = scriptDefinitionRepository;
    this.handoffService = handoffService;
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.quotaService = quotaService;
    this.outputProperties = outputProperties;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public ExecutionBatchResult processPendingWorkItems(int maxItems) {
    List<ScriptWorkItem> claimed = workItemService.claimPendingForEvaluation(maxItems);
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

  private boolean processClaimedWorkItem(ScriptWorkItem workItem) {
    Instant now = Instant.now();
    if (!quotaService.tryAcquire(workItem.getTenantId(), workItem.getScriptId())) {
      deadLetter(workItem, STAGE_DSL_EVAL, "quota_denied", "script_quota_denied", now);
      return false;
    }
    Optional<ScriptDefinition> definition =
        scriptDefinitionRepository.findByTenantIdAndScriptVersionAndName(
            parseTenantId(workItem), workItem.getScriptPatchVersion(), workItem.getScriptId());
    if (definition.isEmpty()) {
      deadLetter(workItem, STAGE_DSL_EVAL, "definition_missing", "script_definition_missing", now);
      return false;
    }

    List<ScriptGameplayCommandHandoffService.EmittedCommand> commands;
    try {
      commands = parseCommands(definition.get().getDefinition(), workItem);
    } catch (IllegalArgumentException ex) {
      deadLetter(workItem, STAGE_DSL_EVAL, "definition_invalid", ex.getMessage(), now);
      return false;
    }

    if (commands.size() > outputProperties.getMaxCommandsPerRun()
        || commands.size() > outputProperties.getMaxCommandsPerEntityPerTrigger()) {
      deadLetter(workItem, STAGE_DSL_EVAL, "command_count_exceeded", "command_count_exceeded", now);
      return false;
    }

    if (commands.isEmpty() || workItem.isDryRun()) {
      markTerminalSuccess(
          workItem,
          STAGE_DSL_EVAL,
          workItem.isDryRun() ? "dry_run_completed" : "no_commands_emitted",
          workItem.isDryRun() ? "dry_run_no_handoff" : "script_emitted_no_commands",
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
      String commandText = render(commandText(node), variables);
      if (commandText.isBlank()) {
        throw new IllegalArgumentException("command_text_blank");
      }
      commands.add(
          new ScriptGameplayCommandHandoffService.EmittedCommand(
              commandText,
              node.path("requiresSoloTick").asBoolean(false),
              Math.max(0L, node.path("dueTickId").asLong(0L)),
              ordinal++));
    }
    return List.copyOf(commands);
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

  private Map<String, String> templateVariables(ScriptWorkItem workItem) {
    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("tenantId", workItem.getTenantId());
    variables.put("gameInstanceId", workItem.getGameInstanceId());
    variables.put("regionId", workItem.getRegionId());
    variables.put("regionEpoch", Long.toString(workItem.getRegionEpoch()));
    variables.put("entityId", workItem.getEntityId());
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
    updateAudit(workItem.getId(), stage, outcome, reason, now);
  }

  private void markTerminalSuccess(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    workItem.setStatus(STATUS_HANDED_OFF);
    workItem.setCancelReason(null);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    updateAudit(workItem.getId(), stage, outcome, reason, now);
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
    try {
      return Long.parseLong(workItem.getTenantId());
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("tenant_id must be numeric for script definition lookup");
    }
  }
}
