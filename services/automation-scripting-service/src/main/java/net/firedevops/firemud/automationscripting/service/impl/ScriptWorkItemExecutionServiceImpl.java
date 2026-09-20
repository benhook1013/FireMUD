package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
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
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScriptWorkItemExecutionServiceImpl implements ScriptWorkItemExecutionService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(ScriptWorkItemExecutionServiceImpl.class);
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String STAGE_ADMISSION = "ADMISSION";
  private static final String STAGE_DSL_EVAL = "DSL_EVAL";
  private static final String OUTCOME_HANDOFF_ACCEPTED = "handoff_accepted";
  private static final String OUTCOME_SANDBOX_ERROR = "sandbox_error";
  private static final String OUTCOME_AUTHORITY_UNAVAILABLE_EXHAUSTED =
      "authority_unavailable_exhausted";
  private static final Duration AUTHORITY_UNAVAILABLE_RETRY_DELAY = Duration.ofSeconds(30);
  private static final Duration AUTHORITY_UNAVAILABLE_MAX_AGE = Duration.ofMinutes(10);
  private static final int MAX_AUTHORITY_UNAVAILABLE_OUTCOMES = 10;
  private static final String PRIORITY_HIGH = "high";
  private static final String PRIORITY_NORMAL = "normal";
  private static final String PRIORITY_BACKGROUND = "background";
  private static final String PRIORITY_UNKNOWN = "unknown";
  private static final String EVENT_ON_LOAD = "onLoad";
  private static final String SERVICE_NAME = "automation-scripting-service";

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
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final PluginRuntimeStateRepository pluginRuntimeStateRepository;
  private final TransactionTemplate transactionTemplate;

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
        null,
        null,
        null);
  }

  /** Compatibility constructor for focused tests that provide readiness collaborators. */
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
        readinessCapacityService,
        null,
        null);
  }

  /** Compatibility constructor for focused tests that provide readiness collaborators. */
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
        readinessCapacityService,
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
      MeterRegistry meterRegistry,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      PluginRuntimeStateRepository pluginRuntimeStateRepository,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
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
        readinessCapacityService,
        gameSessionControlPlaneClient,
        pluginRuntimeStateRepository,
        transactionManager == null ? null : new TransactionTemplate(transactionManager));
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
      AutomationQueueService automationQueueService,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      ScriptReadinessCapacityService readinessCapacityService,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      PluginRuntimeStateRepository pluginRuntimeStateRepository) {
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
        readinessCapacityService,
        gameSessionControlPlaneClient,
        pluginRuntimeStateRepository,
        null);
  }

  private ScriptWorkItemExecutionServiceImpl(
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
      ScriptReadinessCapacityService readinessCapacityService,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      PluginRuntimeStateRepository pluginRuntimeStateRepository,
      TransactionTemplate transactionTemplate) {
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
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.pluginRuntimeStateRepository = pluginRuntimeStateRepository;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public ExecutionBatchResult processPendingWorkItems(int maxItems) {
    List<ScriptWorkItem> claimed = claimWorkItems(maxItems);
    int completedCount = 0;
    int failedCount = 0;
    for (ScriptWorkItem workItem : claimed) {
      try {
        if (processOneWorkItemInTransaction(workItem)) {
          completedCount++;
        } else {
          failedCount++;
        }
      } catch (RuntimeException ex) {
        // The claim is already EVALUATING, but this item lacks enough stage/effect evidence for
        // an automatic disposition. Leave it unresolved until the target lease/recovery owner
        // exists, while ensuring one bad item cannot abort the rest of this claimed batch.
        LOGGER.error(
            "Unexpected exception processing claimed script work item id={}; leaving unresolved",
            workItem.getId(),
            ex);
        failedCount++;
      }
    }
    return new ExecutionBatchResult(claimed.size(), completedCount, failedCount);
  }

  /** Keeps each durable work-item transition and its remote calls in its own transaction. */
  private boolean processOneWorkItemInTransaction(ScriptWorkItem workItem) {
    if (transactionTemplate == null) {
      // Focused unit-test constructors intentionally have no transaction manager.
      return processClaimedWorkItem(workItem);
    }
    Boolean result = transactionTemplate.execute(status -> processClaimedWorkItem(workItem));
    return Boolean.TRUE.equals(result);
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
    if (authorityUnavailableRetryExpired(workItem, now)) {
      deadLetter(
          workItem,
          STAGE_ADMISSION,
          OUTCOME_AUTHORITY_UNAVAILABLE_EXHAUSTED,
          OUTCOME_AUTHORITY_UNAVAILABLE_EXHAUSTED,
          now);
      return false;
    }
    String fenceFailure = validateCurrentExecutionFences(workItem);
    if (fenceFailure != null) {
      if (isTerminalFenceFailure(fenceFailure)) {
        cancel(workItem, STAGE_ADMISSION, "stale_execution_fenced", fenceFailure, now);
      } else {
        requeueAfterAuthorityUnavailable(workItem, fenceFailure, now);
      }
      return false;
    }
    clearAuthorityUnavailableRetryState(workItem);
    if (!workItem.isDryRun()
        && ScriptQuotaClasses.consumesLiveTenantBudget(workItem.getQuotaClass())
        && !tenantBudgetService.tryReserve(
            workItem.getTenantId(), normalizeReservationTier(workItem.getPriorityTag()))) {
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

  /**
   * Final Automation-side admission fence. Queue claims are intentionally not authority: the exact
   * owner tuple is reread immediately before definition evaluation and any gameplay handoff.
   * Missing pre-fence evidence is rejected, which keeps legacy rows fail closed.
   */
  private String validateCurrentExecutionFences(ScriptWorkItem workItem) {
    if (isOnLoad(workItem)) {
      // Tenant-readiness onLoad is pre-instance-pin work and has no runtime pin fence.
      return null;
    }
    String localFenceFailure =
        ScriptWorkItemFenceEvaluationSupport.validateRuntimeIdentity(workItem);
    if (localFenceFailure != null) {
      return localFenceFailure;
    }
    if (gameSessionControlPlaneClient == null) {
      // Compatibility constructors are used by isolated evaluator tests. The Spring production
      // constructor always supplies both authority collaborators and therefore takes the strict
      // branch below; keeping this seam local avoids making unit fixtures model remote authority.
      return pluginRuntimeStateRepository == null
          ? null
          : "script_pin_authority_collaborator_unavailable";
    }
    final GetGameInstanceRuntimeStateResponse runtime;
    runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getRegionId());
    String runtimeFailure =
        ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(workItem, runtime);
    if (runtimeFailure != null) {
      return runtimeFailure;
    }
    String pluginFailure =
        ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem);
    if (pluginFailure != null) {
      return pluginFailure;
    }
    if (ScriptWorkItemFenceEvaluationSupport.normalize(workItem.getPluginId()).isBlank()) {
      return null;
    }
    if (pluginRuntimeStateRepository == null) {
      return "plugin_lifecycle_collaborator_unavailable";
    }
    String pluginId = ScriptWorkItemFenceEvaluationSupport.normalize(workItem.getPluginId());
    Optional<PluginRuntimeState> plugin;
    try {
      plugin =
          pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
              workItem.getTenantId(), workItem.getGameInstanceId(), pluginId);
    } catch (DataAccessException ex) {
      if (isRepositoryUnavailable(ex)) {
        return "plugin_lifecycle_collaborator_unavailable";
      }
      return "plugin_lifecycle_evidence_unavailable";
    }
    PluginState pluginState = null;
    String activePluginVersionId = "";
    long pluginActivationEpoch = 0L;
    long lifecycleRevision = 0L;
    if (plugin.isPresent()) {
      var state = plugin.orElseThrow();
      activePluginVersionId = state.getActivePluginVersionId();
      if (state.getPluginState() != null) {
        try {
          pluginState = PluginState.valueOf(state.getPluginState());
        } catch (IllegalArgumentException ex) {
          pluginState = null;
        }
      }
      pluginActivationEpoch = state.getPluginActivationEpoch();
      lifecycleRevision = state.getLifecycleRevision();
    }
    return ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
        workItem, activePluginVersionId, pluginState, pluginActivationEpoch, lifecycleRevision);
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

    JsonNode definitionRoot;
    try {
      definitionRoot = parseDefinitionRoot(definition.get().getDefinition());
    } catch (IllegalArgumentException ex) {
      deadLetter(workItem, STAGE_DSL_EVAL, "definition_invalid", ex.getMessage(), now);
      return false;
    }

    if (isOnLoad(workItem)) {
      if (declaresCommands(definitionRoot, workItem.getEventType())) {
        deadLetter(
            workItem, STAGE_DSL_EVAL, "definition_invalid", "onload_commands_not_allowed", now);
        return false;
      }
    }

    List<ScriptGameplayCommandHandoffService.EmittedCommand> commands;
    try {
      commands = parseCommands(definitionRoot, workItem);
    } catch (IllegalArgumentException ex) {
      String reason = ex.getMessage();
      String outcome =
          "command_count_exceeded".equals(reason)
                  || "per_entity_command_limit_exceeded".equals(reason)
              ? OUTCOME_SANDBOX_ERROR
              : "definition_invalid";
      deadLetter(workItem, STAGE_DSL_EVAL, outcome, reason, now);
      return false;
    }

    if (commands.isEmpty() || workItem.isDryRun()) {
      markTerminalSuccess(
          workItem,
          STAGE_DSL_EVAL,
          workItem.isDryRun() ? "dry_run_completed" : "completed_no_commands",
          workItem.isDryRun()
              ? "dry_run_no_handoff"
              : isOnLoad(workItem) ? "ready_for_tenant" : "script_emitted_no_commands",
          now);
      return true;
    }

    // DSL evaluation is not the effect boundary. Re-read every authoritative execution fence
    // after evaluation and immediately before the first gameplay handoff so a repin, plugin ABA,
    // lifecycle transition, or policy revocation that won during evaluation cannot emit effects.
    String handoffFenceFailure = validateCurrentExecutionFences(workItem);
    if (handoffFenceFailure != null) {
      if (isTerminalFenceFailure(handoffFenceFailure)) {
        cancel(workItem, STAGE_ADMISSION, "stale_execution_fenced", handoffFenceFailure, now);
      } else {
        requeueAfterAuthorityUnavailable(workItem, handoffFenceFailure, now);
      }
      return false;
    }
    clearAuthorityUnavailableRetryState(workItem);

    ScriptGameplayCommandHandoffService.HandoffResult firstRejectedHandoff = null;
    handoffService.beginAggregateFanout(workItem);
    try {
      for (ScriptGameplayCommandHandoffService.EmittedCommand command : commands) {
        ScriptGameplayCommandHandoffService.HandoffResult result =
            handoffService.handoff(workItem, command);
        if (!result.accepted()
            && (firstRejectedHandoff == null
                || (ScriptHandoffOutcomeSupport.isRetryable(firstRejectedHandoff)
                    && !ScriptHandoffOutcomeSupport.isRetryable(result)))) {
          // Continue through the complete emitted set. The handoff owner records one durable
          // attempted child per call; returning here would leave later siblings without either an
          // attempted disposition or an explicit unattempted terminal record.
          firstRejectedHandoff = result;
        }
      }
    } finally {
      handoffService.endAggregateFanout(workItem);
    }
    if (firstRejectedHandoff != null) {
      if (ScriptHandoffOutcomeSupport.isRetryable(firstRejectedHandoff)) {
        requeueAfterRetryableHandoff(workItem);
        return false;
      }
      recordTerminalHandoffOutcome(workItem, firstRejectedHandoff);
      return false;
    }
    markTerminalSuccess(
        workItem,
        ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
        OUTCOME_HANDOFF_ACCEPTED,
        "commands_handed_off",
        now);
    return true;
  }

  private void requeueAfterRetryableHandoff(ScriptWorkItem workItem) {
    workItem.setStatus("PENDING_EVALUATION");
    workItem.setUpdatedAt(Instant.now());
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, workItem, LOGGER);
  }

  private void requeueAfterAuthorityUnavailable(
      ScriptWorkItem workItem, String reason, Instant now) {
    Instant firstUnavailableAt = workItem.getAuthorityUnavailableSince();
    if (firstUnavailableAt == null) {
      firstUnavailableAt = now;
    }
    int priorCount = Math.max(0, workItem.getAuthorityUnavailableCount());
    int nextCount =
        priorCount >= MAX_AUTHORITY_UNAVAILABLE_OUTCOMES
            ? MAX_AUTHORITY_UNAVAILABLE_OUTCOMES
            : priorCount + 1;
    workItem.setAuthorityUnavailableSince(firstUnavailableAt);
    workItem.setAuthorityUnavailableCount(nextCount);
    if (nextCount >= MAX_AUTHORITY_UNAVAILABLE_OUTCOMES
        || !now.isBefore(firstUnavailableAt.plus(AUTHORITY_UNAVAILABLE_MAX_AGE))) {
      deadLetter(
          workItem,
          STAGE_ADMISSION,
          OUTCOME_AUTHORITY_UNAVAILABLE_EXHAUSTED,
          OUTCOME_AUTHORITY_UNAVAILABLE_EXHAUSTED,
          now);
      return;
    }
    workItem.setStatus("PENDING_EVALUATION");
    workItem.setCancelReason(reason);
    workItem.setNextEligibleAt(now.plus(AUTHORITY_UNAVAILABLE_RETRY_DELAY));
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    // Do not immediately republish while the authority is unavailable: that creates a hot loop
    // against the same unavailable dependency. The durable pending row is picked up by the next
    // executor scan once authority recovers.
  }

  /** Clears an outage budget after a fresh fence read succeeds. */
  private void clearAuthorityUnavailableRetryState(ScriptWorkItem workItem) {
    if (workItem.getAuthorityUnavailableSince() == null
        && workItem.getAuthorityUnavailableCount() == 0
        && workItem.getNextEligibleAt() == null) {
      return;
    }
    workItem.setAuthorityUnavailableSince(null);
    workItem.setAuthorityUnavailableCount(0);
    workItem.setNextEligibleAt(null);
    workItemRepository.save(workItem);
  }

  private static boolean isRepositoryUnavailable(DataAccessException exception) {
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof SQLTransientException
          || cause instanceof SQLRecoverableException
          || cause instanceof ConnectException
          || cause instanceof SocketTimeoutException) {
        return true;
      }
      if (cause instanceof SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
          return true;
        }
      }
      cause = cause.getCause();
    }
    return false;
  }

  private static boolean authorityUnavailableRetryExpired(ScriptWorkItem workItem, Instant now) {
    Instant firstUnavailableAt = workItem.getAuthorityUnavailableSince();
    return firstUnavailableAt != null
        && !now.isBefore(firstUnavailableAt.plus(AUTHORITY_UNAVAILABLE_MAX_AGE));
  }

  private static boolean isTerminalFenceFailure(String reason) {
    return switch (reason) {
      case "script_patch_version_mismatch",
          "script_pin_epoch_mismatch",
          "script_pin_epoch_unavailable",
          "runtime_scope_missing",
          "runtime_scope_changed",
          "plugin_disabled",
          "plugin_version_mismatch",
          "plugin_activation_epoch_mismatch",
          "plugin_binding_mismatch",
          "plugin_lifecycle_revision_mismatch",
          "plugin_lifecycle_evidence_unavailable" ->
          true;
      default -> false;
    };
  }

  private static long requireWorkItemId(ScriptWorkItem workItem) {
    if (workItem.getId() == null) {
      throw new IllegalArgumentException("work_item_id is required for dry-run capacity");
    }
    return workItem.getId();
  }

  private List<ScriptGameplayCommandHandoffService.EmittedCommand> parseCommands(
      JsonNode root, ScriptWorkItem workItem) {
    JsonNode commandsNode = selectCommandsNode(root, workItem.getEventType());
    if (!commandsNode.isArray()) {
      return List.of();
    }
    Map<String, String> variables = templateVariables(workItem);
    List<ScriptGameplayCommandHandoffService.EmittedCommand> commands = new ArrayList<>();
    int maxCommandsPerRun = outputProperties.getMaxCommandsPerRun();
    int maxCommandsPerEntity = outputProperties.getMaxCommandsPerEntityPerTrigger();
    Map<String, Integer> commandsPerEntity = new LinkedHashMap<>();
    int ordinal = 0;
    for (JsonNode node : commandsNode) {
      if (!commandNodeEnabled(node, variables)) {
        continue;
      }
      if (commands.size() >= maxCommandsPerRun) {
        throw new IllegalArgumentException("command_count_exceeded");
      }
      String commandText = renderedCommandText(node, variables);
      if (commandText.isBlank()) {
        throw new IllegalArgumentException("command_text_blank");
      }
      boolean requiresSoloTick = ScriptCommandMetadataSupport.requiresSoloTick(node);
      long dueTickId = ScriptCommandMetadataSupport.dueTickId(node);
      for (String targetEntityId :
          targetEntityIds(
              node,
              variables,
              workItem,
              maxCommandsPerRun - commands.size(),
              commandsPerEntity,
              maxCommandsPerEntity)) {
        commands.add(
            new ScriptGameplayCommandHandoffService.EmittedCommand(
                commandText,
                targetEntityId,
                renderedTargetGameInstanceId(node, variables, workItem),
                renderedTargetRegionId(node, variables, workItem),
                renderedTargetRegionEpoch(node, variables, workItem),
                requiresSoloTick,
                dueTickId,
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

  private boolean declaresCommands(JsonNode root, String eventType) {
    JsonNode commandsNode = selectCommandsNode(root, eventType);
    return commandsNode.isArray() && !commandsNode.isEmpty();
  }

  private JsonNode parseDefinitionRoot(String definition) {
    if (definition == null || definition.isBlank()) {
      throw new IllegalArgumentException("definition_json_invalid");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(definition);
    } catch (JacksonException ex) {
      throw new IllegalArgumentException("definition_json_invalid", ex);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("definition_json_invalid");
    }
    validateDefinitionStructure(root);
    return root;
  }

  private static void validateDefinitionStructure(JsonNode root) {
    if (root.has("emitCommands") && !root.path("emitCommands").isArray()) {
      throw new IllegalArgumentException("definition_json_invalid");
    }

    if (!root.has("eventHandlers")) {
      return;
    }
    JsonNode eventHandlers = root.path("eventHandlers");
    if (!eventHandlers.isObject()) {
      throw new IllegalArgumentException("definition_json_invalid");
    }
    var handlers = eventHandlers.properties().iterator();
    while (handlers.hasNext()) {
      JsonNode handler = handlers.next().getValue();
      if (!handler.isObject()
          || (handler.has("emitCommands") && !handler.path("emitCommands").isArray())) {
        throw new IllegalArgumentException("definition_json_invalid");
      }
    }
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
      JsonNode node,
      Map<String, String> variables,
      ScriptWorkItem workItem,
      int maxTargets,
      Map<String, Integer> commandsPerEntity,
      int maxCommandsPerEntity) {
    JsonNode targetEntityIdsNode = node.get("targetEntityIds");
    if (targetEntityIdsNode != null) {
      if (!targetEntityIdsNode.isArray()) {
        throw new IllegalArgumentException("target_entity_ids_invalid");
      }
      List<String> renderedTargetIds = new ArrayList<>();
      Map<String, Integer> expandedCounts = new LinkedHashMap<>();
      for (JsonNode targetNode : targetEntityIdsNode) {
        if (renderedTargetIds.size() >= maxTargets) {
          throw new IllegalArgumentException("command_count_exceeded");
        }
        if (!targetNode.isTextual()) {
          throw new IllegalArgumentException("target_entity_ids_invalid");
        }
        String renderedTargetId = render(targetNode.asText(), variables).trim();
        if (renderedTargetId.isBlank()) {
          throw new IllegalArgumentException("target_entity_id_blank");
        }
        int expandedCount = expandedCounts.merge(renderedTargetId, 1, Integer::sum);
        int entityCount = commandsPerEntity.getOrDefault(renderedTargetId, 0);
        if (entityCount + expandedCount > maxCommandsPerEntity) {
          throw new IllegalArgumentException("per_entity_command_limit_exceeded");
        }
        renderedTargetIds.add(renderedTargetId);
      }
      for (Map.Entry<String, Integer> entry : expandedCounts.entrySet()) {
        commandsPerEntity.merge(entry.getKey(), entry.getValue(), Integer::sum);
      }
      if (renderedTargetIds.isEmpty()) {
        throw new IllegalArgumentException("target_entity_ids_empty");
      }
      return List.copyOf(renderedTargetIds);
    }
    JsonNode targetEntityIdNode = node.get("targetEntityId");
    String targetEntityId =
        render(
                targetEntityIdNode == null
                    ? workItem.getEntityId()
                    : requiredTextNode(targetEntityIdNode, "target_entity_id_invalid"),
                variables)
            .trim();
    if (targetEntityId.isBlank()) {
      throw new IllegalArgumentException("target_entity_id_blank");
    }
    int entityCount = commandsPerEntity.getOrDefault(targetEntityId, 0);
    if (entityCount >= maxCommandsPerEntity) {
      throw new IllegalArgumentException("per_entity_command_limit_exceeded");
    }
    commandsPerEntity.put(targetEntityId, entityCount + 1);
    return List.of(targetEntityId);
  }

  private static String renderedTargetGameInstanceId(
      JsonNode node, Map<String, String> variables, ScriptWorkItem workItem) {
    JsonNode targetGameInstanceIdNode = node.get("targetGameInstanceId");
    String value =
        render(
                targetGameInstanceIdNode == null
                    ? workItem.getGameInstanceId()
                    : requiredTextNode(targetGameInstanceIdNode, "target_game_instance_id_invalid"),
                variables)
            .trim();
    if (value.isBlank()) {
      throw new IllegalArgumentException("target_game_instance_id_blank");
    }
    return value;
  }

  private static String renderedTargetRegionId(
      JsonNode node, Map<String, String> variables, ScriptWorkItem workItem) {
    JsonNode targetRegionIdNode = node.get("targetRegionId");
    String value =
        render(
                targetRegionIdNode == null
                    ? workItem.getRegionId()
                    : requiredTextNode(targetRegionIdNode, "target_region_id_invalid"),
                variables)
            .trim();
    if (value.isBlank()) {
      throw new IllegalArgumentException("target_region_id_blank");
    }
    return value;
  }

  private static String requiredTextNode(JsonNode node, String invalidReason) {
    if (!node.isTextual()) {
      throw new IllegalArgumentException(invalidReason);
    }
    return node.asText();
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
    if (!STATUS_DEAD_LETTERED.equals(workItem.getStatus())) {
      workItem.setFailureGeneration(Math.addExact(workItem.getFailureGeneration(), 1L));
    }
    workItem.setStatus(STATUS_DEAD_LETTERED);
    workItem.setCancelReason(reason);
    workItem.setNextEligibleAt(null);
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
    workItem.setNextEligibleAt(null);
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
    workItem.setNextEligibleAt(null);
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

  private void recordTerminalHandoffOutcome(
      ScriptWorkItem workItem, ScriptGameplayCommandHandoffService.HandoffResult result) {
    Instant now = Instant.now();
    if (ScriptHandoffOutcomeSupport.isRollbackFence(result)) {
      cancel(
          workItem,
          ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
          ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
          ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED,
          now);
      return;
    }
    if (ScriptHandoffOutcomeSupport.isRuntimeScopeFence(result)) {
      cancel(
          workItem,
          ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
          ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
          ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED,
          now);
      return;
    }
    if (ScriptHandoffOutcomeSupport.isAdmissionPause(result)) {
      cancel(
          workItem,
          ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
          ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
          ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED,
          now);
      return;
    }
    deadLetter(
        workItem,
        ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
        ScriptHandoffOutcomeSupport.OUTCOME_INFRASTRUCTURE_ERROR,
        ScriptHandoffOutcomeSupport.canonicalInfrastructureReason(result),
        now);
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
    if (workItem.isDryRun()) {
      return;
    }
    String priority = normalizePriorityTag(workItem.getPriorityTag());
    String sourceClass = normalizeSourceClass(workItem.getEventType());
    Runnable increment =
        () ->
            meterRegistry
                .counter(
                    "automation_script_work_item_outcomes_total",
                    "service",
                    SERVICE_NAME,
                    "stage",
                    stage,
                    "outcome",
                    outcome,
                    "priority",
                    priority,
                    "source_class",
                    sourceClass)
                .increment();
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      increment.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            increment.run();
          }
        });
  }

  private static String normalizeSourceClass(String eventType) {
    if (eventType == null || eventType.isBlank()) {
      return "unknown";
    }
    // The live registry currently materializes only the built-in event set. Do not infer a
    // service/other class from sourceKind or sourceService until the accepted registry category
    // is durably carried on the work item.
    return switch (eventType) {
      case "onCommand", "onSpawn", "onEnterRegion", "onLeaveRegion" -> "gameplay";
      case "onInterval", "onTimerExpire" -> "scheduler";
      case EVENT_ON_LOAD -> "readiness";
      default -> "unknown";
    };
  }

  private static String normalizePriorityTag(String value) {
    if (value == null || value.isBlank()) {
      return PRIORITY_UNKNOWN;
    }
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case PRIORITY_HIGH, PRIORITY_NORMAL, PRIORITY_BACKGROUND -> normalized;
      default -> PRIORITY_UNKNOWN;
    };
  }

  private static String normalizeReservationTier(String value) {
    String normalized = normalizePriorityTag(value);
    return PRIORITY_UNKNOWN.equals(normalized) ? PRIORITY_NORMAL : normalized;
  }
}
