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
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are internal Spring collaborators")
public class ScriptWorkItemServiceImpl implements ScriptWorkItemService {
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
  private static final int CANCELLATION_PAGE_SIZE = 100;
  private static final int DEAD_LETTER_CLEANUP_PAGE_SIZE = 500;
  private final AtomicLong retentionBlockedRows = new AtomicLong();
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
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

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
      MeterRegistry meterRegistry) {
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
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.meterRegistry = meterRegistry;
    Gauge.builder("automation_retention_blocked_rows", retentionBlockedRows, AtomicLong::get)
        .register(meterRegistry);
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
      if (candidates.size() < CANCELLATION_PAGE_SIZE) {
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
      if (candidates.size() < CANCELLATION_PAGE_SIZE) {
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
            STATUS_PENDING_EVALUATION, PageRequest.of(0, maxItems));
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
    // Child evidence is retained through the longest local retry/replay horizon.  Disposition is
    // deliberately ordered before parent deletion: replay results -> audit/handoff children ->
    // terminal work item.  A hold is enforced by each child repository.
    long replayRetentionSeconds =
        Math.max(
            Math.max(
                Math.multiplyExact((long) outboxProperties.getHandedOffRetentionDays(), 86_400L),
                Math.multiplyExact((long) outboxProperties.getCanceledRetentionDays(), 86_400L)),
            outboxProperties.getDeadLetterMaxAgeSeconds());
    Instant evidenceWatermark = now.minusSeconds(replayRetentionSeconds);
    long replayResultsDisposed = 0L;
    long replayRequestsDisposed = 0L;
    if (replayRepository != null) {
      replayResultsDisposed = replayRepository.deleteExpiredResults(evidenceWatermark, now);
      replayRequestsDisposed = replayRepository.deleteExpiredRequests(evidenceWatermark, now);
    }
    long auditDisposed = auditRepository.deleteExpiredRetentionEvidence(evidenceWatermark, now);
    long handoffDisposed =
        handoffEventRepository.deleteExpiredRetentionEvidence(evidenceWatermark, now);
    long handedOffDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_HANDED_OFF,
            now.minus(outboxProperties.getHandedOffRetentionDays(), ChronoUnit.DAYS));
    long canceledDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_CANCELED,
            now.minus(outboxProperties.getCanceledRetentionDays(), ChronoUnit.DAYS));
    long deadLetteredDeleted =
        workItemRepository.deleteByStatusAndUpdatedAtBefore(
            STATUS_DEAD_LETTERED,
            now.minus(outboxProperties.getDeadLetterMaxAgeSeconds(), ChronoUnit.SECONDS));
    deadLetteredDeleted += deleteExcessDeadLetters();
    long blocked =
        workItemRepository.countTerminalRowsBlockedByEvidence(
                STATUS_HANDED_OFF,
                now.minus(outboxProperties.getHandedOffRetentionDays(), ChronoUnit.DAYS))
            + workItemRepository.countTerminalRowsBlockedByEvidence(
                STATUS_CANCELED,
                now.minus(outboxProperties.getCanceledRetentionDays(), ChronoUnit.DAYS))
            + workItemRepository.countTerminalRowsBlockedByEvidence(
                STATUS_DEAD_LETTERED,
                now.minus(outboxProperties.getDeadLetterMaxAgeSeconds(), ChronoUnit.SECONDS));
    retentionBlockedRows.set(blocked);
    if (replayResultsDisposed + replayRequestsDisposed + auditDisposed + handoffDisposed > 0) {
      meterRegistry
          .counter("automation_retention_disposed_total")
          .increment(
              replayResultsDisposed + replayRequestsDisposed + auditDisposed + handoffDisposed);
    }
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
              PublicationMetadata metadata = publicationMetadata(tenantId, scriptPatchVersion);
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
    return readinessProjectionService.listProjections(tenantId).stream()
        .map(
            readiness -> {
              PublicationMetadata metadata =
                  publicationMetadata(tenantId, readiness.scriptPatchVersion());
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
                    "NOT_FOUND",
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
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    requireText(scriptPatchVersion, "script_patch_version");
    return rolloutProjectionService
        .getProjection(normalizedTenantId, gameInstanceId, scriptPatchVersion)
        .map(summary -> withPublication(normalizedTenantId, summary));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PatchInstanceRolloutSummary> listPatchInstanceRollouts(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    return rolloutProjectionService
        .listProjections(
            normalizedTenantId,
            gameInstanceId,
            scriptPatchVersion,
            rolloutStatus,
            changedAfterMs,
            changedBeforeMs)
        .stream()
        .map(summary -> withPublication(normalizedTenantId, summary))
        .toList();
  }

  @Override
  public List<PatchInstanceRolloutEventSummary> listPatchInstanceRolloutEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    return rolloutProjectionService
        .listEvents(
            normalizedTenantId,
            gameInstanceId,
            scriptPatchVersion,
            rolloutStatus,
            changedAfterMs,
            changedBeforeMs,
            limit)
        .stream()
        .map(summary -> withPublication(normalizedTenantId, summary))
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
        .map(summary -> withPublication(normalizedTenantId, summary))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeadLetterSummary> listDeadLetters(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit) {
    String normalizedTenantId = normalizeText(tenantId);
    requireText(normalizedTenantId, "tenant_id");
    int boundedLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 500);
    return workItemRepository
        .findDeadLetters(
            normalizedTenantId,
            STATUS_DEAD_LETTERED,
            normalizeText(gameInstanceId),
            scriptPatchVersion,
            PageRequest.of(0, boundedLimit))
        .stream()
        .map(ScriptWorkItemServiceImpl::toDeadLetterSummary)
        .map(summary -> withPublication(normalizedTenantId, summary))
        .toList();
  }

  @Override
  @Transactional
  public ReplayResult replayDeadLetters(ReplayDeadLettersCommand command) {
    validateReplayCommand(command);
    String normalizedTenantId = normalizeText(command.tenantId());
    requireText(normalizedTenantId, "tenant_id");
    int boundedLimit = command.workItemIds().size();
    Instant now = Instant.now();
    String reason = normalizeReplayReason(command.reason());
    String fingerprint = replayRequestFingerprint(command);
    ScriptDeadLetterReplayRepository.ReplayRequest durableRequest = null;
    Map<Long, ScriptDeadLetterReplayRepository.ReplayItem> priorResults = Map.of();
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
        throw new IllegalArgumentException(
            "control_plane_request_id already records a different replay request");
      }
      priorResults =
          replayRepository.findResults(durableRequest.id()).stream()
              .collect(
                  Collectors.toMap(
                      ScriptDeadLetterReplayRepository.ReplayItem::requestedWorkItemId,
                      item -> item));
      if ("COMPLETED".equals(durableRequest.status())) {
        return replayResultFromDurable(command, durableRequest, priorResults);
      }
    }
    List<ScriptWorkItem> candidates =
        selectReplayCandidates(command, normalizedTenantId, boundedLimit);
    Map<Long, ScriptWorkItem> byId =
        candidates.stream().collect(Collectors.toMap(ScriptWorkItem::getId, item -> item));
    List<ReplayItemResult> results = new ArrayList<>();
    Map<RuntimeScopeKey, Optional<GetGameInstanceRuntimeStateResponse>> runtimeStateCache =
        new HashMap<>();
    for (String requestedId : command.workItemIds()) {
      long requestedLongId = parseWorkItemId(requestedId);
      ScriptDeadLetterReplayRepository.ReplayItem prior = priorResults.get(requestedLongId);
      if (prior != null) {
        results.add(toReplayItemResult(requestedId, prior));
        continue;
      }
      ScriptWorkItem item = byId.get(requestedLongId);
      if (item == null) {
        results.add(new ReplayItemResult(requestedId, "rejected", "not_found_or_not_owned", 0L));
        persistReplayResult(
            durableRequest, requestedLongId, "rejected", "not_found_or_not_owned", null, now);
        continue;
      }
      if (!STATUS_DEAD_LETTERED.equals(item.getStatus())) {
        String reasonForCurrentStatus =
            switch (blankToEmpty(item.getStatus())) {
              case STATUS_PENDING_EVALUATION, STATUS_EVALUATING, STATUS_HANDOFF_IN_FLIGHT ->
                  "recovery_in_progress";
              default -> "work_item_not_dead_lettered";
            };
        results.add(
            new ReplayItemResult(
                requestedId, "rejected", reasonForCurrentStatus, item.getFailureGeneration()));
        persistReplayResult(
            durableRequest, requestedLongId, "rejected", reasonForCurrentStatus, item, now);
        continue;
      }
      String replayRejection = replayEligibilityReason(item, runtimeStateCache);
      if (replayRejection != null) {
        results.add(
            new ReplayItemResult(
                requestedId, "rejected", replayRejection, item.getFailureGeneration()));
        persistReplayResult(
            durableRequest, requestedLongId, "rejected", replayRejection, item, now);
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
            durableRequest, requestedLongId, "rejected", "recovery_in_progress", item, now);
        continue;
      }
      item = claimed.orElseThrow();
      refreshReadinessProjectionIfNeeded(item);
      rolloutProjectionService.refreshForWorkItem(item);
      markReplayQueued(item.getId(), reason, now);
      results.add(
          new ReplayItemResult(requestedId, "retried_evaluation", "", item.getFailureGeneration()));
      persistReplayResult(durableRequest, requestedLongId, "retried_evaluation", "", item, now);
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
          return replayResultFromDurable(
              command,
              completedRequest.orElseThrow(),
              replayRepository.findResults(durableRequest.id()).stream()
                  .collect(
                      Collectors.toMap(
                          ScriptDeadLetterReplayRepository.ReplayItem::requestedWorkItemId,
                          item -> item)));
        }
      }
    }
    return new ReplayResult(counts.replayed(), counts.rejected(), results, fingerprint);
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
        || command.createdAfterMs() > 0
        || command.createdBeforeMs() > 0) {
      throw new IllegalArgumentException("replay_filters_require_preview");
    }
    if (blankToEmpty(command.controlPlaneRequestId()).isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
  }

  private static String replayRequestFingerprint(ReplayDeadLettersCommand command) {
    List<String> normalizedWorkItemIds =
        command.workItemIds().stream()
            .map(ScriptWorkItemServiceImpl::normalizeText)
            .sorted()
            .toList();
    String canonical =
        String.join(
            "\u0000",
            normalizeText(command.tenantId()),
            String.join(",", normalizedWorkItemIds),
            normalizeText(command.controlPlaneRequestId()),
            normalizeText(command.actorPrincipal()),
            normalizeReplayReason(command.reason()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder fingerprint = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        fingerprint.append(String.format("%02x", value));
      }
      return fingerprint.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for replay request identity", e);
    }
  }

  private PatchInstanceRolloutSummary withPublication(
      String tenantId, PatchInstanceRolloutSummary summary) {
    return new PatchInstanceRolloutSummary(
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
        summary.rolloutStatus(),
        summary.statusReason(),
        summary.lastChangedAtMs(),
        summary.projectionAsOfMs(),
        summary.projectionLagMs(),
        summary.projectionStale(),
        publicationMetadata(tenantId, summary.scriptPatchVersion()).publication());
  }

  private PatchInstanceRolloutEventSummary withPublication(
      String tenantId, PatchInstanceRolloutEventSummary summary) {
    return new PatchInstanceRolloutEventSummary(
        summary.eventId(),
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
        summary.rolloutStatus(),
        summary.statusReason(),
        summary.observedAtMs(),
        summary.projectionAsOfMs(),
        publicationMetadata(tenantId, summary.scriptPatchVersion()).publication());
  }

  private DeadLetterSummary withPublication(String tenantId, DeadLetterSummary summary) {
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
        summary.scriptEventId(),
        summary.status(),
        summary.reason(),
        summary.createdAtMs(),
        summary.updatedAtMs(),
        publicationMetadata(tenantId, summary.scriptPatchVersion()).publication(),
        pluginPublication);
  }

  private HandoffEventSummary withPublication(String tenantId, HandoffEventSummary summary) {
    PluginRuntimeStateService.PluginPublicationLink pluginPublication =
        pluginPublicationLink(tenantId, summary.pluginId(), summary.pluginVersionId());
    return new HandoffEventSummary(
        summary.eventId(),
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
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
        publicationMetadata(tenantId, summary.scriptPatchVersion()).publication(),
        pluginPublication);
  }

  private PublicationMetadata publicationMetadata(String tenantId, String scriptPatchVersion) {
    GetPublishedScriptPatchVersionResponse scriptPatchResponse =
        gameDesignControlPlaneClient.getPublishedScriptPatchVersion(tenantId, scriptPatchVersion);
    if (scriptPatchResponse.hasError() && !scriptPatchResponse.getError().getCode().isBlank()) {
      return PublicationMetadata.lookupFailure(
          scriptPatchVersion,
          scriptPatchResponse.getError().getCode(),
          scriptPatchResponse.getError().getMessage());
    }
    long baseVersionId = scriptPatchResponse.getScriptPatch().getBaseVersionId();
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
      ReplayDeadLettersCommand command, String normalizedTenantId, int boundedLimit) {
    if (command.workItemIds() != null && !command.workItemIds().isEmpty()) {
      return command.workItemIds().stream()
          .limit(boundedLimit)
          .map(ScriptWorkItemServiceImpl::parseWorkItemId)
          .map(workItemRepository::findById)
          .flatMap(Optional::stream)
          .filter(item -> normalizedTenantId.equals(item.getTenantId()))
          .filter(item -> matchesReplayFilters(item, command))
          .toList();
    }
    return workItemRepository
        .findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
            normalizedTenantId, STATUS_DEAD_LETTERED, PageRequest.of(0, boundedLimit))
        .stream()
        .filter(item -> matchesReplayFilters(item, command))
        .toList();
  }

  private boolean matchesReplayFilters(ScriptWorkItem item, ReplayDeadLettersCommand command) {
    String normalizedGameInstanceId = normalizeText(command.gameInstanceId());
    String normalizedRegionId = normalizeText(command.regionId());
    return (normalizedGameInstanceId.isBlank()
            || item.getGameInstanceId().equals(normalizedGameInstanceId))
        && (normalizedRegionId.isBlank() || item.getRegionId().equals(normalizedRegionId))
        && (command.scriptPatchVersion() == null
            || command.scriptPatchVersion().isBlank()
            || item.getScriptPatchVersion().equals(command.scriptPatchVersion()))
        && (command.createdAfterMs() <= 0
            || item.getCreatedAt().toEpochMilli() >= command.createdAfterMs())
        && (command.createdBeforeMs() <= 0
            || item.getCreatedAt().toEpochMilli() <= command.createdBeforeMs());
  }

  private String replayEligibilityReason(
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
    Optional<GetGameInstanceRuntimeStateResponse> cachedRuntime =
        runtimeStateCache.computeIfAbsent(
            runtimeScopeKey,
            ignored -> {
              try {
                return Optional.ofNullable(
                    gameSessionControlPlaneClient.getGameInstanceRuntimeState(
                        item.getTenantId(), item.getGameInstanceId(), item.getRegionId()));
              } catch (RuntimeException ex) {
                return Optional.empty();
              }
            });
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
    var plugin =
        pluginRuntimeStateService.getStatus(item.getTenantId(), item.getGameInstanceId(), pluginId);
    return ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
        item,
        plugin.map(PluginRuntimeStateService.PluginRuntimeStatus::activePluginVersionId).orElse(""),
        plugin.map(PluginRuntimeStateService.PluginRuntimeStatus::pluginState).orElse(null),
        plugin.map(PluginRuntimeStateService.PluginRuntimeStatus::pluginActivationEpoch).orElse(0L),
        plugin.map(PluginRuntimeStateService.PluginRuntimeStatus::lifecycleRevision).orElse(0L));
  }

  private void persistReplayResult(
      ScriptDeadLetterReplayRepository.ReplayRequest request,
      long requestedWorkItemId,
      String outcome,
      String rejectionReason,
      ScriptWorkItem item,
      Instant now) {
    if (request == null) {
      return;
    }
    replayRepository.saveResult(
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
        now);
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

  private void markReplayQueued(Long workItemId, String reason, Instant now) {
    auditRepository
        .findByWorkItemId(workItemId)
        .ifPresent(
            audit -> {
              audit.setFinalStage("REPLAY");
              audit.setFinalOutcome("requeued");
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  private long deleteExcessDeadLetters() {
    long deadLetteredCount = workItemRepository.countByStatus(STATUS_DEAD_LETTERED);
    long excess = deadLetteredCount - outboxProperties.getDeadLetterMaxRows();
    if (excess <= 0) {
      return 0;
    }
    // Held oldest rows must not consume the cap candidate budget. Keyset pages remain bounded and
    // avoid skipping rows that shift into an earlier offset after an eligible deletion.
    long deleted = 0L;
    Instant afterUpdatedAt = null;
    Long afterId = null;
    while (deleted < excess) {
      List<ScriptWorkItem> page =
          workItemRepository.findByStatusOrderByUpdatedAtAscIdAscAfter(
              STATUS_DEAD_LETTERED, afterUpdatedAt, afterId, DEAD_LETTER_CLEANUP_PAGE_SIZE);
      if (page.isEmpty()) {
        break;
      }
      for (ScriptWorkItem item : page) {
        afterUpdatedAt = item.getUpdatedAt();
        afterId = item.getId();
        if (workItemRepository.deleteDeadLetteredIfNoRetainedEvidence(
            item.getTenantId(), item.getId())) {
          deleted++;
          if (deleted >= excess) {
            break;
          }
        }
      }
    }
    return deleted;
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

  private record RuntimeScopeKey(String tenantId, String gameInstanceId, String regionId) {}

  private static String normalizeReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_cancel" : reason;
  }

  private static String normalizeReplayReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_replay" : reason.strip();
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
