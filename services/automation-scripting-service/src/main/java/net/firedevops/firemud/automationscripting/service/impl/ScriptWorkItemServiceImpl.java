package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutboxProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
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
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
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
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final List<String> CANCELABLE_STATUSES = List.of(STATUS_PENDING_EVALUATION);
  private static final List<String> ACTIVE_DRAIN_STATUSES =
      List.of(STATUS_EVALUATING, STATUS_HANDOFF_IN_FLIGHT);
  private static final List<String> DRAIN_RELEVANT_STATUSES =
      List.of(STATUS_PENDING_EVALUATION, STATUS_EVALUATING, STATUS_HANDOFF_IN_FLIGHT);

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
      ScriptPatchReadinessProjectionService readinessProjectionService) {
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
  }

  @Override
  @Transactional
  public long cancelPendingForPatch(CancelPendingForPatchCommand command) {
    requireText(command.tenantId(), "tenant_id");
    requireText(command.scriptPatchVersion(), "script_patch_version");
    List<ScriptWorkItem> candidates =
        workItemRepository
            .findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
                command.tenantId(), command.scriptPatchVersion(), CANCELABLE_STATUSES)
            .stream()
            .filter(
                item ->
                    command.gameInstanceId().isBlank()
                        || item.getGameInstanceId().equals(command.gameInstanceId()))
            .filter(
                item ->
                    command.regionId().isBlank() || item.getRegionId().equals(command.regionId()))
            .toList();
    return cancelCandidates(candidates, command.reason());
  }

  @Override
  @Transactional
  public long cancelPendingForPluginVersion(CancelPendingForPluginVersionCommand command) {
    requireText(command.tenantId(), "tenant_id");
    requireText(command.pluginId(), "plugin_id");
    requireText(command.pluginVersionId(), "plugin_version_id");
    List<ScriptWorkItem> candidates =
        workItemRepository
            .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
                command.tenantId(),
                command.pluginId(),
                command.pluginVersionId(),
                CANCELABLE_STATUSES)
            .stream()
            .filter(
                item ->
                    command.gameInstanceId().isBlank()
                        || item.getGameInstanceId().equals(command.gameInstanceId()))
            .filter(
                item ->
                    command.regionId().isBlank() || item.getRegionId().equals(command.regionId()))
            .toList();
    return cancelCandidates(candidates, command.reason());
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
        workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
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
        workItemRepository.findByIdInAndStatusOrderByCreatedAtAscIdAsc(
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
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    Instant now = Instant.now();
    AutomationAdmissionStateService.AdmissionStateSummary admissionState =
        automationAdmissionStateService.getState(tenantId, gameInstanceId, regionId);
    List<ScriptWorkItem> scopedWorkItems =
        workItemRepository.findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
            tenantId, gameInstanceId, blankToEmpty(regionId), DRAIN_RELEVANT_STATUSES);
    List<ScriptWorkItem> countedWorkItems =
        "PAUSED_FOR_ROLLBACK".equals(admissionState.mode())
            ? scopedWorkItems.stream()
                .filter(
                    item ->
                        item.getAdmissionEpoch() <= 0
                            || item.getAdmissionEpoch() < admissionState.admissionEpoch())
                .toList()
            : scopedWorkItems;
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
        tenantId,
        gameInstanceId,
        blankToEmpty(regionId),
        admissionState.mode(),
        admissionState.admissionEpoch(),
        activeExecutionCount,
        oldestActiveExecutionStartedAtMs,
        pendingCancelableWorkItemCount,
        now.toEpochMilli());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PatchInstanceRolloutSummary> getPatchInstanceRolloutStatus(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    requireText(scriptPatchVersion, "script_patch_version");
    return rolloutProjectionService
        .getProjection(tenantId, gameInstanceId, scriptPatchVersion)
        .map(summary -> withPublication(tenantId, summary));
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
    requireText(tenantId, "tenant_id");
    return rolloutProjectionService
        .listProjections(
            tenantId,
            gameInstanceId,
            scriptPatchVersion,
            rolloutStatus,
            changedAfterMs,
            changedBeforeMs)
        .stream()
        .map(summary -> withPublication(tenantId, summary))
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
    return rolloutProjectionService
        .listEvents(
            tenantId,
            gameInstanceId,
            scriptPatchVersion,
            rolloutStatus,
            changedAfterMs,
            changedBeforeMs,
            limit)
        .stream()
        .map(summary -> withPublication(tenantId, summary))
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
    requireText(tenantId, "tenant_id");
    int boundedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(worldSlug, realmSlug, pointerVersion);
    return handoffEventRepository
        .findEvents(
            tenantId,
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
        .map(summary -> withPublication(tenantId, summary))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeadLetterSummary> listDeadLetters(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit) {
    requireText(tenantId, "tenant_id");
    int boundedLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 500);
    return workItemRepository
        .findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
            tenantId, STATUS_DEAD_LETTERED, PageRequest.of(0, boundedLimit))
        .stream()
        .filter(
            item ->
                gameInstanceId == null
                    || gameInstanceId.isBlank()
                    || item.getGameInstanceId().equals(gameInstanceId))
        .filter(
            item ->
                scriptPatchVersion == null
                    || scriptPatchVersion.isBlank()
                    || item.getScriptPatchVersion().equals(scriptPatchVersion))
        .map(ScriptWorkItemServiceImpl::toDeadLetterSummary)
        .map(summary -> withPublication(tenantId, summary))
        .toList();
  }

  @Override
  @Transactional
  public ReplayResult replayDeadLetters(ReplayDeadLettersCommand command) {
    requireText(command.tenantId(), "tenant_id");
    int boundedLimit = Math.min(Math.max(command.limit() <= 0 ? 50 : command.limit(), 1), 100);
    Instant now = Instant.now();
    String reason = normalizeReplayReason(command.reason());
    List<ScriptWorkItem> candidates = selectReplayCandidates(command, boundedLimit);
    long replayed = 0L;
    long rejected = 0L;
    for (ScriptWorkItem item : candidates) {
      if (!eligibleForReplay(item)) {
        rejected++;
        continue;
      }
      item.setStatus(STATUS_PENDING_EVALUATION);
      item.setCancelReason("");
      item.setUpdatedAt(now);
      workItemRepository.save(item);
      refreshReadinessProjectionIfNeeded(item);
      rolloutProjectionService.refreshForWorkItem(item);
      markReplayQueued(item.getId(), reason, now);
      replayed++;
    }
    return new ReplayResult(replayed, rejected);
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
      ReplayDeadLettersCommand command, int boundedLimit) {
    if (command.workItemIds() != null && !command.workItemIds().isEmpty()) {
      return command.workItemIds().stream()
          .limit(boundedLimit)
          .map(ScriptWorkItemServiceImpl::parseWorkItemId)
          .map(workItemRepository::findById)
          .flatMap(Optional::stream)
          .filter(item -> command.tenantId().equals(item.getTenantId()))
          .filter(item -> STATUS_DEAD_LETTERED.equals(item.getStatus()))
          .filter(item -> matchesReplayFilters(item, command))
          .toList();
    }
    return workItemRepository
        .findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
            command.tenantId(), STATUS_DEAD_LETTERED, PageRequest.of(0, boundedLimit))
        .stream()
        .filter(item -> matchesReplayFilters(item, command))
        .toList();
  }

  private boolean matchesReplayFilters(ScriptWorkItem item, ReplayDeadLettersCommand command) {
    return (command.gameInstanceId() == null
            || command.gameInstanceId().isBlank()
            || item.getGameInstanceId().equals(command.gameInstanceId()))
        && (command.regionId() == null
            || command.regionId().isBlank()
            || item.getRegionId().equals(command.regionId()))
        && (command.scriptPatchVersion() == null
            || command.scriptPatchVersion().isBlank()
            || item.getScriptPatchVersion().equals(command.scriptPatchVersion()))
        && (command.createdAfterMs() <= 0
            || item.getCreatedAt().toEpochMilli() >= command.createdAfterMs())
        && (command.createdBeforeMs() <= 0
            || item.getCreatedAt().toEpochMilli() <= command.createdBeforeMs());
  }

  private boolean eligibleForReplay(ScriptWorkItem item) {
    if ("onLoad".equals(item.getEventType())) {
      return eligibleForOnLoadReplay(item);
    }
    return eligibleForRuntimeReplay(item);
  }

  private boolean eligibleForOnLoadReplay(ScriptWorkItem item) {
    return readinessProjectionService
        .getProjection(item.getTenantId(), item.getScriptPatchVersion())
        .filter(summary -> summary.status() == ScriptPatchStatus.SCRIPT_PATCH_STATUS_FAILED)
        .filter(summary -> summary.supersededByScriptPatchVersion().isBlank())
        .isPresent();
  }

  private boolean eligibleForRuntimeReplay(ScriptWorkItem item) {
    Optional<ScriptPatchPinProjectionService.PinConvergenceSummary> runtime =
        scriptPatchPinProjectionService
            .getPinConvergence(item.getTenantId(), item.getGameInstanceId())
            .summary();
    if (runtime.isEmpty() || runtime.get().projectionStale()) {
      return false;
    }
    if (!item.getScriptPatchVersion().equals(runtime.get().observedPinnedScriptPatchVersion())) {
      return false;
    }
    Optional<ScriptEventIngressAudit> audit =
        ingressAuditRepository
            .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                item.getTenantId(),
                item.getGameInstanceId(),
                item.getRegionId(),
                item.getRegionEpoch(),
                item.getEntityId(),
                blankToEmpty(item.getPlayableStateScope()),
                blankToEmpty(item.getWorldSlug()),
                blankToEmpty(item.getRealmSlug()),
                blankToEmpty(item.getPointerVersion()),
                item.getEventType(),
                item.getEventSchemaVersion(),
                item.getScriptPatchVersion(),
                item.getScriptEventId(),
                item.isDryRun());
    if (audit.isEmpty()
        || audit.get().getPluginId() == null
        || audit.get().getPluginId().isBlank()) {
      return true;
    }
    return pluginRuntimeStateService
        .getStatus(item.getTenantId(), item.getGameInstanceId(), audit.get().getPluginId())
        .map(status -> status.activePluginVersionId().equals(audit.get().getPluginVersionId()))
        .orElse(false);
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
    int batchSize = Math.toIntExact(Math.min(excess, Integer.MAX_VALUE));
    List<ScriptWorkItem> oldest =
        workItemRepository.findByStatusOrderByUpdatedAtAscIdAsc(
            STATUS_DEAD_LETTERED, PageRequest.of(0, batchSize));
    workItemRepository.deleteAll(oldest);
    return oldest.size();
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

  private static String normalizeReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_cancel" : reason;
  }

  private static String normalizeReplayReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_replay" : reason;
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private static Long parseWorkItemId(String workItemId) {
    try {
      return Long.parseLong(workItemId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("work_item_id must be numeric");
    }
  }

  private static Long parseOptionalWorkItemId(String workItemId) {
    String normalized = blankToEmpty(workItemId);
    return normalized.isBlank() ? null : parseWorkItemId(normalized);
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }
}
