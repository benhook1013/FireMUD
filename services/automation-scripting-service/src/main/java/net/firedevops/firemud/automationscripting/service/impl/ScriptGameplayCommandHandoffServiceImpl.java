package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
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
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScriptGameplayCommandHandoffServiceImpl
    implements ScriptGameplayCommandHandoffService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(ScriptGameplayCommandHandoffServiceImpl.class);
  private static final String STATUS_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final String STATUS_PENDING_EVALUATION = "PENDING_EVALUATION";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String OUTCOME_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final String REASON_HANDOFF_IN_FLIGHT = "handoff_in_flight";
  private static final String REMOTE_LATE_RESULT_POLICY = "late_result_safe_to_ignore";

  private final GameSessionControlPlaneClient gameSessionClient;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final ScriptHandoffEventRepository handoffEventRepository;
  private final DSLContext dsl;
  private final AutomationQueueService automationQueueService;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;
  private final TransactionTemplate handoffTransactionTemplate;
  private final TransactionTemplate rpcTransactionTemplate;
  private final ThreadLocal<Set<Long>> aggregateFanouts = ThreadLocal.withInitial(HashSet::new);
  private final ThreadLocal<Map<Long, AggregateAdmissionSnapshot>> aggregateAdmissionSnapshots =
      ThreadLocal.withInitial(HashMap::new);

  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService) {
    this(
        gameSessionClient,
        workItemRepository,
        auditRepository,
        handoffEventRepository,
        (DSLContext) null,
        null,
        automationAdmissionStateService,
        rolloutProjectionService,
        null);
  }

  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      AutomationAdmissionStateRepository ignoredAdmissionStateRepository,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService) {
    this(
        gameSessionClient,
        workItemRepository,
        auditRepository,
        handoffEventRepository,
        (DSLContext) null,
        null,
        automationAdmissionStateService,
        rolloutProjectionService,
        null);
  }

  /** Legacy constructor retained for focused unit tests that do not exercise queue publication. */
  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      DSLContext dsl,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService) {
    this(
        gameSessionClient,
        workItemRepository,
        auditRepository,
        handoffEventRepository,
        dsl,
        null,
        automationAdmissionStateService,
        rolloutProjectionService,
        null);
  }

  /** Compatibility constructor for focused tests that do not provide a transaction manager. */
  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      DSLContext dsl,
      AutomationQueueService automationQueueService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService) {
    this(
        gameSessionClient,
        workItemRepository,
        auditRepository,
        handoffEventRepository,
        dsl,
        automationQueueService,
        automationAdmissionStateService,
        rolloutProjectionService,
        null);
  }

  @Autowired
  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      DSLContext dsl,
      AutomationQueueService automationQueueService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PlatformTransactionManager transactionManager) {
    this(
        gameSessionClient,
        workItemRepository,
        auditRepository,
        handoffEventRepository,
        dsl,
        automationQueueService,
        automationAdmissionStateService,
        rolloutProjectionService,
        newTransactionTemplate(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW),
        newTransactionTemplate(
            transactionManager, TransactionDefinition.PROPAGATION_NOT_SUPPORTED));
  }

  private ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      DSLContext dsl,
      AutomationQueueService automationQueueService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      TransactionTemplate handoffTransactionTemplate,
      TransactionTemplate rpcTransactionTemplate) {
    this.gameSessionClient = gameSessionClient;
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.handoffEventRepository = handoffEventRepository;
    this.dsl = dsl;
    this.automationQueueService = automationQueueService;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.rolloutProjectionService = rolloutProjectionService;
    this.handoffTransactionTemplate = handoffTransactionTemplate;
    this.rpcTransactionTemplate = rpcTransactionTemplate;
  }

  private static TransactionTemplate newTransactionTemplate(
      PlatformTransactionManager transactionManager, int propagationBehavior) {
    if (transactionManager == null) {
      return null;
    }
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(propagationBehavior);
    return template;
  }

  @Override
  public void beginAggregateFanout(ScriptWorkItem workItem) {
    requireWorkItem(workItem);
    HandoffPreflight preflight = preflight(workItem);
    executeIntentBoundary(() -> beginAggregateFanoutInTransaction(workItem, preflight));
  }

  private void beginAggregateFanoutInTransaction(
      ScriptWorkItem workItem, HandoffPreflight preflight) {
    lockAdmissionScope(workItem);
    aggregateFanouts.get().add(workItem.getId());
    try {
      String admissionFenceReason =
          handoffTransactionTemplate == null
              ? preflight.admissionFenceReason()
              : admissionFenceReason(workItem);
      aggregateAdmissionSnapshots
          .get()
          .put(
              workItem.getId(),
              new AggregateAdmissionSnapshot(admissionFenceReason, preflight.runtimeScopeStatus()));
    } catch (RuntimeException ex) {
      clearAggregateFanout(workItem.getId());
      throw ex;
    }
  }

  @Override
  public void endAggregateFanout(ScriptWorkItem workItem) {
    if (workItem == null || workItem.getId() == null) {
      return;
    }
    clearAggregateFanout(workItem.getId());
  }

  private void clearAggregateFanout(Long workItemId) {
    Set<Long> active = aggregateFanouts.get();
    active.remove(workItemId);
    Map<Long, AggregateAdmissionSnapshot> snapshots = aggregateAdmissionSnapshots.get();
    snapshots.remove(workItemId);
    if (active.isEmpty()) {
      aggregateFanouts.remove();
      aggregateAdmissionSnapshots.remove();
    }
  }

  @Override
  public HandoffResult handoff(ScriptWorkItem workItem, EmittedCommand command) {
    requireWorkItem(workItem);
    requireCommand(command);
    String dispatchId = dispatchId(workItem, command.ordinal());
    AggregateAdmissionSnapshot aggregateSnapshot =
        aggregateAdmissionSnapshots.get().get(workItem.getId());
    HandoffPreflight preflight =
        aggregateSnapshot == null ? preflight(workItem) : HandoffPreflight.from(aggregateSnapshot);
    HandoffPreparation preparation;
    try {
      preparation = executeIntentTransaction(workItem, command, dispatchId, preflight);
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Unable to durably prepare script handoff for workItemId={} commandOrdinal={}",
          workItem.getId(),
          command.ordinal(),
          ex);
      return retryablePreparationResult(ex);
    }
    if (preparation.result() != null) {
      return preparation.result();
    }
    HandoffResult downstreamResult;
    try {
      downstreamResult =
          executeRpcWithoutLocalTransaction(
              () -> invokeDownstream(workItem, command, dispatchId, preparation.remoteHandoff()));
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Script handoff RPC failed for workItemId={} commandOrdinal={}; retaining in-flight evidence",
          workItem.getId(),
          command.ordinal(),
          ex);
      return reconciliationRequiredResult(ex);
    }
    if (isReconciliationRequired(downstreamResult)) {
      return downstreamResult;
    }
    try {
      return executeResponseTransaction(
          () ->
              persistDownstreamResponse(
                  workItem, command, dispatchId, downstreamResult, preparation.intentRowVersion()));
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Unable to persist script handoff response for workItemId={} commandOrdinal={}; retaining in-flight evidence",
          workItem.getId(),
          command.ordinal(),
          ex);
      return reconciliationRequiredResult(ex);
    }
  }

  private HandoffPreparation executeIntentTransaction(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffPreflight preflight) {
    if (handoffTransactionTemplate == null) {
      return prepareHandoff(workItem, command, dispatchId, preflight);
    }
    return handoffTransactionTemplate.execute(
        status -> prepareHandoff(workItem, command, dispatchId, preflight));
  }

  private void executeIntentBoundary(Runnable action) {
    if (handoffTransactionTemplate == null) {
      action.run();
      return;
    }
    handoffTransactionTemplate.execute(
        status -> {
          action.run();
          return null;
        });
  }

  private HandoffResult executeRpcWithoutLocalTransaction(RpcCall rpcCall) {
    if (rpcTransactionTemplate == null) {
      return rpcCall.invoke();
    }
    return rpcTransactionTemplate.execute(status -> rpcCall.invoke());
  }

  private HandoffResult executeResponseTransaction(ResponseCommit responseCommit) {
    if (handoffTransactionTemplate == null) {
      return responseCommit.commit();
    }
    return handoffTransactionTemplate.execute(status -> responseCommit.commit());
  }

  @FunctionalInterface
  private interface RpcCall {
    HandoffResult invoke();
  }

  @FunctionalInterface
  private interface ResponseCommit {
    HandoffResult commit();
  }

  private record HandoffPreparation(
      HandoffResult result, boolean remoteHandoff, int intentRowVersion) {
    HandoffPreparation(HandoffResult result, boolean remoteHandoff) {
      this(result, remoteHandoff, -1);
    }
  }

  private HandoffPreparation prepareHandoff(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffPreflight preflight) {
    requireWorkItem(workItem);
    requireCommand(command);
    // Every child intent owns a short local transaction. Reacquire the admission lock and reread
    // the local fence in that transaction even when aggregate fanout retained a remote runtime
    // snapshot; the aggregate snapshot is not local admission authority.
    lockAdmissionScope(workItem);
    AggregateAdmissionSnapshot aggregateSnapshot =
        aggregateAdmissionSnapshots.get().get(workItem.getId());
    String admissionFenceReason =
        aggregateSnapshot == null
            ? handoffTransactionTemplate == null
                ? preflight.admissionFenceReason()
                : admissionFenceReason(workItem)
            : admissionFenceReason(workItem);
    if (ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED.equals(admissionFenceReason)) {
      Instant now = Instant.now();
      cancelForAdmissionPause(
          workItem, command, dispatchId, now, deferAggregateTerminalization(workItem));
      return new HandoffPreparation(
          new HandoffResult(
              false,
              ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED,
              "",
              "",
              "",
              ScriptHandoffOutcomeSupport.ERROR_RUNTIME_PAUSED),
          false);
    }
    if (ScriptHandoffOutcomeSupport.REASON_AUTHORITY_UNAVAILABLE.equals(admissionFenceReason)) {
      Instant now = Instant.now();
      HandoffResult result =
          new HandoffResult(
              false,
              ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
              "",
              "",
              "",
              ScriptHandoffOutcomeSupport.ERROR_AUTHORITY_UNAVAILABLE,
              "automation admission state unavailable");
      applyOutcome(workItem, command, dispatchId, result, now);
      return new HandoffPreparation(result, false);
    }
    if (ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED.equals(admissionFenceReason)) {
      Instant now = Instant.now();
      cancelForRollbackEpochAdvance(
          workItem, command, dispatchId, now, deferAggregateTerminalization(workItem));
      return new HandoffPreparation(
          new HandoffResult(
              false, ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED, "", "", "", ""),
          false);
    }
    RuntimeRegionScopeStatus runtimeScopeStatus =
        aggregateSnapshot == null
            ? preflight.runtimeScopeStatus()
            : aggregateSnapshot.runtimeRegionScopeStatus();
    if (runtimeScopeStatus == RuntimeRegionScopeStatus.ADVANCED) {
      Instant now = Instant.now();
      cancelForRuntimeRegionScopeAdvance(
          workItem, command, dispatchId, now, deferAggregateTerminalization(workItem));
      return new HandoffPreparation(
          new HandoffResult(
              false,
              ScriptHandoffOutcomeSupport.REASON_RUNTIME_REGION_SCOPE_ADVANCED,
              "",
              "",
              "",
              ""),
          false);
    }
    boolean remoteHandoff = requiresRemoteHandoff(workItem, command);
    if (runtimeScopeStatus != RuntimeRegionScopeStatus.CURRENT) {
      Instant now = Instant.now();
      String errorCode =
          runtimeScopeStatus == RuntimeRegionScopeStatus.UNAVAILABLE
              ? ScriptHandoffOutcomeSupport.ERROR_AUTHORITY_UNAVAILABLE
              : ScriptHandoffOutcomeSupport.ERROR_REMOTE_RESPONSE_INVALID;
      String message =
          runtimeScopeStatus == RuntimeRegionScopeStatus.UNAVAILABLE
              ? "runtime owner authority unavailable"
              : "runtime owner response incomplete";
      HandoffResult result =
          new HandoffResult(
              false,
              ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
              "",
              "",
              "",
              errorCode,
              message);
      applyOutcome(workItem, command, dispatchId, result, now);
      return new HandoffPreparation(result, remoteHandoff);
    }
    if (remoteHandoff && !hasRepresentableDeadline(workItem, command)) {
      Instant now = Instant.now();
      HandoffResult result =
          new HandoffResult(
              false,
              ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
              "",
              "",
              "",
              ScriptHandoffOutcomeSupport.ERROR_INVALID_ARGUMENT);
      applyOutcome(workItem, command, dispatchId, result, now);
      return new HandoffPreparation(result, remoteHandoff);
    }
    Instant now = Instant.now();
    Optional<ScriptHandoffEvent> existingHandoff =
        handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal(
            workItem.getTenantId(), workItem.getId(), command.ordinal());
    if (existingHandoff.isPresent()) {
      ScriptHandoffEvent existing = existingHandoff.orElseThrow();
      if (!handoffIdentityMatches(existing, workItem, command, dispatchId)) {
        return new HandoffPreparation(
            new HandoffResult(
                false,
                ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
                "",
                "",
                "",
                ScriptHandoffOutcomeSupport.REASON_IDEMPOTENCY_CONFLICT,
                "existing handoff child identity does not match request"),
            remoteHandoff);
      }
      if (isAcceptedHandoff(existing)) {
        return new HandoffPreparation(handoffResult(existing), remoteHandoff);
      }
    }
    workItem.setStatus(STATUS_HANDOFF_IN_FLIGHT);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);

    ScriptHandoffEvent intent = appendHandoffIntent(workItem, command, dispatchId, now);
    return new HandoffPreparation(null, remoteHandoff, intent.getRowVersion());
  }

  private HandoffResult invokeDownstream(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId, boolean remoteHandoff) {
    if (remoteHandoff) {
      ScopeValidationResult scopeValidation = validateRemoteHandoffScope(workItem, command);
      if (scopeValidation != null) {
        return new HandoffResult(
            false,
            ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
            "",
            "",
            "",
            scopeValidation.errorCode(),
            scopeValidation.message());
      }
      ScheduleRemoteFollowupResponse remoteResponse =
          gameSessionClient.scheduleRemoteFollowup(
              toRemoteScheduleRequest(workItem, command, dispatchId));
      return remoteHandoffResult(remoteResponse);
    }
    EnqueueAutomationCommandIfAbsentResponse response =
        gameSessionClient.enqueueAutomationCommandIfAbsent(
            toRequest(workItem, command, dispatchId));
    if (response == null) {
      return reconciliationRequiredResult("local owner response was null");
    }
    return localHandoffResult(response);
  }

  private HandoffResult persistDownstreamResponse(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffResult result,
      int intentRowVersion) {
    if (isReconciliationRequired(result)) {
      return result;
    }
    if (handoffTransactionTemplate != null) {
      Optional<ScriptHandoffEvent> existingHandoff =
          handoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal(
              workItem.getTenantId(), workItem.getId(), command.ordinal());
      if (existingHandoff.isEmpty()) {
        return reconciliationRequiredResult("durable handoff intent was not found");
      }
      ScriptHandoffEvent existing = existingHandoff.orElseThrow();
      if (isAcceptedHandoff(existing)) {
        return handoffResult(existing);
      }
      if (intentRowVersion >= 0 && existing.getRowVersion() != intentRowVersion) {
        return reconciliationRequiredResult("durable handoff intent fence advanced");
      }
    }
    applyOutcome(workItem, command, dispatchId, result, Instant.now());
    return result;
  }

  private static ScopeValidationResult validateRemoteHandoffScope(
      ScriptWorkItem workItem, EmittedCommand command) {
    if (!requiresRemoteHandoff(workItem, command)) {
      return null;
    }
    if (command.targetRegionEpoch() == null || command.targetRegionEpoch() <= 0) {
      return new ScopeValidationResult(
          ScriptHandoffOutcomeSupport.ERROR_INVALID_ARGUMENT,
          "target_region_epoch must be positive for remote handoff");
    }
    return null;
  }

  private record ScopeValidationResult(String errorCode, String message) {}

  private static HandoffResult localHandoffResult(
      EnqueueAutomationCommandIfAbsentResponse response) {
    if (!response.getAccepted()) {
      String errorCode = response.hasError() ? normalize(response.getError().getCode()) : "";
      return new HandoffResult(
          false,
          response.getAdmissionOutcome(),
          response.getCommandId(),
          "",
          "",
          errorCode.isBlank()
              ? ScriptHandoffOutcomeSupport.ERROR_REMOTE_RESPONSE_INVALID
              : errorCode);
    }
    String admissionOutcome = normalize(response.getAdmissionOutcome());
    boolean hasNonBlankErrorMetadata =
        response.hasError()
            && (!normalize(response.getError().getCode()).isBlank()
                || !normalize(response.getError().getMessage()).isBlank());
    boolean coherentOutcome =
        "ENQUEUED".equals(admissionOutcome) || "DUPLICATE_NOOP".equals(admissionOutcome);
    if (normalize(response.getCommandId()).isBlank()
        || hasNonBlankErrorMetadata
        || !coherentOutcome) {
      return reconciliationRequiredResult("local owner response was incomplete");
    }
    return new HandoffResult(true, admissionOutcome, response.getCommandId(), "", "", "");
  }

  private static HandoffResult remoteHandoffResult(ScheduleRemoteFollowupResponse remoteResponse) {
    if (remoteResponse == null) {
      return reconciliationRequiredResult("remote owner response was null");
    }
    String remoteCoordinatorId = remoteResponse.getCoordinatorId();
    String remoteFollowupId = remoteResponse.getFollowupId();
    if (remoteResponse.hasError()) {
      String errorCode = normalize(remoteResponse.getError().getCode());
      return new HandoffResult(
          false,
          ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
          "",
          remoteCoordinatorId,
          remoteFollowupId,
          errorCode.isBlank()
              ? ScriptHandoffOutcomeSupport.ERROR_REMOTE_RESPONSE_INVALID
              : errorCode);
    }
    boolean hasDurableIds =
        remoteCoordinatorId != null
            && !remoteCoordinatorId.isBlank()
            && remoteFollowupId != null
            && !remoteFollowupId.isBlank();
    if (!hasDurableIds) {
      return reconciliationRequiredResult("remote owner response omitted durable identifiers");
    }
    return new HandoffResult(
        true,
        ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_SCHEDULED,
        "",
        remoteCoordinatorId,
        remoteFollowupId,
        "");
  }

  private static HandoffResult reconciliationRequiredResult(String message) {
    return new HandoffResult(
        false,
        OUTCOME_HANDOFF_IN_FLIGHT,
        "",
        "",
        "",
        OUTCOME_HANDOFF_IN_FLIGHT,
        message == null ? "" : message);
  }

  private static HandoffResult reconciliationRequiredResult(Throwable failure) {
    return reconciliationRequiredResult(
        failure == null || failure.getMessage() == null ? "" : failure.getMessage());
  }

  private static HandoffResult retryablePreparationResult(Throwable failure) {
    return new HandoffResult(
        false,
        ScriptHandoffOutcomeSupport.OUTCOME_REMOTE_REJECTED,
        "",
        "",
        "",
        "UNAVAILABLE",
        failure == null || failure.getMessage() == null ? "" : failure.getMessage());
  }

  private static boolean isReconciliationRequired(HandoffResult result) {
    return result != null
        && (OUTCOME_HANDOFF_IN_FLIGHT.equalsIgnoreCase(normalize(result.outcome()))
            || OUTCOME_HANDOFF_IN_FLIGHT.equalsIgnoreCase(normalize(result.errorCode())));
  }

  private static boolean isAcceptedHandoff(ScriptHandoffEvent event) {
    String outcome = normalize(event.getHandoffOutcome()).trim().toUpperCase(Locale.ROOT);
    return "ENQUEUED".equals(outcome)
        || "DUPLICATE_NOOP".equals(outcome)
        || "REMOTE_SCHEDULED".equals(outcome);
  }

  private static HandoffResult handoffResult(ScriptHandoffEvent event) {
    String outcome = normalize(event.getHandoffOutcome()).trim().toUpperCase(Locale.ROOT);
    return new HandoffResult(
        true,
        outcome,
        normalize(event.getGameSessionCommandId()),
        normalize(event.getRemoteCoordinatorId()),
        normalize(event.getRemoteFollowupId()),
        "");
  }

  private static boolean handoffIdentityMatches(
      ScriptHandoffEvent existing,
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    return Objects.equals(existing.getEventId(), handoffEventId(workItem, command.ordinal()))
        && Objects.equals(existing.getTenantId(), workItem.getTenantId())
        && Objects.equals(existing.getGameInstanceId(), workItem.getGameInstanceId())
        && Objects.equals(existing.getScriptPatchVersion(), workItem.getScriptPatchVersion())
        && existing.getScriptPinEpoch() == workItem.getScriptPinEpoch()
        && Objects.equals(
            normalize(existing.getScriptPinControlPlaneRequestId()),
            normalize(workItem.getScriptPinControlPlaneRequestId()))
        && Objects.equals(existing.getScriptId(), workItem.getScriptId())
        && Objects.equals(normalize(existing.getBindingId()), normalize(workItem.getBindingId()))
        && Objects.equals(normalize(existing.getPluginId()), normalize(workItem.getPluginId()))
        && Objects.equals(
            normalize(existing.getPluginVersionId()), normalize(workItem.getPluginVersionId()))
        && existing.getPluginActivationEpoch() == workItem.getPluginActivationEpoch()
        && existing.getLifecycleRevision() == workItem.getLifecycleRevision()
        && Objects.equals(existing.getWorkItemId(), workItem.getId())
        && existing.getCommandOrdinal() == command.ordinal()
        && Objects.equals(existing.getAutomationDispatchId(), dispatchId)
        && Objects.equals(
            normalize(existing.getTargetGameInstanceId()),
            normalize(command.targetGameInstanceId()))
        && Objects.equals(
            normalize(existing.getTargetRegionId()), normalize(command.targetRegionId()))
        && existing.getTargetRegionEpoch() == zeroIfNull(command.targetRegionEpoch())
        && Objects.equals(existing.getTargetEntityId(), command.targetEntityId())
        && Objects.equals(
            normalize(existing.getPlayableStateScope()),
            normalize(workItem.getPlayableStateScope()))
        && Objects.equals(existing.getWorldSlug(), routingBundle.worldSlug())
        && Objects.equals(existing.getRealmSlug(), routingBundle.realmSlug())
        && Objects.equals(existing.getPointerVersion(), routingBundle.pointerVersion())
        && Objects.equals(existing.getSourceKind(), normalize(workItem.getSourceKind()))
        && Objects.equals(existing.getSourceState(), normalize(workItem.getSourceState()))
        && Objects.equals(existing.getSourceOrdinal(), workItem.getSourceOrdinal())
        && Objects.equals(existing.getSourceDueTickId(), workItem.getSourceDueTickId())
        && Objects.equals(existing.getSourceDueAtMs(), workItem.getSourceDueAtMs())
        && Objects.equals(existing.getEmittedCommandText(), command.commandText());
  }

  private String admissionFenceReason(ScriptWorkItem workItem) {
    if (workItem.getGameInstanceId() == null || workItem.getGameInstanceId().isBlank()) {
      return null;
    }
    AutomationAdmissionStateService.AdmissionStateSummary state =
        automationAdmissionStateService.getState(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getRegionId());
    if (state == null) {
      return ScriptHandoffOutcomeSupport.REASON_AUTHORITY_UNAVAILABLE;
    }
    if (state.admissionEpoch() != workItem.getAdmissionEpoch()) {
      return ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED;
    }
    if ("PAUSED_FOR_ROLLBACK".equals(state.mode())) {
      return ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED;
    }
    return "NORMAL".equals(state.mode())
        ? null
        : ScriptHandoffOutcomeSupport.REASON_AUTHORITY_UNAVAILABLE;
  }

  /**
   * Uses the same transaction-scoped owner lock as admission mutations. The lock fences the pre-RPC
   * admission read and intent commit; the RPC itself runs after that transaction commits and
   * therefore never holds this local transaction open.
   */
  private void lockAdmissionScope(ScriptWorkItem workItem) {
    if (dsl == null
        || workItem.getTenantId() == null
        || workItem.getTenantId().isBlank()
        || workItem.getGameInstanceId() == null
        || workItem.getGameInstanceId().isBlank()) {
      return;
    }
    AutomationAdmissionStateServiceImpl.lockMutationScope(
        dsl, workItem.getTenantId(), workItem.getGameInstanceId());
  }

  private void cancelForAdmissionPause(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      Instant now,
      boolean deferAggregateTerminalization) {
    HandoffResult result =
        new HandoffResult(
            false,
            ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED,
            "",
            "",
            "",
            ScriptHandoffOutcomeSupport.ERROR_RUNTIME_PAUSED);
    appendHandoffEvent(workItem, command, dispatchId, result, now);
    if (deferAggregateTerminalization) {
      return;
    }
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason(ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(
        workItem,
        ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
        ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
        ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED,
        now);
  }

  private void cancelForRollbackEpochAdvance(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      Instant now,
      boolean deferAggregateTerminalization) {
    appendHandoffEvent(
        workItem,
        command,
        dispatchId,
        new HandoffResult(
            false,
            ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED,
            "",
            "",
            "",
            ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED),
        now);
    if (deferAggregateTerminalization) {
      return;
    }
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason(ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(
        workItem,
        ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
        ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
        ScriptHandoffOutcomeSupport.REASON_ROLLBACK_EPOCH_ADVANCED,
        now);
  }

  private enum RuntimeRegionScopeStatus {
    CURRENT,
    ADVANCED,
    UNAVAILABLE,
    MALFORMED
  }

  private record AggregateAdmissionSnapshot(
      String admissionFenceReason, RuntimeRegionScopeStatus runtimeRegionScopeStatus) {}

  private record HandoffPreflight(
      String admissionFenceReason, RuntimeRegionScopeStatus runtimeScopeStatus) {
    static HandoffPreflight from(AggregateAdmissionSnapshot snapshot) {
      return new HandoffPreflight(
          snapshot.admissionFenceReason(), snapshot.runtimeRegionScopeStatus());
    }
  }

  private HandoffPreflight preflight(ScriptWorkItem workItem) {
    String admissionFenceReason = admissionFenceReason(workItem);
    RuntimeRegionScopeStatus runtimeScopeStatus =
        admissionFenceReason == null
            ? runtimeRegionScopeStatusOutsideTransaction(workItem)
            : RuntimeRegionScopeStatus.MALFORMED;
    return new HandoffPreflight(admissionFenceReason, runtimeScopeStatus);
  }

  private RuntimeRegionScopeStatus runtimeRegionScopeStatusOutsideTransaction(
      ScriptWorkItem workItem) {
    if (rpcTransactionTemplate == null) {
      return runtimeRegionScopeStatus(workItem);
    }
    return rpcTransactionTemplate.execute(status -> runtimeRegionScopeStatus(workItem));
  }

  private RuntimeRegionScopeStatus runtimeRegionScopeStatus(ScriptWorkItem workItem) {
    if (workItem.getTenantId() == null
        || workItem.getTenantId().isBlank()
        || workItem.getGameInstanceId() == null
        || workItem.getGameInstanceId().isBlank()) {
      return RuntimeRegionScopeStatus.MALFORMED;
    }
    GetGameInstanceRuntimeStateResponse runtimeState;
    try {
      runtimeState =
          gameSessionClient.getGameInstanceRuntimeState(
              workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getRegionId());
    } catch (RuntimeException ex) {
      // A client-side failure is indistinguishable from an unavailable owner. Do not let a
      // partial local handoff escape the durable retry/fail-closed path.
      LOGGER.warn(
          "Runtime owner lookup failed for tenantId={} gameInstanceId={} regionId={}",
          workItem.getTenantId(),
          workItem.getGameInstanceId(),
          workItem.getRegionId(),
          ex);
      return RuntimeRegionScopeStatus.UNAVAILABLE;
    }
    if (runtimeState == null || runtimeState.hasError()) {
      return RuntimeRegionScopeStatus.UNAVAILABLE;
    }
    if (!runtimeState.hasRuntimeState()) {
      return RuntimeRegionScopeStatus.MALFORMED;
    }
    if (!runtimeState.getRuntimeState().getTenantId().equals(workItem.getTenantId())
        || !runtimeState
            .getRuntimeState()
            .getGameInstanceId()
            .equals(workItem.getGameInstanceId())) {
      return RuntimeRegionScopeStatus.MALFORMED;
    }
    if (runtimeState.getRuntimeState().getRegionId().isBlank()
        || runtimeState.getRuntimeState().getRegionEpoch() <= 0) {
      return RuntimeRegionScopeStatus.MALFORMED;
    }
    // Region ownership alone is not a sufficient final script fence: the same patch can be
    // repinned under a newer epoch. Require the exact tuple and owner request evidence captured
    // on the durable work item before allowing either local staging or remote scheduling.
    if (workItem.getScriptPinEpoch() <= 0
        || normalize(workItem.getScriptPinControlPlaneRequestId()).isBlank()
        || runtimeState.getRuntimeState().getScriptPinEpoch() <= 0
        || normalize(runtimeState.getRuntimeState().getScriptPatchPinnedControlPlaneRequestId())
            .isBlank()) {
      return RuntimeRegionScopeStatus.MALFORMED;
    }
    if (!runtimeState
            .getRuntimeState()
            .getPinnedScriptPatchVersion()
            .equals(normalize(workItem.getScriptPatchVersion()))
        || runtimeState.getRuntimeState().getScriptPinEpoch() != workItem.getScriptPinEpoch()
        || !runtimeState
            .getRuntimeState()
            .getScriptPatchPinnedControlPlaneRequestId()
            .equals(normalize(workItem.getScriptPinControlPlaneRequestId()))) {
      return RuntimeRegionScopeStatus.ADVANCED;
    }
    return runtimeState.getRuntimeState().getRegionId().equals(normalize(workItem.getRegionId()))
            && runtimeState.getRuntimeState().getRegionEpoch() == workItem.getRegionEpoch()
        ? RuntimeRegionScopeStatus.CURRENT
        : RuntimeRegionScopeStatus.ADVANCED;
  }

  private void cancelForRuntimeRegionScopeAdvance(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      Instant now,
      boolean deferAggregateTerminalization) {
    HandoffResult result =
        new HandoffResult(
            false,
            ScriptHandoffOutcomeSupport.REASON_RUNTIME_REGION_SCOPE_ADVANCED,
            "",
            "",
            "",
            ScriptHandoffOutcomeSupport.REASON_RUNTIME_REGION_SCOPE_ADVANCED);
    appendHandoffEvent(workItem, command, dispatchId, result, now);
    if (deferAggregateTerminalization) {
      return;
    }
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason(ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(
        workItem,
        ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
        ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
        ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED,
        now);
  }

  private EnqueueAutomationCommandIfAbsentRequest toRequest(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    return EnqueueAutomationCommandIfAbsentRequest.newBuilder()
        .setTenantId(workItem.getTenantId())
        .setGameInstanceId(workItem.getGameInstanceId())
        .setRegionId(workItem.getRegionId())
        .setRegionEpoch(workItem.getRegionEpoch())
        .setDueTickId(command.dueTickId())
        .setAutomationDispatchId(dispatchId)
        .setAutomationWorkItemId(workItem.getId().toString())
        .setScriptId(workItem.getScriptId())
        .setBindingId(normalize(workItem.getBindingId()))
        .setScriptPatchVersion(workItem.getScriptPatchVersion())
        .setScriptPinEpoch(workItem.getScriptPinEpoch())
        .setScriptPinControlPlaneRequestId(normalize(workItem.getScriptPinControlPlaneRequestId()))
        .setPluginId(normalize(workItem.getPluginId()))
        .setPluginVersionId(normalize(workItem.getPluginVersionId()))
        .setPlayableStateScope(toPlayableStateScope(workItem.getPlayableStateScope()))
        .setWorldSlug(routingBundle.worldSlug())
        .setRealmSlug(routingBundle.realmSlug())
        .setPointerVersion(routingBundle.pointerVersion())
        .setOriginSourceKind(normalize(workItem.getSourceKind()))
        .setOriginSourceState(normalize(workItem.getSourceState()))
        .setOriginSourceOrdinal(zeroIfNull(workItem.getSourceOrdinal()))
        .setOriginSourceDueTickId(zeroIfNull(workItem.getSourceDueTickId()))
        .setOriginSourceDueAtMs(zeroIfNull(workItem.getSourceDueAtMs()))
        .setTargetEntityId(command.targetEntityId())
        .setCommand(command.commandText())
        .setRequiresSoloTick(command.requiresSoloTick())
        .build();
  }

  private ScheduleRemoteFollowupRequest toRemoteScheduleRequest(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId) {
    long targetDueTickId = command.dueTickId() > 0 ? command.dueTickId() : 0L;
    long originDeadlineTickId = originDeadlineTickId(workItem, command);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    return ScheduleRemoteFollowupRequest.newBuilder()
        .setTenantId(workItem.getTenantId())
        .setCommandId(dispatchId)
        .setCoordinatorId("remote-coordinator:" + dispatchId)
        .setOriginGameInstanceId(workItem.getGameInstanceId())
        .setOriginRegionId(workItem.getRegionId())
        .setOriginRegionEpoch(workItem.getRegionEpoch())
        .setTargetGameInstanceId(normalize(command.targetGameInstanceId()))
        .setTargetRegionId(normalize(command.targetRegionId()))
        .setTargetRegionEpoch(zeroIfNull(command.targetRegionEpoch()))
        .setTargetDueTickId(targetDueTickId)
        .setOriginDeadlineRegionEpoch(workItem.getRegionEpoch())
        .setOriginDeadlineTickId(originDeadlineTickId)
        .setLateResultPolicy(REMOTE_LATE_RESULT_POLICY)
        .setFollowupId("remote-followup:" + dispatchId)
        .setEffectKey("remote-followup:" + dispatchId)
        .setTargetEntityId(command.targetEntityId())
        .setPayloadKind("enqueue_automation_command")
        .setRequestedCommand(command.commandText())
        .setRequiresSoloTick(command.requiresSoloTick())
        .setPlayableStateScope(toPlayableStateScope(workItem.getPlayableStateScope()))
        .setWorldSlug(routingBundle.worldSlug())
        .setRealmSlug(routingBundle.realmSlug())
        .setPointerVersion(routingBundle.parsedPointerVersion())
        .setScriptPatchVersion(workItem.getScriptPatchVersion())
        .setScriptPinEpoch(workItem.getScriptPinEpoch())
        .setScriptPinControlPlaneRequestId(normalize(workItem.getScriptPinControlPlaneRequestId()))
        .setPluginId(normalize(workItem.getPluginId()))
        .setPluginVersionId(normalize(workItem.getPluginVersionId()))
        .setAutomationDispatchId(dispatchId)
        .setAutomationWorkItemId(workItem.getId().toString())
        .setScriptId(workItem.getScriptId())
        .setOriginSourceKind(normalize(workItem.getSourceKind()))
        .setOriginSourceState(normalize(workItem.getSourceState()))
        .setOriginSourceOrdinal(zeroIfNull(workItem.getSourceOrdinal()))
        .setOriginSourceDueTickId(zeroIfNull(workItem.getSourceDueTickId()))
        .setOriginSourceDueAtMs(zeroIfNull(workItem.getSourceDueAtMs()))
        .build();
  }

  private static boolean hasRepresentableDeadline(ScriptWorkItem workItem, EmittedCommand command) {
    long dueTickId = effectiveDueTickId(workItem, command);
    return ScriptCommandMetadataSupport.isRepresentableDueTickId(dueTickId);
  }

  private void applyOutcome(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffResult result,
      Instant now) {
    appendHandoffEvent(workItem, command, dispatchId, result, now);
    if (isReconciliationRequired(result)) {
      // A lost/ambiguous downstream response is not a terminal rejection. The child remains
      // HANDOFF_IN_FLIGHT until a later exact-identity reconciliation determines the outcome.
      return;
    }
    if (result.accepted()) {
      // The executor owns the aggregate terminal outcome. This child is only durably accepted;
      // recording handoff_accepted here would falsely claim that all siblings were accepted.
      return;
    }
    if (ScriptHandoffOutcomeSupport.isRetryable(result)) {
      if (deferAggregateTerminalization(workItem)) {
        return;
      }
      requeueForRetry(workItem, now);
      return;
    }
    if (deferAggregateTerminalization(workItem)) {
      return;
    }
    if (ScriptHandoffOutcomeSupport.isRuntimeScopeFence(result)) {
      workItem.setStatus(STATUS_CANCELED);
      workItem.setCancelReason(ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED);
      workItem.setUpdatedAt(now);
      workItemRepository.save(workItem);
      rolloutProjectionService.refreshForWorkItem(workItem);
      updateAudit(
          workItem,
          ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
          ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
          ScriptHandoffOutcomeSupport.REASON_RUNTIME_SCOPE_CHANGED,
          now);
      return;
    }
    if (ScriptHandoffOutcomeSupport.isAdmissionPause(result)) {
      workItem.setStatus(STATUS_CANCELED);
      workItem.setCancelReason(ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED);
      workItem.setUpdatedAt(now);
      workItemRepository.save(workItem);
      rolloutProjectionService.refreshForWorkItem(workItem);
      updateAudit(
          workItem,
          ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
          ScriptHandoffOutcomeSupport.OUTCOME_CANCELED,
          ScriptHandoffOutcomeSupport.REASON_RUNTIME_PAUSED,
          now);
      return;
    }
    // A terminal failure is a distinct dead-letter transition unless this item was
    // already terminal. Replay moves it back to PENDING_EVALUATION first, so the
    // next terminal failure advances the generation exactly once.
    if (!STATUS_DEAD_LETTERED.equals(workItem.getStatus())) {
      workItem.setFailureGeneration(Math.addExact(workItem.getFailureGeneration(), 1L));
    }
    workItem.setStatus(STATUS_DEAD_LETTERED);
    String failureReason = ScriptHandoffOutcomeSupport.canonicalInfrastructureReason(result);
    workItem.setCancelReason(failureReason);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(
        workItem,
        ScriptHandoffOutcomeSupport.STAGE_TICK_HANDOFF,
        ScriptHandoffOutcomeSupport.OUTCOME_INFRASTRUCTURE_ERROR,
        failureReason,
        now);
  }

  private void requeueForRetry(ScriptWorkItem workItem, Instant now) {
    workItem.setStatus(STATUS_PENDING_EVALUATION);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, workItem, LOGGER);
  }

  private boolean deferAggregateTerminalization(ScriptWorkItem workItem) {
    return workItem.getId() != null && aggregateFanouts.get().contains(workItem.getId());
  }

  private void updateAudit(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    auditRepository
        .findByWorkItemId(workItem.getId())
        .ifPresent(
            audit -> {
              audit.setFinalStage(stage);
              audit.setFinalOutcome(outcome);
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  private static String dispatchId(ScriptWorkItem workItem, int ordinal) {
    return "workItem:" + workItem.getId() + "#" + ordinal;
  }

  private ScriptHandoffEvent appendHandoffIntent(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId, Instant now) {
    return appendHandoffEvent(workItem, command, dispatchId, reconciliationRequiredResult(""), now);
  }

  private ScriptHandoffEvent appendHandoffEvent(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffResult result,
      Instant now) {
    String outcome = result.outcome().toLowerCase(Locale.ROOT);
    String reason = handoffReason(result);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId(handoffEventId(workItem, command.ordinal()));
    event.setTenantId(workItem.getTenantId());
    event.setGameInstanceId(workItem.getGameInstanceId());
    event.setScriptPatchVersion(workItem.getScriptPatchVersion());
    event.setScriptPinEpoch(workItem.getScriptPinEpoch());
    event.setScriptPinControlPlaneRequestId(workItem.getScriptPinControlPlaneRequestId());
    event.setScriptId(workItem.getScriptId());
    event.setBindingId(normalize(workItem.getBindingId()));
    event.setPluginId(normalize(workItem.getPluginId()));
    event.setPluginVersionId(normalize(workItem.getPluginVersionId()));
    event.setScriptPinEpoch(workItem.getScriptPinEpoch());
    event.setPluginActivationEpoch(workItem.getPluginActivationEpoch());
    event.setLifecycleRevision(workItem.getLifecycleRevision());
    event.setWorkItemId(workItem.getId());
    event.setCommandOrdinal(command.ordinal());
    event.setAutomationDispatchId(dispatchId);
    event.setGameSessionCommandId(normalize(result.commandId()));
    event.setRemoteCoordinatorId(normalize(result.remoteCoordinatorId()));
    event.setRemoteFollowupId(normalize(result.remoteFollowupId()));
    event.setTargetEntityId(command.targetEntityId());
    event.setTargetGameInstanceId(normalize(command.targetGameInstanceId()));
    event.setTargetRegionId(normalize(command.targetRegionId()));
    event.setTargetRegionEpoch(zeroIfNull(command.targetRegionEpoch()));
    event.setPlayableStateScope(normalize(workItem.getPlayableStateScope()));
    event.setWorldSlug(routingBundle.worldSlug());
    event.setRealmSlug(routingBundle.realmSlug());
    event.setPointerVersion(routingBundle.pointerVersion());
    event.setSourceKind(normalize(workItem.getSourceKind()));
    event.setSourceState(normalize(workItem.getSourceState()));
    event.setSourceOrdinal(workItem.getSourceOrdinal());
    event.setSourceDueTickId(workItem.getSourceDueTickId());
    event.setSourceDueAtMs(workItem.getSourceDueAtMs());
    event.setEmittedCommandText(command.commandText());
    event.setHandoffOutcome(outcome);
    event.setHandoffReason(reason);
    event.setObservedAt(now);
    handoffEventRepository
        .findByTenantIdAndWorkItemIdAndCommandOrdinal(
            workItem.getTenantId(), workItem.getId(), command.ordinal())
        .ifPresent(
            existing -> {
              event.setId(existing.getId());
              event.setRowVersion(existing.getRowVersion());
            });
    ScriptHandoffEvent saved = handoffEventRepository.save(event);
    return saved == null ? event : saved;
  }

  private static String handoffEventId(ScriptWorkItem workItem, int commandOrdinal) {
    return "she-work-item-" + workItem.getId() + "-command-" + commandOrdinal;
  }

  private static String handoffReason(HandoffResult result) {
    if (isReconciliationRequired(result)) {
      return REASON_HANDOFF_IN_FLIGHT;
    }
    if (result.accepted()) {
      return result.remoteFollowupId().isBlank()
          ? "game_session_accepted"
          : "remote_followup_scheduled";
    }
    String reason = normalize(result.errorCode()).trim();
    if (reason.isBlank()) {
      reason = normalize(result.outcome()).trim();
    }
    return ScriptHandoffOutcomeSupport.canonicalHandoffReason(reason, result.outcome());
  }

  private static boolean requiresRemoteHandoff(ScriptWorkItem workItem, EmittedCommand command) {
    return !normalize(workItem.getGameInstanceId())
            .equals(normalize(command.targetGameInstanceId()))
        || !normalize(workItem.getRegionId()).equals(normalize(command.targetRegionId()))
        || workItem.getRegionEpoch() != zeroIfNull(command.targetRegionEpoch());
  }

  private static long originDeadlineTickId(ScriptWorkItem workItem, EmittedCommand command) {
    long dueTickId = effectiveDueTickId(workItem, command);
    if (dueTickId <= 0) {
      return Long.MAX_VALUE;
    }
    try {
      return Math.addExact(dueTickId, 1L);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("due_tick_id_invalid", ex);
    }
  }

  private static long effectiveDueTickId(ScriptWorkItem workItem, EmittedCommand command) {
    long commandDueTickId = command.dueTickId();
    if (commandDueTickId > 0) {
      return commandDueTickId;
    }
    if (workItem.getSourceDueTickId() != null) {
      long sourceDueTickId = workItem.getSourceDueTickId();
      if (sourceDueTickId > 0) {
        return sourceDueTickId;
      }
    }
    return 0L;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  private static PlayableStateScope toPlayableStateScope(String playableStateScope) {
    return switch (normalize(playableStateScope)) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private static void requireWorkItem(ScriptWorkItem workItem) {
    if (workItem == null || workItem.getId() == null) {
      throw new IllegalArgumentException("persisted work_item is required");
    }
  }

  private static void requireCommand(EmittedCommand command) {
    if (command == null || command.commandText() == null || command.commandText().isBlank()) {
      throw new IllegalArgumentException("command_text is required");
    }
    if (command.targetEntityId() == null || command.targetEntityId().isBlank()) {
      throw new IllegalArgumentException("target_entity_id is required");
    }
    if (command.targetGameInstanceId() == null || command.targetGameInstanceId().isBlank()) {
      throw new IllegalArgumentException("target_game_instance_id is required");
    }
    if (command.targetRegionId() == null || command.targetRegionId().isBlank()) {
      throw new IllegalArgumentException("target_region_id is required");
    }
    if (command.ordinal() < 0) {
      throw new IllegalArgumentException("command ordinal must be non-negative");
    }
  }
}
