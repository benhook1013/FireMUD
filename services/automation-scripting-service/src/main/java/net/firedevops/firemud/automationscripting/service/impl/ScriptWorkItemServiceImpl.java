package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptDeadLetterReplayRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are internal Spring collaborators")
public class ScriptWorkItemServiceImpl implements ScriptWorkItemService {
  static final class ReplayIdempotencyConflictException extends RuntimeException {
    ReplayIdempotencyConflictException(String message) {
      super(message);
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(ScriptWorkItemServiceImpl.class);
  private static final String PARTICIPANT_KEY_AUTOMATION_SCRIPTING = "AUTOMATION_SCRIPTING";
  private static final String STATUS_PENDING_EVALUATION = "PENDING_EVALUATION";
  private static final String STATUS_EVALUATING = "EVALUATING";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String STATUS_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final List<String> CANCELABLE_STATUSES = List.of(STATUS_PENDING_EVALUATION);
  private static final List<String> ACTIVE_DRAIN_STATUSES =
      List.of(STATUS_EVALUATING, STATUS_HANDOFF_IN_FLIGHT);
  private static final List<String> DRAIN_RELEVANT_STATUSES =
      List.of(STATUS_PENDING_EVALUATION, STATUS_EVALUATING, STATUS_HANDOFF_IN_FLIGHT);
  private static final int REPLAY_CONTROL_PLANE_REQUEST_ID_MAX_LENGTH = 128;
  private static final int REPLAY_ACTOR_PRINCIPAL_MAX_LENGTH = 256;
  private static final int REPLAY_REASON_MAX_LENGTH = 256;
  private final AtomicLong retentionBlockedRows = new AtomicLong();
  private final AtomicLong retentionDeadLetterBlockedRows = new AtomicLong();
  private final MeterRegistry meterRegistry;

  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final ScriptEventIngressAuditRepository ingressAuditRepository;
  private final ScriptHandoffEventRepository handoffEventRepository;
  private final ScriptOutboxProperties outboxProperties;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchPinProjectionService scriptPatchPinProjectionService;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;
  private final PluginRuntimeStateService pluginRuntimeStateService;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final ScriptPatchReadinessProjectionService readinessProjectionService;
  private final ScriptDeadLetterReplayRepository replayRepository;
  private final ScriptDeadLetterReplayTransactionBoundary replayTransactionBoundary;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptDefinitionRepository scriptDefinitionRepository;
  private final AutomationQueueService automationQueueService;

  @org.springframework.beans.factory.annotation.Autowired
  public ScriptWorkItemServiceImpl(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptEventIngressAuditRepository ingressAuditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      ScriptOutboxProperties outboxProperties,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      ScriptDeadLetterReplayRepository replayRepository,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      MeterRegistry meterRegistry,
      ScriptDefinitionRepository scriptDefinitionRepository,
      AutomationQueueService automationQueueService,
      ScriptDeadLetterReplayTransactionBoundary replayTransactionBoundary) {
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.ingressAuditRepository = ingressAuditRepository;
    this.handoffEventRepository = handoffEventRepository;
    this.outboxProperties = outboxProperties;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.scriptPatchPinProjectionService = scriptPatchPinProjectionService;
    this.rolloutProjectionService = rolloutProjectionService;
    this.pluginRuntimeStateService = pluginRuntimeStateService;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.readinessProjectionService = readinessProjectionService;
    this.replayRepository = replayRepository;
    this.replayTransactionBoundary = replayTransactionBoundary;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.meterRegistry = meterRegistry;
    Gauge.builder("automation_retention_blocked_rows", retentionBlockedRows, AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder(
            "automation_retention_dead_letter_blocked_rows",
            retentionDeadLetterBlockedRows,
            AtomicLong::get)
        .register(meterRegistry);
    this.scriptDefinitionRepository = scriptDefinitionRepository;
    this.automationQueueService = automationQueueService;
  }

  public ScriptWorkItemServiceImpl(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptEventIngressAuditRepository ingressAuditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      ScriptOutboxProperties outboxProperties,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      ScriptDeadLetterReplayRepository replayRepository,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      MeterRegistry meterRegistry,
      ScriptDefinitionRepository scriptDefinitionRepository,
      AutomationQueueService automationQueueService) {
    this(
        workItemRepository,
        auditRepository,
        ingressAuditRepository,
        handoffEventRepository,
        outboxProperties,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        rolloutProjectionService,
        pluginRuntimeStateService,
        gameDesignControlPlaneClient,
        readinessProjectionService,
        replayRepository,
        gameSessionControlPlaneClient,
        meterRegistry,
        scriptDefinitionRepository,
        automationQueueService,
        new ScriptDeadLetterReplayTransactionBoundary());
  }

  ScriptWorkItemServiceImpl(
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptEventIngressAuditRepository ingressAuditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      ScriptOutboxProperties outboxProperties,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      ScriptPatchReadinessProjectionService readinessProjectionService,
      ScriptDeadLetterReplayRepository replayRepository,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      MeterRegistry meterRegistry,
      ScriptDefinitionRepository scriptDefinitionRepository) {
    this(
        workItemRepository,
        auditRepository,
        ingressAuditRepository,
        handoffEventRepository,
        outboxProperties,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        rolloutProjectionService,
        pluginRuntimeStateService,
        gameDesignControlPlaneClient,
        readinessProjectionService,
        replayRepository,
        gameSessionControlPlaneClient,
        meterRegistry,
        scriptDefinitionRepository,
        null,
        new ScriptDeadLetterReplayTransactionBoundary());
  }

  @Override
  @Transactional
  public long cancelPendingForPatch(CancelPendingForPatchCommand command) {
    String normalizedTenantId = normalizeText(command.tenantId());
    requireText(normalizedTenantId, "tenant_id");
    requireText(command.scriptPatchVersion(), "script_patch_version");
    String normalizedGameInstanceId = normalizeText(command.gameInstanceId());
    String normalizedRegionId = normalizeText(command.regionId());
    long canceled = 0L;
    while (true) {
      List<ScriptWorkItem> candidates =
          workItemRepository
              .findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
                  normalizedTenantId,
                  command.scriptPatchVersion(),
                  normalizedGameInstanceId,
                  normalizedRegionId,
                  CANCELABLE_STATUSES);
      if (candidates.isEmpty()) {
        return canceled;
      }
      canceled += cancelCandidates(candidates, command.reason());
      if (candidates.size() < ScriptWorkItemRepository.CANCELLATION_PAGE_SIZE) {
        return canceled;
      }
    }
  }

  @Override
  @Transactional
  public long cancelPendingForPluginVersion(CancelPendingForPluginVersionCommand command) {
    String normalizedTenantId = normalizeText(command.tenantId());
    requireText(normalizedTenantId, "tenant_id");
    requireText(command.pluginId(), "plugin_id");
    requireText(command.pluginVersionId(), "plugin_version_id");
    String normalizedGameInstanceId = normalizeText(command.gameInstanceId());
    String normalizedRegionId = normalizeText(command.regionId());
    long canceled = 0L;
    while (true) {
      List<ScriptWorkItem> candidates =
          workItemRepository
              .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
                  normalizedTenantId,
                  command.pluginId(),
                  command.pluginVersionId(),
                  normalizedGameInstanceId,
                  normalizedRegionId,
                  CANCELABLE_STATUSES);
      if (candidates.isEmpty()) {
        return canceled;
      }
      canceled += cancelCandidates(candidates, command.reason());
      if (candidates.size() < ScriptWorkItemRepository.CANCELLATION_PAGE_SIZE) {
        return canceled;
      }
    }
  }

  private long cancelCandidates(List<ScriptWorkItem> candidates, String rawReason) {
    String reason = normalizeReason(rawReason);
    Instant now = Instant.now();
    candidates.forEach(item -> cancel(item, reason, now));
    workItemRepository.saveAll(candidates);
    refreshReadinessProjectionsIfNeeded(candidates);
    candidates.forEach(rolloutProjectionService::refreshForWorkItem);
    return candidates.size();
  }

  @Override
  @Transactional
  public List<ScriptWorkItem> claimPendingForEvaluation(int maxItems) {
    if (maxItems <= 0) {
      throw new IllegalArgumentException("max_items must be positive");
    }
    Instant now = Instant.now();
    List<ScriptWorkItem> items =
        workItemRepository.findByStatusForUpdateOrderByCreatedAtAscIdAsc(
            STATUS_PENDING_EVALUATION, now, PageRequest.of(0, maxItems));
    items.forEach(
        item -> {
          item.setStatus(STATUS_EVALUATING);
          item.setUpdatedAt(now);
        });
    List<ScriptWorkItem> saved = List.copyOf(workItemRepository.saveAll(items));
    saved.forEach(rolloutProjectionService::refreshForWorkItem);
    return saved;
  }

  @Override
  @Transactional
  public List<ScriptWorkItem> claimPendingForEvaluation(List<Long> workItemIds, int maxItems) {
    if (maxItems <= 0) {
      throw new IllegalArgumentException("max_items must be positive");
    }
    if (workItemIds == null || workItemIds.isEmpty()) {
      return List.of();
    }
    Instant now = Instant.now();
    List<ScriptWorkItem> items =
        workItemRepository.findByIdInAndStatusForUpdateOrderByCreatedAtAscIdAsc(
            workItemIds.stream().distinct().toList(),
            STATUS_PENDING_EVALUATION,
            now,
            PageRequest.of(0, maxItems));
    items.forEach(
        item -> {
          item.setStatus(STATUS_EVALUATING);
          item.setUpdatedAt(now);
        });
    List<ScriptWorkItem> saved = List.copyOf(workItemRepository.saveAll(items));
    saved.forEach(rolloutProjectionService::refreshForWorkItem);
    return saved;
  }

  @Override
  @Transactional
  public TerminalCleanupResult cleanupTerminalWorkItems() {
    Instant now = Instant.now();
    // HANDED_OFF and CANCELED retain their established status-specific cleanup contract.  A
    // DEAD_LETTERED row is different: the current schema has no recovery aggregate, complete
    // child ledger, or rollback/receipt horizon that can prove the whole bundle is disposable.
    // Keep its parent and all supporting replay/audit/handoff evidence until that owner contract
    // exists.  In particular, do not turn configured age or row-count knobs into a guessed TTL.
    long handedOffDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_HANDED_OFF,
            now.minus(outboxProperties.getHandedOffRetentionDays(), ChronoUnit.DAYS));
    long canceledDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_CANCELED,
            now.minus(outboxProperties.getCanceledRetentionDays(), ChronoUnit.DAYS));
    long deadLetteredDeleted = 0L;
    long deadLetteredBlocked =
        workItemRepository.countTerminalRowsBlockedByEvidence(
            STATUS_DEAD_LETTERED,
            now.minus(outboxProperties.getDeadLetterMaxAgeSeconds(), ChronoUnit.SECONDS));
    long blocked =
        workItemRepository.countTerminalRowsBlockedByEvidence(
                STATUS_HANDED_OFF,
                now.minus(outboxProperties.getHandedOffRetentionDays(), ChronoUnit.DAYS))
            + workItemRepository.countTerminalRowsBlockedByEvidence(
                STATUS_CANCELED,
                now.minus(outboxProperties.getCanceledRetentionDays(), ChronoUnit.DAYS))
            + deadLetteredBlocked;
    retentionDeadLetterBlockedRows.set(deadLetteredBlocked);
    retentionBlockedRows.set(blocked);
    return new TerminalCleanupResult(handedOffDeleted, canceledDeleted, deadLetteredDeleted);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PatchStatusSummary> getPatchStatus(String tenantId, String scriptPatchVersion) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    return readinessProjectionService
        .getProjection(tenantId, scriptPatchVersion)
        .map(
            readiness -> {
              PublicationMetadata metadata =
                  publicationMetadata(tenantId, readiness.baseVersionId(), scriptPatchVersion);
              return PatchStatusSummary.fromProjection(
                  readiness,
                  metadata.baseVersionId(),
                  metadata.abilitySchemaDigest(),
                  metadata.publication());
            });
  }

  @Override
  @Transactional(readOnly = true)
  public List<PatchStatusSummary> listPatchStatuses(
      String tenantId, ScriptPatchStatus status, long changedAfterMs, long changedBeforeMs) {
    requireText(tenantId, "tenant_id");
    PublicationMetadataCache publicationMetadataCache = new PublicationMetadataCache();
    return readinessProjectionService.listProjections(tenantId).stream()
        .map(
            readiness -> {
              PublicationMetadata metadata =
                  publicationMetadataCache.get(
                      tenantId, readiness.baseVersionId(), readiness.scriptPatchVersion());
              return PatchStatusSummary.fromProjection(
                  readiness,
                  metadata.baseVersionId(),
                  metadata.abilitySchemaDigest(),
                  metadata.publication());
            })
        .filter(
            summary ->
                status == ScriptPatchStatus.SCRIPT_PATCH_STATUS_UNSPECIFIED
                    || summary.status() == status)
        .filter(summary -> changedAfterMs <= 0 || summary.lastChangedAtMs() > changedAfterMs)
        .filter(summary -> changedBeforeMs <= 0 || summary.lastChangedAtMs() < changedBeforeMs)
        .sorted(Comparator.comparingLong(PatchStatusSummary::lastChangedAtMs).reversed())
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public AutomationDrainStatusSummary getAutomationDrainStatus(
      String tenantId, String gameInstanceId, String regionId) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    String normalizedGameInstanceId = normalizeText(gameInstanceId);
    requireText(normalizedGameInstanceId, "game_instance_id");
    String normalizedRegionId = normalizeText(regionId);
    Instant now = Instant.now();
    Optional<AutomationAdmissionStateService.AdmissionStateSummary> admissionStateLookup =
        automationAdmissionStateService.findState(
            normalizedTenantId, normalizedGameInstanceId, normalizedRegionId);
    boolean statePresent = admissionStateLookup.isPresent();
    AutomationAdmissionStateService.AdmissionStateSummary admissionState =
        admissionStateLookup.orElseGet(
            () ->
                new AutomationAdmissionStateService.AdmissionStateSummary(
                    normalizedTenantId,
                    normalizedGameInstanceId,
                    normalizedRegionId,
                    "NORMAL",
                    0L,
                    "",
                    "",
                    "",
                    0L,
                    "",
                    AutomationAdmissionStateService.OUTCOME_NOT_FOUND,
                    "",
                    0L));
    List<ScriptWorkItem> scopedWorkItems =
        workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            normalizedTenantId,
            normalizedGameInstanceId,
            normalizedRegionId,
            DRAIN_RELEVANT_STATUSES);
    List<ScriptWorkItem> countedWorkItems =
        scopedWorkItems.stream()
            .filter(item -> isEligibleForDrainStatus(item, admissionState))
            .toList();
    long activeExecutionCount =
        countedWorkItems.stream()
            .filter(item -> ACTIVE_DRAIN_STATUSES.contains(item.getStatus()))
            .count();
    long oldestActiveExecutionStartedAtMs =
        countedWorkItems.stream()
            .filter(item -> ACTIVE_DRAIN_STATUSES.contains(item.getStatus()))
            .map(ScriptWorkItem::getCreatedAt)
            .findFirst()
            .map(Instant::toEpochMilli)
            .orElse(0L);
    long pendingCancelableWorkItemCount =
        countedWorkItems.stream()
            .filter(item -> STATUS_PENDING_EVALUATION.equals(item.getStatus()))
            .count();
    return new AutomationDrainStatusSummary(
        normalizedTenantId,
        normalizedGameInstanceId,
        normalizedRegionId,
        statePresent,
        admissionState.mode(),
        admissionState.admissionEpoch(),
        admissionState.controlPlaneRequestId(),
        admissionState.targetMode(),
        admissionState.outcome(),
        admissionState.requestFingerprint(),
        admissionState.acknowledgedAtMs(),
        activeExecutionCount,
        oldestActiveExecutionStartedAtMs,
        pendingCancelableWorkItemCount,
        now.toEpochMilli());
  }

  private static boolean isEligibleForDrainStatus(
      ScriptWorkItem item, AutomationAdmissionStateService.AdmissionStateSummary admissionState) {
    if ("PAUSED_FOR_ROLLBACK".equals(admissionState.mode())) {
      return item.getAdmissionEpoch() <= 0
          || item.getAdmissionEpoch() < admissionState.admissionEpoch();
    }
    return item.getAdmissionEpoch() == admissionState.admissionEpoch();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PatchInstanceRolloutSummary> getPatchInstanceRolloutStatus(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId) {
    requireText(tenantId, "tenant_id");
    requireNonNegativeScriptPinEpoch(scriptPinEpoch);
    requireCompletePinTuple(scriptPinEpoch, lastObservedControlPlaneRequestId);
    requireText(gameInstanceId, "game_instance_id");
    requireText(scriptPatchVersion, "script_patch_version");
    Optional<PatchInstanceRolloutSummary> projection =
        rolloutProjectionService.getProjection(
            tenantId,
            gameInstanceId,
            scriptPatchVersion,
            projectionPinEpoch(scriptPinEpoch),
            projectionPinRequestId(scriptPinEpoch, lastObservedControlPlaneRequestId));
    return projection.map(
        summary -> withPublication(tenantId, summary, new PublicationMetadataCache()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PatchInstanceRolloutSummary> listPatchInstanceRollouts(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs) {
    requireText(tenantId, "tenant_id");
    requireNonNegativeScriptPinEpoch(scriptPinEpoch);
    requireCompletePinTuple(scriptPinEpoch, lastObservedControlPlaneRequestId);
    List<PatchInstanceRolloutSummary> projections =
        rolloutProjectionService.listProjections(
            tenantId,
            gameInstanceId,
            scriptPatchVersion,
            projectionPinEpoch(scriptPinEpoch),
            projectionPinRequestId(scriptPinEpoch, lastObservedControlPlaneRequestId),
            rolloutStatus,
            changedAfterMs,
            changedBeforeMs);
    PublicationMetadataCache publicationMetadataCache = new PublicationMetadataCache();
    return projections.stream()
        .map(summary -> withPublication(tenantId, summary, publicationMetadataCache))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<PatchInstanceRolloutEventSummary> listPatchInstanceRolloutEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    requireText(tenantId, "tenant_id");
    requireNonNegativeScriptPinEpoch(scriptPinEpoch);
    requireCompletePinTuple(scriptPinEpoch, lastObservedControlPlaneRequestId);
    List<PatchInstanceRolloutEventSummary> events =
        rolloutProjectionService.listEvents(
            tenantId,
            gameInstanceId,
            scriptPatchVersion,
            projectionPinEpoch(scriptPinEpoch),
            projectionPinRequestId(scriptPinEpoch, lastObservedControlPlaneRequestId),
            rolloutStatus,
            changedAfterMs,
            changedBeforeMs,
            limit);
    PublicationMetadataCache publicationMetadataCache = new PublicationMetadataCache();
    return events.stream()
        .map(summary -> withPublication(tenantId, summary, publicationMetadataCache))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<HandoffEventSummary> listHandoffEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String workItemId,
      String handoffOutcome,
      String targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String scriptId,
      String pluginId,
      String automationDispatchId,
      String gameSessionCommandId,
      String targetEntityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String sourceKind,
      String sourceState,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    int boundedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(worldSlug, realmSlug, pointerVersion);
    PublicationMetadataCache publicationMetadataCache = new PublicationMetadataCache();
    return handoffEventRepository
        .findEvents(
            normalizedTenantId,
            blankToEmpty(gameInstanceId),
            blankToEmpty(scriptPatchVersion),
            parseOptionalWorkItemId(workItemId),
            blankToEmpty(handoffOutcome),
            blankToEmpty(targetGameInstanceId),
            blankToEmpty(targetRegionId),
            targetRegionEpoch,
            blankToEmpty(remoteCoordinatorId),
            blankToEmpty(remoteFollowupId),
            blankToEmpty(scriptId),
            blankToEmpty(pluginId),
            blankToEmpty(automationDispatchId),
            blankToEmpty(gameSessionCommandId),
            blankToEmpty(targetEntityId),
            blankToEmpty(playableStateScope),
            routingBundle.worldSlug(),
            routingBundle.realmSlug(),
            routingBundle.pointerVersion(),
            blankToEmpty(sourceKind),
            blankToEmpty(sourceState),
            changedAfterMs <= 0 ? null : Instant.ofEpochMilli(changedAfterMs),
            changedBeforeMs <= 0 ? null : Instant.ofEpochMilli(changedBeforeMs),
            PageRequest.of(0, boundedLimit))
        .stream()
        .map(ScriptWorkItemServiceImpl::toHandoffSummary)
        .map(summary -> withPublication(normalizedTenantId, summary, publicationMetadataCache))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeadLetterSummary> listDeadLetters(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    int boundedLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 500);
    String normalizedGameInstanceId = normalizeText(gameInstanceId);
    String normalizedScriptPatchVersion = normalizeText(scriptPatchVersion);
    PublicationMetadataCache publicationMetadataCache = new PublicationMetadataCache();
    return workItemRepository
        .findDeadLettersByTenantIdAndFiltersOrderByUpdatedAtDescIdDesc(
            normalizedTenantId,
            normalizedGameInstanceId,
            normalizedScriptPatchVersion,
            STATUS_DEAD_LETTERED,
            PageRequest.of(0, boundedLimit))
        .stream()
        .map(ScriptWorkItemServiceImpl::toDeadLetterSummary)
        .map(summary -> withPublication(normalizedTenantId, summary, publicationMetadataCache))
        .toList();
  }

  @Override
  public ReplayResult replayDeadLetters(ReplayDeadLettersCommand command) {
    return replayTransactionBoundary.withoutTransaction(
        () -> replayDeadLettersWithoutTransaction(command));
  }

  private ReplayResult replayDeadLettersWithoutTransaction(ReplayDeadLettersCommand command) {
    validateReplayCommand(command);
    String normalizedTenantId = normalizeText(command.tenantId());
    requireText(normalizedTenantId, "tenant_id");
    Instant now = Instant.now();
    String reason = normalizeReplayReason(command.reason());
    String fingerprint = replayRequestFingerprint(command);
    Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> priorResults = Map.of();
    if (replayRepository != null) {
      Optional<ScriptDeadLetterReplayRepository.ReplayRequest> priorRequest =
          replayRepository.findRequest(
              normalizedTenantId, normalizeText(command.controlPlaneRequestId()));
      if (priorRequest.isPresent()
          && !fingerprint.equals(priorRequest.orElseThrow().requestFingerprint())) {
        throw new ReplayIdempotencyConflictException(
            "control_plane_request_id already records a different replay request");
      }
      if (priorRequest.isPresent()) {
        ScriptDeadLetterReplayRepository.ReplayRequest request = priorRequest.orElseThrow();
        priorResults =
            replayRepository.findResults(request.id()).stream()
                .collect(
                    Collectors.toMap(
                        ScriptDeadLetterReplayRepository.ReplayItem::requestedWorkItemId,
                        item -> item));
        if ("COMPLETED".equals(request.status())) {
          return replayResultFromDurable(command, request, priorResults);
        }
      }
    }
    // Authority reads must happen before any work-item row is locked. The initial lookup is a
    // tenant-scoped snapshot only; every mutation below re-reads and revalidates its current row.
    List<ScriptWorkItem> candidates = selectReplayCandidates(command, normalizedTenantId);
    Map<Long, ScriptWorkItem> byId =
        candidates.stream().collect(Collectors.toMap(ScriptWorkItem::getId, item -> item));
    Map<Long, ReplayPreflight> preflights = new HashMap<>();
    for (String requestedId : command.workItemIds()) {
      long requestedLongId = parseWorkItemId(requestedId);
      if (priorResults.containsKey(requestedLongId)) {
        continue;
      }
      ScriptWorkItem item = byId.get(requestedLongId);
      if (item == null) {
        preflights.put(requestedLongId, ReplayPreflight.notFound());
        continue;
      }
      OriginalFailureEvidence originalFailure = originalFailureEvidence(item);
      String preflightRejection = replayPreflightRejection(item, originalFailure);
      preflights.put(
          requestedLongId, new ReplayPreflight(item, originalFailure, preflightRejection));
    }

    Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> completedPreflightResults = priorResults;
    ReplayTransactionOutcome transactionOutcome =
        replayTransactionBoundary.inTransaction(
            () ->
                replayDeadLettersInTransaction(
                    command,
                    normalizedTenantId,
                    now,
                    reason,
                    fingerprint,
                    completedPreflightResults,
                    preflights));
    for (ScriptWorkItem claimedItem : transactionOutcome.claimedItems()) {
      // This refresh can consult Game Session when its local pin projection is stale. Keep that
      // authority read outside the durable replay transaction. Projection reads retry the refresh
      // from durable work-item state if this post-commit refresh fails.
      refreshRolloutProjectionAfterReplayCommit(claimedItem);
    }
    return transactionOutcome.result();
  }

  private void refreshRolloutProjectionAfterReplayCommit(ScriptWorkItem item) {
    try {
      rolloutProjectionService.refreshForWorkItem(item);
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Replay committed for work item {}, but rollout projection refresh failed; a later "
              + "projection read will retry from durable work-item state",
          item.getId(),
          ex);
    }
  }

  private ReplayTransactionOutcome replayDeadLettersInTransaction(
      ReplayDeadLettersCommand command,
      String normalizedTenantId,
      Instant now,
      String reason,
      String fingerprint,
      Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> preflightResults,
      Map<Long, ReplayPreflight> preflights) {
    ScriptDeadLetterReplayRepository.ReplayRequest durableRequest = null;
    Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> priorResults = preflightResults;
    if (replayRepository != null) {
      durableRequest =
          replayRepository.insertOrGet(
              normalizedTenantId,
              normalizeText(command.controlPlaneRequestId()),
              fingerprint,
              normalizeText(command.actorPrincipal()),
              reason,
              now);
      if (!fingerprint.equals(durableRequest.requestFingerprint())) {
        throw new ReplayIdempotencyConflictException(
            "control_plane_request_id already records a different replay request");
      }
      priorResults =
          replayRepository.findResults(durableRequest.id()).stream()
              .collect(
                  Collectors.toMap(
                      ScriptDeadLetterReplayRepository.ReplayItem::requestedWorkItemId,
                      item -> item));
      if ("COMPLETED".equals(durableRequest.status())) {
        return new ReplayTransactionOutcome(
            replayResultFromDurable(command, durableRequest, priorResults), List.of());
      }
    }
    Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> existingResults = priorResults;

    // Phase two deliberately contains no authority RPCs. Acquire every candidate lock in the
    // canonical ID order so concurrent requests cannot deadlock while claiming overlapping batches.
    Map<Long, ScriptWorkItem> lockedById = new HashMap<>();
    List<ScriptWorkItem> claimedItems = new ArrayList<>();
    command.workItemIds().stream()
        .map(ScriptWorkItemServiceImpl::parseWorkItemId)
        .sorted()
        .forEach(
            requestedLongId -> {
              if (existingResults.containsKey(requestedLongId)) {
                return;
              }
              ReplayPreflight preflight = preflights.get(requestedLongId);
              if (preflight == null || preflight.snapshot() == null) {
                return;
              }
              workItemRepository
                  .findByTenantIdAndIdForUpdate(normalizedTenantId, requestedLongId)
                  .ifPresent(item -> lockedById.put(requestedLongId, item));
            });

    // Emit and persist outcomes in caller order, while using only the already locked current rows.
    List<ReplayItemResult> results = new ArrayList<>();
    for (String requestedId : command.workItemIds()) {
      long requestedLongId = parseWorkItemId(requestedId);
      ScriptDeadLetterReplayRepository.ReplayItem prior = existingResults.get(requestedLongId);
      if (prior != null) {
        results.add(toReplayItemResult(requestedId, prior));
        continue;
      }
      ReplayPreflight preflight = preflights.get(requestedLongId);
      if (preflight == null || preflight.snapshot() == null) {
        results.add(new ReplayItemResult(requestedId, "rejected", "not_found_or_not_owned", 0L));
        persistReplayResult(
            durableRequest,
            normalizedTenantId,
            requestedLongId,
            "rejected",
            "not_found_or_not_owned",
            null,
            OriginalFailureEvidence.EMPTY,
            now);
        continue;
      }

      ScriptWorkItem item = lockedById.get(requestedLongId);
      if (item == null) {
        results.add(new ReplayItemResult(requestedId, "rejected", "not_found_or_not_owned", 0L));
        persistReplayResult(
            durableRequest,
            normalizedTenantId,
            requestedLongId,
            "rejected",
            "not_found_or_not_owned",
            null,
            OriginalFailureEvidence.EMPTY,
            now);
        continue;
      }
      OriginalFailureEvidence originalFailure = originalFailureEvidence(item);
      if (item.getRowVersion() != preflight.snapshot().getRowVersion()
          || item.getFailureGeneration() != preflight.snapshot().getFailureGeneration()) {
        String currentStateRejection = replayCurrentStateRejection(item);
        if (currentStateRejection == null) {
          currentStateRejection = "recovery_in_progress";
        }
        results.add(
            new ReplayItemResult(
                requestedId, "rejected", currentStateRejection, item.getFailureGeneration()));
        persistReplayResult(
            durableRequest,
            normalizedTenantId,
            requestedLongId,
            "rejected",
            currentStateRejection,
            item,
            originalFailure,
            now);
        continue;
      }

      String currentStatusRejection = replayCurrentStateRejection(item);
      String replayRejection =
          currentStatusRejection == null ? preflight.rejectionReason() : currentStatusRejection;
      if (replayRejection == null && !preflight.originalFailure().equals(originalFailure)) {
        // Audit evidence is mutable independently of the work-item row. Do not accept a successful
        // preflight when the current failure evidence no longer proves the same retryable class.
        replayRejection = "stage_evidence_unavailable";
      }
      if (replayRejection != null && !replayRejection.isBlank()) {
        results.add(
            new ReplayItemResult(
                requestedId, "rejected", replayRejection, item.getFailureGeneration()));
        persistReplayResult(
            durableRequest,
            normalizedTenantId,
            requestedLongId,
            "rejected",
            replayRejection,
            item,
            originalFailure,
            now);
        continue;
      }
      Optional<ScriptWorkItem> claimed =
          workItemRepository.claimDeadLetterForReplay(
              item.getId(),
              item.getTenantId(),
              item.getRowVersion(),
              item.getFailureGeneration(),
              now);
      if (claimed.isEmpty()) {
        results.add(
            new ReplayItemResult(
                requestedId, "rejected", "recovery_in_progress", item.getFailureGeneration()));
        persistReplayResult(
            durableRequest,
            normalizedTenantId,
            requestedLongId,
            "rejected",
            "recovery_in_progress",
            item,
            originalFailure,
            now);
        continue;
      }
      item = claimed.orElseThrow();
      claimedItems.add(item);
      refreshReadinessProjectionIfNeeded(item);
      AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, item, LOGGER);
      results.add(
          new ReplayItemResult(requestedId, "retried_evaluation", "", item.getFailureGeneration()));
      persistReplayResult(
          durableRequest,
          normalizedTenantId,
          requestedLongId,
          "retried_evaluation",
          "",
          item,
          originalFailure,
          now);
    }
    ReplayCounts counts = replayCounts(results);
    if (durableRequest != null) {
      boolean completionWon =
          replayRepository.complete(durableRequest.id(), counts.replayed(), counts.rejected(), now);
      if (!completionWon) {
        Optional<ScriptDeadLetterReplayRepository.ReplayRequest> completedRequest =
            replayRepository.findRequest(
                normalizedTenantId, normalizeText(command.controlPlaneRequestId()));
        if (completedRequest.filter(request -> "COMPLETED".equals(request.status())).isPresent()) {
          return new ReplayTransactionOutcome(
              replayResultFromDurable(
                  command,
                  completedRequest.orElseThrow(),
                  replayRepository.findResults(durableRequest.id()).stream()
                      .collect(
                          Collectors.toMap(
                              ScriptDeadLetterReplayRepository.ReplayItem::requestedWorkItemId,
                              item -> item))),
              List.of());
        }
      }
    }
    return new ReplayTransactionOutcome(
        new ReplayResult(counts.replayed(), counts.rejected(), results, fingerprint),
        List.copyOf(claimedItems));
  }

  private static void validateReplayCommand(ReplayDeadLettersCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("replay_request_required");
    }
    if (command.workItemIds() == null || command.workItemIds().isEmpty()) {
      throw new IllegalArgumentException("invalid_work_item_ids");
    }
    if (command.workItemIds().size() > 100) {
      throw new IllegalArgumentException("work_item_ids_limit_exceeded");
    }
    Set<String> ids = new HashSet<>();
    Set<Long> parsedIds = new HashSet<>();
    for (String id : command.workItemIds()) {
      if (id == null || id.isBlank() || !ids.add(id.strip())) {
        throw new IllegalArgumentException("invalid_work_item_ids");
      }
      if (!parsedIds.add(parseWorkItemId(id))) {
        throw new IllegalArgumentException("invalid_work_item_ids");
      }
    }
    if (!blankToEmpty(command.gameInstanceId()).isBlank()
        || !blankToEmpty(command.regionId()).isBlank()
        || !blankToEmpty(command.scriptPatchVersion()).isBlank()
        || command.createdAfterMs() != 0
        || command.createdBeforeMs() != 0
        || command.limit() != 0) {
      throw new IllegalArgumentException("replay_filters_require_preview");
    }
    String controlPlaneRequestId = normalizeText(command.controlPlaneRequestId());
    if (controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    requireReplayTextLength(
        controlPlaneRequestId,
        REPLAY_CONTROL_PLANE_REQUEST_ID_MAX_LENGTH,
        "control_plane_request_id");
    requireReplayTextLength(
        normalizeText(command.actorPrincipal()),
        REPLAY_ACTOR_PRINCIPAL_MAX_LENGTH,
        "actor_principal");
    requireReplayTextLength(
        normalizeReplayReason(command.reason()), REPLAY_REASON_MAX_LENGTH, "reason");
  }

  static String replayRequestFingerprint(ReplayDeadLettersCommand command) {
    List<String> normalizedWorkItemIds =
        command.workItemIds().stream()
            // Work-item IDs are numeric identities.  Canonicalize accepted alternate spellings
            // (for example, 01 and +1) before sorting and hashing so an exact logical retry does
            // not become an idempotency conflict.
            .map(id -> Long.toString(parseWorkItemId(id)))
            .sorted()
            .toList();
    // replayDeadLetteredWorkItems/v1 uses UTF-8 byte-length framing.  The version label is part of
    // the preimage so a future encoding change cannot silently reinterpret retained fingerprints.
    StringBuilder canonical = new StringBuilder();
    appendReplayFingerprintSegment(canonical, "replayDeadLetteredWorkItems/v1");
    appendReplayFingerprintSegment(canonical, normalizeText(command.tenantId()));
    appendReplayFingerprintSegment(canonical, Integer.toString(normalizedWorkItemIds.size()));
    normalizedWorkItemIds.forEach(id -> appendReplayFingerprintSegment(canonical, id));
    appendReplayFingerprintSegment(canonical, normalizeText(command.controlPlaneRequestId()));
    appendReplayFingerprintSegment(canonical, normalizeText(command.actorPrincipal()));
    appendReplayFingerprintSegment(canonical, normalizeReplayReason(command.reason()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder fingerprint = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        fingerprint.append(String.format("%02x", value));
      }
      return fingerprint.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for replay request identity", e);
    }
  }

  private static void appendReplayFingerprintSegment(StringBuilder canonical, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    canonical.append(bytes.length).append(':').append(value);
  }

  private PatchInstanceRolloutSummary withPublication(
      String tenantId,
      PatchInstanceRolloutSummary summary,
      PublicationMetadataCache publicationMetadataCache) {
    return new PatchInstanceRolloutSummary(
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
        summary.scriptPinEpoch(),
        summary.lastObservedControlPlaneRequestId(),
        summary.rolloutStatus(),
        summary.statusReason(),
        summary.lastChangedAtMs(),
        summary.projectionAsOfMs(),
        summary.projectionLagMs(),
        summary.projectionStale(),
        publicationMetadataCache.get(tenantId, summary.scriptPatchVersion()).publication());
  }

  private static void requireNonNegativeScriptPinEpoch(long scriptPinEpoch) {
    if (scriptPinEpoch < 0) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
  }

  private static void requireCompletePinTuple(long scriptPinEpoch, String requestId) {
    boolean hasEpoch = scriptPinEpoch > 0L;
    boolean hasRequestId = requestId != null && !requestId.isBlank();
    if (hasEpoch != hasRequestId) {
      throw new IllegalArgumentException(
          "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    }
  }

  private static long projectionPinEpoch(long scriptPinEpoch) {
    return scriptPinEpoch > 0L ? scriptPinEpoch : 0L;
  }

  private static String projectionPinRequestId(long scriptPinEpoch, String requestId) {
    return scriptPinEpoch > 0L ? requestId : null;
  }

  private PatchInstanceRolloutEventSummary withPublication(
      String tenantId,
      PatchInstanceRolloutEventSummary summary,
      PublicationMetadataCache publicationMetadataCache) {
    return new PatchInstanceRolloutEventSummary(
        summary.eventId(),
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
        summary.scriptPinEpoch(),
        summary.lastObservedControlPlaneRequestId(),
        summary.rolloutStatus(),
        summary.statusReason(),
        summary.observedAtMs(),
        summary.projectionAsOfMs(),
        publicationMetadataCache.get(tenantId, summary.scriptPatchVersion()).publication());
  }

  private DeadLetterSummary withPublication(
      String tenantId,
      DeadLetterSummary summary,
      PublicationMetadataCache publicationMetadataCache) {
    PluginRuntimeStateService.PluginPublicationLink pluginPublication =
        pluginPublicationLink(tenantId, summary.pluginId(), summary.pluginVersionId());
    return new DeadLetterSummary(
        summary.workItemId(),
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.regionId(),
        summary.regionEpoch(),
        summary.entityId(),
        summary.playableStateScope(),
        summary.worldSlug(),
        summary.realmSlug(),
        summary.pointerVersion(),
        summary.sourceKind(),
        summary.sourceState(),
        summary.sourceOrdinal(),
        summary.sourceDueTickId(),
        summary.sourceDueAtMs(),
        summary.scriptId(),
        summary.pluginId(),
        summary.pluginVersionId(),
        summary.eventType(),
        summary.scriptPatchVersion(),
        summary.scriptPatchBaseVersionId(),
        summary.scriptPinEpoch(),
        summary.scriptPinControlPlaneRequestId(),
        summary.scriptEventId(),
        summary.status(),
        summary.reason(),
        summary.createdAtMs(),
        summary.updatedAtMs(),
        publicationMetadataCache
            .get(tenantId, summary.scriptPatchBaseVersionId(), summary.scriptPatchVersion())
            .publication(),
        pluginPublication);
  }

  private HandoffEventSummary withPublication(
      String tenantId,
      HandoffEventSummary summary,
      PublicationMetadataCache publicationMetadataCache) {
    PluginRuntimeStateService.PluginPublicationLink pluginPublication =
        pluginPublicationLink(tenantId, summary.pluginId(), summary.pluginVersionId());
    return new HandoffEventSummary(
        summary.eventId(),
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
        summary.scriptPinEpoch(),
        summary.scriptPinControlPlaneRequestId(),
        summary.scriptId(),
        summary.pluginId(),
        summary.pluginVersionId(),
        summary.workItemId(),
        summary.commandOrdinal(),
        summary.automationDispatchId(),
        summary.gameSessionCommandId(),
        summary.targetGameInstanceId(),
        summary.targetRegionId(),
        summary.targetRegionEpoch(),
        summary.remoteCoordinatorId(),
        summary.remoteFollowupId(),
        summary.targetEntityId(),
        summary.playableStateScope(),
        summary.worldSlug(),
        summary.realmSlug(),
        summary.pointerVersion(),
        summary.sourceKind(),
        summary.sourceState(),
        summary.sourceOrdinal(),
        summary.sourceDueTickId(),
        summary.sourceDueAtMs(),
        summary.emittedCommandText(),
        summary.handoffOutcome(),
        summary.handoffReason(),
        summary.observedAtMs(),
        publicationMetadataCache.get(tenantId, summary.scriptPatchVersion()).publication(),
        pluginPublication);
  }

  private PublicationMetadata publicationMetadata(String tenantId, String scriptPatchVersion) {
    // The authored patch identity is bound once to an immutable base. A later readiness
    // projection cannot rewrite the base used to render a historical rollout or handoff.
    Long retainedBaseVersionId =
        scriptDefinitionRepository
            .findScriptPatchBaseVersionId(tenantId, scriptPatchVersion)
            .orElse(null);
    if (retainedBaseVersionId == null || retainedBaseVersionId <= 0L) {
      return PublicationMetadata.lookupFailure(
          scriptPatchVersion,
          "PUBLICATION_SCOPE_UNAVAILABLE",
          "immutable script-patch base binding is unavailable for exact publication lookup");
    }
    return publicationMetadata(tenantId, retainedBaseVersionId, scriptPatchVersion);
  }

  private final class PublicationMetadataCache {
    private final Map<PublicationMetadataKey, PublicationMetadata> entries = new HashMap<>();

    private PublicationMetadata get(String tenantId, String scriptPatchVersion) {
      PublicationMetadataKey key = new PublicationMetadataKey(tenantId, scriptPatchVersion, null);
      return entries.computeIfAbsent(
          key, ignored -> publicationMetadata(tenantId, scriptPatchVersion));
    }

    private PublicationMetadata get(
        String tenantId, long baseVersionId, String scriptPatchVersion) {
      PublicationMetadataKey key =
          new PublicationMetadataKey(tenantId, scriptPatchVersion, baseVersionId);
      return entries.computeIfAbsent(
          key, ignored -> publicationMetadata(tenantId, baseVersionId, scriptPatchVersion));
    }
  }

  private record PublicationMetadataKey(
      String tenantId, String scriptPatchVersion, Long baseVersionId) {}

  private PublicationMetadata publicationMetadata(
      String tenantId, long requestedBaseVersionId, String scriptPatchVersion) {
    if (requestedBaseVersionId <= 0L) {
      return PublicationMetadata.lookupFailure(
          scriptPatchVersion,
          "PUBLICATION_SCOPE_UNAVAILABLE",
          "base_version_id is unavailable for exact script-patch publication lookup");
    }
    GetPublishedScriptPatchVersionResponse scriptPatchResponse =
        gameDesignControlPlaneClient.getPublishedScriptPatchVersion(
            tenantId, requestedBaseVersionId, scriptPatchVersion);
    if (scriptPatchResponse.hasError() && !scriptPatchResponse.getError().getCode().isBlank()) {
      return PublicationMetadata.lookupFailure(
          scriptPatchVersion,
          scriptPatchResponse.getError().getCode(),
          scriptPatchResponse.getError().getMessage());
    }
    long baseVersionId = scriptPatchResponse.getScriptPatch().getBaseVersionId();
    if (baseVersionId != requestedBaseVersionId
        || !scriptPatchVersion.equals(
            blankToEmpty(scriptPatchResponse.getScriptPatch().getScriptPatchVersion()))) {
      return PublicationMetadata.lookupFailure(
          scriptPatchVersion,
          "FAILED_PRECONDITION",
          "published script-patch identity does not match the exact requested base");
    }
    ScriptPatchPublicationLink publication =
        new ScriptPatchPublicationLink(
            blankToEmpty(scriptPatchResponse.getScriptPatch().getScriptPatchVersion()),
            scriptPatchResponse.getScriptPatch().getVersionId(),
            baseVersionId,
            scriptPatchResponse.getScriptPatch().getPublicationState(),
            scriptPatchResponse.getScriptPatch().getLastChangedAtMs(),
            "",
            "");
    if (baseVersionId <= 0) {
      return new PublicationMetadata(0L, "", publication);
    }
    GetPublishedReleaseBundleResponse releaseBundleResponse =
        gameDesignControlPlaneClient.getPublishedReleaseBundle(tenantId, baseVersionId);
    if (releaseBundleResponse.hasError() && !releaseBundleResponse.getError().getCode().isBlank()) {
      return new PublicationMetadata(baseVersionId, "", publication);
    }
    String abilitySchemaDigest =
        releaseBundleResponse.getBundle().getParticipantDigestsList().stream()
            .filter(
                digest -> PARTICIPANT_KEY_AUTOMATION_SCRIPTING.equals(digest.getParticipantKey()))
            .map(ParticipantDigest::getContentDigest)
            .findFirst()
            .orElse("");
    return new PublicationMetadata(baseVersionId, abilitySchemaDigest, publication);
  }

  private PluginRuntimeStateService.PluginPublicationLink pluginPublicationLink(
      String tenantId, String pluginId, String pluginVersionId) {
    if (blankToEmpty(pluginId).isBlank() || blankToEmpty(pluginVersionId).isBlank()) {
      return null;
    }
    var pluginResponse =
        gameDesignControlPlaneClient.getPublishedPluginVersion(tenantId, pluginId, pluginVersionId);
    if (pluginResponse == null) {
      return new PluginRuntimeStateService.PluginPublicationLink(
          pluginVersionId,
          0L,
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
          "",
          0L,
          "GAME_DESIGN_UNAVAILABLE",
          "Game Design service unavailable");
    }
    if (pluginResponse.hasError() && !pluginResponse.getError().getCode().isBlank()) {
      return new PluginRuntimeStateService.PluginPublicationLink(
          pluginVersionId,
          0L,
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
          "",
          0L,
          pluginResponse.getError().getCode(),
          pluginResponse.getError().getMessage());
    }
    return new PluginRuntimeStateService.PluginPublicationLink(
        blankToEmpty(pluginResponse.getPluginVersion().getPluginVersionId()),
        pluginResponse.getPluginVersion().getPublicationId(),
        pluginResponse.getPluginVersion().getPublicationState(),
        blankToEmpty(pluginResponse.getPluginVersion().getStatusReason()),
        pluginResponse.getPluginVersion().getLastChangedAtMs(),
        "",
        "");
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String normalizeText(String value) {
    return value == null || value.isBlank() ? "" : value.strip();
  }

  private record PublicationMetadata(
      long baseVersionId, String abilitySchemaDigest, ScriptPatchPublicationLink publication) {
    private PublicationMetadata {
      abilitySchemaDigest = abilitySchemaDigest == null ? "" : abilitySchemaDigest;
    }

    private static PublicationMetadata lookupFailure(
        String scriptPatchVersion, String errorCode, String errorMessage) {
      return new PublicationMetadata(
          0L,
          "",
          new ScriptPatchPublicationLink(
              blankToEmpty(scriptPatchVersion),
              0L,
              0L,
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
              0L,
              blankToEmpty(errorCode),
              blankToEmpty(errorMessage)));
    }
  }

  private static DeadLetterSummary toDeadLetterSummary(ScriptWorkItem item) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            item.getWorldSlug(), item.getRealmSlug(), item.getPointerVersion());
    return new DeadLetterSummary(
        item.getId().toString(),
        item.getTenantId(),
        item.getGameInstanceId(),
        item.getRegionId(),
        item.getRegionEpoch(),
        item.getEntityId(),
        blankToEmpty(item.getPlayableStateScope()),
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion(),
        blankToEmpty(item.getSourceKind()),
        blankToEmpty(item.getSourceState()),
        zeroIfNull(item.getSourceOrdinal()),
        zeroIfNull(item.getSourceDueTickId()),
        zeroIfNull(item.getSourceDueAtMs()),
        item.getScriptId(),
        blankToEmpty(item.getPluginId()),
        blankToEmpty(item.getPluginVersionId()),
        item.getEventType(),
        item.getScriptPatchVersion(),
        item.getScriptPatchBaseVersionId() == null ? 0L : item.getScriptPatchBaseVersionId(),
        item.getScriptPinEpoch(),
        blankToEmpty(item.getScriptPinControlPlaneRequestId()),
        item.getScriptEventId(),
        item.getStatus(),
        item.getCancelReason() == null ? "" : item.getCancelReason(),
        item.getCreatedAt().toEpochMilli(),
        item.getUpdatedAt().toEpochMilli(),
        null,
        null);
  }

  private static HandoffEventSummary toHandoffSummary(ScriptHandoffEvent event) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            event.getWorldSlug(), event.getRealmSlug(), event.getPointerVersion());
    return new HandoffEventSummary(
        event.getEventId(),
        event.getTenantId(),
        event.getGameInstanceId(),
        event.getScriptPatchVersion(),
        event.getScriptPinEpoch(),
        blankToEmpty(event.getScriptPinControlPlaneRequestId()),
        event.getScriptId(),
        blankToEmpty(event.getPluginId()),
        blankToEmpty(event.getPluginVersionId()),
        Long.toString(event.getWorkItemId()),
        event.getCommandOrdinal(),
        event.getAutomationDispatchId(),
        blankToEmpty(event.getGameSessionCommandId()),
        blankToEmpty(event.getTargetGameInstanceId()),
        blankToEmpty(event.getTargetRegionId()),
        event.getTargetRegionEpoch(),
        blankToEmpty(event.getRemoteCoordinatorId()),
        blankToEmpty(event.getRemoteFollowupId()),
        event.getTargetEntityId(),
        blankToEmpty(event.getPlayableStateScope()),
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion(),
        blankToEmpty(event.getSourceKind()),
        blankToEmpty(event.getSourceState()),
        zeroIfNull(event.getSourceOrdinal()),
        zeroIfNull(event.getSourceDueTickId()),
        zeroIfNull(event.getSourceDueAtMs()),
        event.getEmittedCommandText(),
        event.getHandoffOutcome(),
        event.getHandoffReason(),
        event.getObservedAt().toEpochMilli(),
        null,
        null);
  }

  private List<ScriptWorkItem> selectReplayCandidates(
      ReplayDeadLettersCommand command, String normalizedTenantId) {
    List<Long> requestedIds =
        command.workItemIds().stream()
            .map(ScriptWorkItemServiceImpl::parseWorkItemId)
            .sorted()
            .toList();
    Map<Long, ScriptWorkItem> snapshotsById =
        workItemRepository
            .findByTenantIdAndIdInOrderByIdAsc(normalizedTenantId, requestedIds)
            .stream()
            .filter(item -> normalizedTenantId.equals(item.getTenantId()))
            .collect(Collectors.toMap(ScriptWorkItem::getId, item -> item));
    return requestedIds.stream().map(snapshotsById::get).filter(Objects::nonNull).toList();
  }

  private String replayPreflightRejection(
      ScriptWorkItem item, OriginalFailureEvidence originalFailure) {
    String currentStatusRejection = replayCurrentStateRejection(item);
    if (currentStatusRejection != null) {
      return currentStatusRejection;
    }
    // Use a separate cache per candidate. Reusing an authority result across a batch would make
    // the later candidate depend on an unboundedly older authority read.
    String authorityRejection = replayEligibilityReason(item, new HashMap<>());
    if (authorityRejection != null) {
      return authorityRejection;
    }
    if (!originalFailure.isRetryableForEvaluationReplay()) {
      // Only persisted failure classes known to be retryable may re-enter the DSL. A missing,
      // unknown, contradictory, or deterministic logical outcome stays dead-lettered; accepting
      // it would lose the original outcome as later execution updates the mutable audit row.
      return "stage_evidence_unavailable";
    }
    if (!"ADMISSION".equals(originalFailure.stage())
        && !"DSL_EVAL".equals(originalFailure.stage())) {
      // The current work-item row has no committed evaluated-output/child ledger. A handoff
      // failure may have accepted earlier siblings, so re-entering the DSL would invent new
      // output rather than resume the original children. Keep this generation dead-lettered.
      return "stage_evidence_unavailable";
    }
    return null;
  }

  private static String replayCurrentStateRejection(ScriptWorkItem item) {
    if (STATUS_DEAD_LETTERED.equals(item.getStatus())) {
      return null;
    }
    return switch (blankToEmpty(item.getStatus())) {
      case STATUS_PENDING_EVALUATION, STATUS_EVALUATING, STATUS_HANDOFF_IN_FLIGHT ->
          "recovery_in_progress";
      default -> "work_item_not_dead_lettered";
    };
  }

  String replayEligibilityReason(
      ScriptWorkItem item,
      Map<RuntimeScopeKey, Optional<GetGameInstanceRuntimeStateResponse>> runtimeStateCache) {
    if ("onLoad".equals(item.getEventType())) {
      return "onload_not_replayable";
    }
    String localFenceFailure = ScriptWorkItemFenceEvaluationSupport.validateRuntimeIdentity(item);
    if (localFenceFailure != null) {
      return localFenceFailure;
    }
    if (gameSessionControlPlaneClient == null) {
      return "script_pin_authority_collaborator_unavailable";
    }
    RuntimeScopeKey runtimeScopeKey =
        new RuntimeScopeKey(item.getTenantId(), item.getGameInstanceId(), item.getRegionId());
    // Transient read failures must escape so this transactional request can be retried by ID;
    // missing or incoherent authority responses below remain durable fail-closed rejections.
    Optional<GetGameInstanceRuntimeStateResponse> cachedRuntime =
        runtimeStateCache.computeIfAbsent(
            runtimeScopeKey,
            ignored ->
                Optional.ofNullable(
                    gameSessionControlPlaneClient.getGameInstanceRuntimeState(
                        item.getTenantId(), item.getGameInstanceId(), item.getRegionId())));
    if (cachedRuntime.isEmpty()) {
      return "script_pin_authority_unavailable";
    }
    GetGameInstanceRuntimeStateResponse runtime = cachedRuntime.orElseThrow();
    String runtimeFailure =
        ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(item, runtime);
    if (runtimeFailure != null) {
      return runtimeFailure;
    }
    String capturedPluginFailure =
        ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(item);
    if (capturedPluginFailure != null) {
      return capturedPluginFailure;
    }
    if (ScriptWorkItemFenceEvaluationSupport.normalize(item.getPluginId()).isBlank()) {
      return null;
    }
    if (pluginRuntimeStateService == null) {
      return "plugin_lifecycle_collaborator_unavailable";
    }
    String pluginId = ScriptWorkItemFenceEvaluationSupport.normalize(item.getPluginId());
    Optional<PluginRuntimeStateService.PluginRuntimeStatus> plugin =
        pluginRuntimeStateService.getStatus(item.getTenantId(), item.getGameInstanceId(), pluginId);
    if (plugin == null || plugin.isEmpty()) {
      return "authority_unavailable";
    }
    PluginRuntimeStateService.PluginRuntimeStatus pluginStatus = plugin.orElseThrow();
    if (pluginStatus.pluginState() == null
        || pluginStatus.pluginState() == PluginState.PLUGIN_STATE_UNSPECIFIED) {
      return "authority_unavailable";
    }
    return ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
        item,
        pluginStatus.activePluginVersionId(),
        pluginStatus.pluginState(),
        pluginStatus.pluginActivationEpoch(),
        pluginStatus.lifecycleRevision());
  }

  private void persistReplayResult(
      ScriptDeadLetterReplayRepository.ReplayRequest request,
      String tenantId,
      long requestedWorkItemId,
      String outcome,
      String rejectionReason,
      ScriptWorkItem item,
      OriginalFailureEvidence originalFailure,
      Instant now) {
    if (request == null) {
      return;
    }
    replayRepository.saveResult(
        tenantId,
        request.id(),
        requestedWorkItemId,
        item == null ? null : item.getId(),
        outcome,
        rejectionReason,
        "",
        item == null ? 0L : item.getScriptPinEpoch(),
        item == null ? 0L : item.getPluginActivationEpoch(),
        item == null ? 0L : item.getLifecycleRevision(),
        item == null ? 0L : item.getFailureGeneration(),
        originalFailure.stage(),
        originalFailure.reason(),
        now);
  }

  private OriginalFailureEvidence originalFailureEvidence(ScriptWorkItem item) {
    if (item == null || item.getId() == null) {
      return OriginalFailureEvidence.EMPTY;
    }
    String workItemReason = blankToEmpty(item.getCancelReason());
    return auditRepository
        .findByWorkItemId(item.getId())
        .map(
            audit -> {
              String auditReason = blankToEmpty(audit.getFinalReason());
              boolean consistent =
                  auditReason.isBlank()
                      || workItemReason.isBlank()
                      || auditReason.equals(workItemReason);
              return new OriginalFailureEvidence(
                  blankToEmpty(audit.getFinalStage()),
                  auditReason,
                  blankToEmpty(audit.getFinalOutcome()),
                  consistent);
            })
        .orElse(new OriginalFailureEvidence("", workItemReason, "", true));
  }

  private ReplayResult replayResultFromDurable(
      ReplayDeadLettersCommand command,
      ScriptDeadLetterReplayRepository.ReplayRequest request,
      Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> persisted) {
    List<ReplayItemResult> results =
        command.workItemIds().stream()
            .map(
                id -> {
                  ScriptDeadLetterReplayRepository.ReplayItem result =
                      persisted.get(parseWorkItemId(id));
                  return result == null
                      ? new ReplayItemResult(id, "rejected", "replay_result_missing", 0L)
                      : toReplayItemResult(id, result);
                })
            .toList();
    ReplayCounts counts = replayCounts(results);
    return new ReplayResult(
        counts.replayed(), counts.rejected(), results, request.requestFingerprint());
  }

  private static ReplayCounts replayCounts(List<ReplayItemResult> results) {
    long replayed =
        results.stream()
            .filter(
                result ->
                    "retried_evaluation".equals(result.outcome())
                        || "resumed_dispatch".equals(result.outcome()))
            .count();
    long rejected = results.stream().filter(result -> "rejected".equals(result.outcome())).count();
    return new ReplayCounts(replayed, rejected);
  }

  private record ReplayCounts(long replayed, long rejected) {}

  private record ReplayTransactionOutcome(ReplayResult result, List<ScriptWorkItem> claimedItems) {}

  private record ReplayPreflight(
      ScriptWorkItem snapshot, OriginalFailureEvidence originalFailure, String rejectionReason) {
    private static ReplayPreflight notFound() {
      return new ReplayPreflight(null, OriginalFailureEvidence.EMPTY, "not_found_or_not_owned");
    }
  }

  private static ReplayItemResult toReplayItemResult(
      String requestedId, ScriptDeadLetterReplayRepository.ReplayItem stored) {
    String outcome = blankToEmpty(stored.outcome());
    String rejectionReason = normalizeText(stored.rejectionReason());
    String failureReason = normalizeText(stored.failureReason());
    if ("retried_evaluation".equals(outcome)
        || "resumed_dispatch".equals(outcome)
        || "already_recovered".equals(outcome)
        || "recovery_failed".equals(outcome)) {
      return new ReplayItemResult(
          requestedId, outcome, rejectionReason, failureReason, stored.failureGeneration());
    }
    if ("retried_evaluation_unknown".equals(outcome)) {
      return new ReplayItemResult(
          requestedId, "rejected", "stage_evidence_unavailable", "", stored.failureGeneration());
    }
    return new ReplayItemResult(
        requestedId,
        "rejected",
        rejectionReason.isBlank() ? "stage_evidence_unavailable" : rejectionReason,
        "",
        stored.failureGeneration());
  }

  private void refreshReadinessProjectionIfNeeded(ScriptWorkItem item) {
    if (!"onLoad".equals(item.getEventType())) {
      return;
    }
    readinessProjectionService.refreshFromOnLoadWorkItems(
        item.getTenantId(), item.getScriptPatchVersion());
  }

  private void refreshReadinessProjectionsIfNeeded(List<ScriptWorkItem> items) {
    items.stream()
        .filter(item -> "onLoad".equals(item.getEventType()))
        .map(item -> item.getTenantId() + "\u0000" + item.getScriptPatchVersion())
        .collect(Collectors.toSet())
        .forEach(
            key -> {
              int delimiter = key.indexOf('\u0000');
              readinessProjectionService.refreshFromOnLoadWorkItems(
                  key.substring(0, delimiter), key.substring(delimiter + 1));
            });
  }

  private void cancel(ScriptWorkItem item, String reason, Instant now) {
    item.setStatus(STATUS_CANCELED);
    item.setCancelReason(reason);
    item.setUpdatedAt(now);
    auditRepository
        .findByWorkItemId(item.getId())
        .ifPresent(
            audit -> {
              audit.setFinalStage("ADMISSION");
              audit.setFinalOutcome("canceled");
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  record RuntimeScopeKey(String tenantId, String gameInstanceId, String regionId) {}

  private record OriginalFailureEvidence(
      String stage, String reason, String outcome, boolean consistent) {
    private static final OriginalFailureEvidence EMPTY =
        new OriginalFailureEvidence("", "", "", true);

    private OriginalFailureEvidence {
      stage = blankToEmpty(stage);
      reason = blankToEmpty(reason);
      outcome = blankToEmpty(outcome);
    }

    private boolean isRetryableForEvaluationReplay() {
      if (!consistent || stage.isBlank() || reason.isBlank() || outcome.isBlank()) {
        return false;
      }
      return ("ADMISSION".equals(stage)
              && "authority_unavailable_exhausted".equals(outcome)
              && "authority_unavailable_exhausted".equals(reason))
          || (("ADMISSION".equals(stage) || "DSL_EVAL".equals(stage))
              && "infrastructure_error".equals(outcome)
              && "authority_unavailable".equals(reason));
    }
  }

  private static String normalizeReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_cancel" : reason;
  }

  private static String normalizeReplayReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_replay" : reason.strip();
  }

  private static void requireReplayTextLength(String value, int maxLength, String fieldName) {
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          fieldName + " must be at most " + maxLength + " characters");
    }
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private static long parseWorkItemId(String workItemId) {
    return RequestIdValidation.requirePositiveLong(workItemId, "work_item_id");
  }

  private static Long parseOptionalWorkItemId(String workItemId) {
    String normalized = blankToEmpty(workItemId);
    return normalized.isBlank() ? null : parseWorkItemId(normalized);
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }
}
