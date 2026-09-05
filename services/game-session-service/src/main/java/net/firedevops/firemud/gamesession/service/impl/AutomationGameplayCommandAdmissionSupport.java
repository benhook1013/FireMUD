package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.ScriptPinTupleCoherence;
import net.firedevops.firemud.gamesession.service.TickService;

final class AutomationGameplayCommandAdmissionSupport {
  private AutomationGameplayCommandAdmissionSupport() {}

  static AdmissionResult admitIfAbsent(
      AdmissionRequest request,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      TickService tickService) {
    return admitIfAbsent(
        request,
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        null,
        tickService);
  }

  static AdmissionResult admitIfAbsent(
      AdmissionRequest request,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      TickService tickService) {
    DurableAdmission durableAdmission =
        admitIfAbsentDurably(
            request,
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            gameplayAdmissionPointerAuthorityService);
    return materializeAcceptedCommand(
        durableAdmission, request, gameplayCommandRepository, tickService);
  }

  static DurableAdmission admitIfAbsentDurably(
      AdmissionRequest request,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService) {
    validate(request);
    GameInstance instance =
        (isLocalAutomation(request)
                ? gameInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(
                    request.tenantId(), request.gameInstanceId())
                : gameInstanceRepository.findById(request.gameInstanceId()))
            .orElseThrow(() -> new IllegalArgumentException("game_instance_id not found"));
    if (!request.tenantId().equals(instance.getTenantId())) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }

    Optional<AdmissionResult> scriptPinRejected = rejectIfScriptPinTupleMismatch(request, instance);
    if (scriptPinRejected.isPresent()) {
      return new DurableAdmission(scriptPinRejected.orElseThrow(), null, false);
    }

    GameplayCommand requestedCommand = acceptedAutomationCommand(request);
    Optional<GameplayCommand> existing = findExistingCommand(request, gameplayCommandRepository);
    if (existing.isPresent()) {
      return new DurableAdmission(
          existingAdmissionResult(existing.orElseThrow(), requestedCommand), null, false);
    }

    return admitFreshDurably(
        request,
        requestedCommand,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        gameplayAdmissionPointerAuthorityService,
        null);
  }

  static AdmissionResult admitRemoteIfAbsent(
      AdmissionRequest request,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      TickService tickService) {
    if (gameplayAdmissionPointerAuthorityService == null) {
      return admitIfAbsent(
          request,
          gameInstanceRepository,
          gameplayCommandRepository,
          runtimeRegionStatusRepository,
          null,
          tickService);
    }
    // Remote admission receives routing fields from an upstream retry payload.  The
    // pointer authority below is the source of truth for those fields, so validate
    // the request's non-routing identity first and validate the authoritative request
    // after routing has been supplied.
    validate(request, false);
    GameInstance instance =
        gameInstanceRepository
            .findById(request.gameInstanceId())
            .orElseThrow(() -> new IllegalArgumentException("game_instance_id not found"));
    if (!request.tenantId().equals(instance.getTenantId())) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }

    GameplayCommand requestedCommand = acceptedAutomationCommand(request);
    Optional<GameplayCommand> existing = findExistingCommand(request, gameplayCommandRepository);
    if (existing.isPresent()) {
      return existingAdmissionResult(existing.orElseThrow(), requestedCommand);
    }

    final List<GameplayAdmissionPointerSnapshot> currentPointers;
    try {
      currentPointers =
          gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
              request.tenantId(), request.gameInstanceId());
    } catch (RuntimeException ex) {
      return temporaryPointerAuthorityUnavailable(true);
    }
    if (currentPointers == null
        || currentPointers.size() != 1
        || !GameplayAdmissionPointerSnapshots.hasCompleteRoutingBundle(
            currentPointers.getFirst())) {
      return temporaryPointerAuthorityUnavailable(false);
    }
    GameplayAdmissionPointerSnapshot targetPointer = currentPointers.getFirst();
    if (targetPointer.tenantId() != request.tenantId()
        || targetPointer.gameInstanceId() != request.gameInstanceId()) {
      return permanentPointerAuthorityMismatch();
    }
    // A retry may omit routing entirely, but a supplied routing bundle is an
    // assertion from the original caller and must not be silently replaced by
    // the current pointer.  This preserves stale world/realm/version rejection.
    try {
      GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
          request.worldSlug(),
          request.realmSlug(),
          request.pointerVersion(),
          request.playableStateScope(),
          "world_slug, realm_slug, pointer_version, and playable_state_scope must be provided together");
    } catch (IllegalArgumentException ex) {
      return permanentPointerAuthorityMismatch();
    }
    if ((request.worldSlug() != null && !request.worldSlug().isBlank())
        || (request.realmSlug() != null && !request.realmSlug().isBlank())
        || (request.pointerVersion() != null && request.pointerVersion() > 0L)
        || (request.playableStateScope() != null && !request.playableStateScope().isBlank())) {
      // requireCompleteOrAbsentRoutingBundle above guarantees this is a complete bundle.
      if (!GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
          currentPointers,
          request.tenantId(),
          request.gameInstanceId(),
          request.worldSlug(),
          request.realmSlug(),
          request.pointerVersion(),
          request.playableStateScope())) {
        return permanentPointerAuthorityMismatch();
      }
    }
    AdmissionRequest targetRequest =
        withRouting(
            request,
            targetPointer.stateScope(),
            targetPointer.worldSlug(),
            targetPointer.realmSlug(),
            targetPointer.pointerVersion());
    validate(targetRequest, true);
    return admitFresh(
        targetRequest,
        acceptedAutomationCommand(targetRequest),
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        gameplayAdmissionPointerAuthorityService,
        currentPointers,
        tickService);
  }

  private static AdmissionResult admitFresh(
      AdmissionRequest request,
      GameplayCommand requestedCommand,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      List<GameplayAdmissionPointerSnapshot> preReadCurrentPointers,
      TickService tickService) {
    DurableAdmission durableAdmission =
        admitFreshDurably(
            request,
            requestedCommand,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            gameplayAdmissionPointerAuthorityService,
            preReadCurrentPointers);
    return materializeAcceptedCommand(
        durableAdmission, request, gameplayCommandRepository, tickService);
  }

  private static DurableAdmission admitFreshDurably(
      AdmissionRequest request,
      GameplayCommand requestedCommand,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      List<GameplayAdmissionPointerSnapshot> preReadCurrentPointers) {
    Optional<AdmissionResult> pointerRejected =
        rejectIfCurrentPointerAuthorityMismatch(
            request, gameplayAdmissionPointerAuthorityService, preReadCurrentPointers);
    if (pointerRejected.isPresent()) {
      return new DurableAdmission(pointerRejected.orElseThrow(), null, false);
    }

    Optional<AdmissionResult> rejected =
        rejectIfOwnershipClosed(request, runtimeRegionStatusRepository);
    if (rejected.isPresent()) {
      return new DurableAdmission(rejected.orElseThrow(), null, false);
    }

    GameplayCommand command = requestedCommand;
    GameplayCommandRepository.IdempotentInsertResult insertResult;
    try {
      insertResult = gameplayCommandRepository.insertIfAbsentByIdempotencyIdentity(command);
    } catch (GameplayCommandRepository.AdmissionPointerUnavailableException ex) {
      return new DurableAdmission(temporaryPointerAuthorityUnavailable(false), null, false);
    }
    command = insertResult.command();
    if (!insertResult.inserted()) {
      return new DurableAdmission(existingAdmissionResult(command, requestedCommand), null, false);
    }
    return new DurableAdmission(null, command, true);
  }

  static AdmissionResult materializeAcceptedCommand(
      DurableAdmission durableAdmission,
      AdmissionRequest request,
      GameplayCommandRepository gameplayCommandRepository,
      TickService tickService) {
    if (!durableAdmission.inserted()) {
      return durableAdmission.result();
    }
    GameplayCommand command = durableAdmission.command();
    try {
      tickService.enqueueCommand(
          request.tenantId(),
          request.gameInstanceId(),
          command.getCommandId(),
          request.command(),
          request.requiresSoloTick());
      triggerImmediateAutomationTick(tickService, request.tenantId(), request.gameInstanceId());
      return new AdmissionResult(true, "ENQUEUED", command.getCommandId(), null, null);
    } catch (IllegalArgumentException ex) {
      markAutomationFailed(command, "INVALID_ARGUMENT", ex.getMessage(), gameplayCommandRepository);
      return new AdmissionResult(
          false, "REJECTED", command.getCommandId(), "INVALID_ARGUMENT", ex.getMessage());
    } catch (TickQueueControlService.QueueUnavailableException ex) {
      markAutomationFailed(
          command, "QUEUE_UNAVAILABLE", ex.getMessage(), gameplayCommandRepository);
      return new AdmissionResult(
          false, "REJECTED", command.getCommandId(), "UNAVAILABLE", ex.getMessage());
    } catch (RuntimeException ex) {
      String message = "Gameplay command queue unavailable";
      markAutomationFailed(command, "QUEUE_UNAVAILABLE", message, gameplayCommandRepository);
      return new AdmissionResult(false, "REJECTED", command.getCommandId(), "UNAVAILABLE", message);
    }
  }

  record DurableAdmission(AdmissionResult result, GameplayCommand command, boolean inserted) {}

  private static AdmissionResult existingAdmissionResult(
      GameplayCommand command, GameplayCommand requestedCommand) {
    if (!sameAdmissionPayload(command, requestedCommand)) {
      return new AdmissionResult(
          false,
          "REJECTED",
          command.getCommandId(),
          "IDEMPOTENCY_CONFLICT",
          "Idempotency identity was already used with different admission fields");
    }
    if (isReusableExecutionOutcome(command.getExecutionOutcome())) {
      return new AdmissionResult(true, "DUPLICATE_NOOP", command.getCommandId(), null, null);
    }
    if ("ACCEPTED".equals(command.getExecutionOutcome())) {
      return new AdmissionResult(
          false,
          "REJECTED",
          command.getCommandId(),
          "UNAVAILABLE",
          "Gameplay command admission is still in flight");
    }
    if (isTerminalFailure(command.getExecutionOutcome())) {
      String failureCode = command.getFailureCode();
      String errorCode =
          failureCode == null || failureCode.isBlank()
              ? "UNAVAILABLE"
              : "QUEUE_UNAVAILABLE".equals(failureCode) ? "UNAVAILABLE" : failureCode;
      String failureMessage =
          command.getFailureMessage() == null || command.getFailureMessage().isBlank()
              ? "Gameplay command admission previously failed"
              : command.getFailureMessage();
      return new AdmissionResult(
          false, "REJECTED", command.getCommandId(), errorCode, failureMessage);
    }
    return new AdmissionResult(
        false,
        "REJECTED",
        command.getCommandId(),
        "UNAVAILABLE",
        "Gameplay command admission state is not safely reusable");
  }

  /**
   * Compares the immutable fields carried by the current live automation admission request.
   *
   * <p>The command row also contains mutable execution/recovery state and downstream queue-source
   * fields; those deliberately do not participate in this comparison. The target-only namespace and
   * command ordinal are not part of the current request/schema and are therefore not fabricated
   * here. Local Automation rows also compare the exact script pin epoch and owner request evidence.
   */
  private static boolean sameAdmissionPayload(GameplayCommand existing, GameplayCommand requested) {
    return Objects.equals(existing.getTenantId(), requested.getTenantId())
        && Objects.equals(existing.getGameInstanceId(), requested.getGameInstanceId())
        && sameSourceType(existing.getSourceType(), requested.getSourceType())
        && sameText(existing.getAutomationDispatchId(), requested.getAutomationDispatchId())
        && sameText(existing.getAutomationWorkItemId(), requested.getAutomationWorkItemId())
        && sameText(existing.getScriptId(), requested.getScriptId())
        && sameText(existing.getScriptPatchVersion(), requested.getScriptPatchVersion())
        && sameAutomationScriptPinTuple(existing, requested)
        && sameText(existing.getPluginId(), requested.getPluginId())
        && sameText(existing.getPluginVersionId(), requested.getPluginVersionId())
        && sameRemoteOrExactPlayableStateScope(existing, requested)
        && sameRemoteOrExactRouting(existing, requested)
        && sameText(existing.getOriginSourceKind(), requested.getOriginSourceKind())
        && sameText(existing.getOriginSourceState(), requested.getOriginSourceState())
        && Objects.equals(existing.getOriginSourceOrdinal(), requested.getOriginSourceOrdinal())
        && Objects.equals(existing.getOriginSourceDueTickId(), requested.getOriginSourceDueTickId())
        && Objects.equals(existing.getOriginSourceDueAtMs(), requested.getOriginSourceDueAtMs())
        && sameText(existing.getTargetEntityId(), requested.getTargetEntityId())
        && Objects.equals(existing.getCharacterId(), requested.getCharacterId())
        && sameText(existing.getRemoteCoordinatorId(), requested.getRemoteCoordinatorId())
        && sameText(existing.getRemoteFollowupId(), requested.getRemoteFollowupId())
        && sameText(existing.getCommandName(), requested.getCommandName())
        && sameText(existing.getCommandText(), requested.getCommandText())
        && sameText(existing.getSanitizedCommandText(), requested.getSanitizedCommandText())
        && existing.isRequiresSoloTick() == requested.isRequiresSoloTick()
        && sameText(existing.getRegionId(), requested.getRegionId())
        && Objects.equals(existing.getRegionEpoch(), requested.getRegionEpoch())
        && Objects.equals(existing.getDueTickId(), requested.getDueTickId());
  }

  private static boolean sameRemoteOrExactRouting(
      GameplayCommand existing, GameplayCommand requested) {
    if (existing.getRemoteFollowupId() != null
        && !existing.getRemoteFollowupId().isBlank()
        && requested.getRemoteFollowupId() != null
        && !requested.getRemoteFollowupId().isBlank()) {
      return true;
    }
    return sameRoutingSlug(existing.getWorldSlug(), requested.getWorldSlug())
        && sameRoutingSlug(existing.getRealmSlug(), requested.getRealmSlug())
        && Objects.equals(existing.getPointerVersion(), requested.getPointerVersion());
  }

  private static boolean sameRemoteOrExactPlayableStateScope(
      GameplayCommand existing, GameplayCommand requested) {
    if (existing.getRemoteFollowupId() != null
        && !existing.getRemoteFollowupId().isBlank()
        && requested.getRemoteFollowupId() != null
        && !requested.getRemoteFollowupId().isBlank()) {
      return true;
    }
    return samePlayableStateScope(
        existing.getPlayableStateScope(), requested.getPlayableStateScope());
  }

  private static boolean sameText(String left, String right) {
    return Objects.equals(blankToNull(left), blankToNull(right));
  }

  private static boolean sameSourceType(String left, String right) {
    return Objects.equals(normalizeSourceType(left), normalizeSourceType(right));
  }

  private static String normalizeSourceType(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean samePlayableStateScope(String left, String right) {
    String normalizedLeft = normalizePlayableStateScope(left);
    String normalizedRight = normalizePlayableStateScope(right);
    return Objects.equals(normalizedLeft, normalizedRight);
  }

  private static String normalizePlayableStateScope(String value) {
    return value == null || value.isBlank()
        ? null
        : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static boolean sameRoutingSlug(String left, String right) {
    String normalizedLeft = blankToNull(left);
    String normalizedRight = blankToNull(right);
    return normalizedLeft == null
        ? normalizedRight == null
        : normalizedRight != null && normalizedLeft.equalsIgnoreCase(normalizedRight);
  }

  private static boolean isReusableExecutionOutcome(String executionOutcome) {
    return "STAGED".equals(executionOutcome)
        || "RETRY_QUEUED".equals(executionOutcome)
        || "DRAINED".equals(executionOutcome)
        || "APPLIED".equals(executionOutcome)
        || "COMPLETED".equals(executionOutcome);
  }

  private static boolean isTerminalFailure(String executionOutcome) {
    return "FAILED".equals(executionOutcome)
        || "ABANDONED".equals(executionOutcome)
        || "LOST_BEFORE_STAGING".equals(executionOutcome)
        || "REJECTED".equals(executionOutcome);
  }

  private static void validate(AdmissionRequest request) {
    validate(request, true);
  }

  private static void validate(AdmissionRequest request, boolean requireRoutingBundle) {
    ControlPlaneRequestParser.requirePositive(request.tenantId(), "tenant_id");
    ControlPlaneRequestParser.requirePositive(request.gameInstanceId(), "game_instance_id");
    requireText(request.regionId(), "region_id is required");
    ControlPlaneRequestParser.requirePositive(request.regionEpoch(), "region_epoch");
    requireText(request.sourceType(), "source_type is required");
    String normalizedSourceType = normalizeSourceType(request.sourceType());
    if ("AUTOMATION".equals(normalizedSourceType)) {
      requireText(request.automationDispatchId(), "automation_dispatch_id is required");
      requireText(request.automationWorkItemId(), "automation_work_item_id is required");
      requireText(request.scriptId(), "script_id is required");
      requireText(request.scriptPatchVersion(), "script_patch_version is required");
    } else if ("REMOTE_FOLLOWUP".equals(normalizedSourceType)) {
      requireText(request.remoteCoordinatorId(), "remote_coordinator_id is required");
      requireText(request.remoteFollowupId(), "remote_followup_id is required");
      TickQueueControlService.requireQueueEncodingSafe(
          request.remoteFollowupId(), "remote_followup_id");
    }
    requireText(request.targetEntityId(), "target_entity_id is required");
    requireText(request.command(), "command is required");
    if (requireRoutingBundle) {
      GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
          request.worldSlug(),
          request.realmSlug(),
          request.pointerVersion(),
          request.playableStateScope(),
          "world_slug, realm_slug, pointer_version, and playable_state_scope must be provided together");
    }
  }

  private static Optional<GameplayCommand> findExistingCommand(
      AdmissionRequest request, GameplayCommandRepository gameplayCommandRepository) {
    if (request.remoteFollowupId() != null && !request.remoteFollowupId().isBlank()) {
      return gameplayCommandRepository
          .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
              request.tenantId(),
              request.gameInstanceId(),
              request.regionId(),
              request.regionEpoch(),
              request.remoteFollowupId());
    }
    if (request.automationDispatchId() != null && !request.automationDispatchId().isBlank()) {
      return gameplayCommandRepository
          .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
              request.tenantId(),
              request.gameInstanceId(),
              request.regionId(),
              request.regionEpoch(),
              request.automationDispatchId());
    }
    return Optional.empty();
  }

  private static Optional<AdmissionResult> rejectIfOwnershipClosed(
      AdmissionRequest request, RuntimeRegionStatusRepository runtimeRegionStatusRepository) {
    Optional<RuntimeRegionStatus> maybeStatus =
        findRuntimeOwnership(request, runtimeRegionStatusRepository);
    if (maybeStatus.isEmpty()) {
      return Optional.of(
          new AdmissionResult(
              false,
              "OWNERSHIP_UNAVAILABLE",
              null,
              "runtime_ownership_not_found",
              "Runtime ownership not found"));
    }
    RuntimeRegionStatus status = maybeStatus.orElseThrow();
    if (!request.regionId().equals(status.getRegionId())) {
      return Optional.of(
          new AdmissionResult(
              false,
              "STALE_TIMELINE",
              null,
              "stale_region_id",
              "region_id does not match current runtime ownership"));
    }
    if (!request.regionEpoch().equals(status.getRegionEpoch())) {
      return Optional.of(
          new AdmissionResult(
              false,
              "STALE_TIMELINE",
              null,
              "stale_region_epoch",
              "region_epoch does not match current runtime ownership"));
    }
    if (status.isPaused()) {
      return Optional.of(
          new AdmissionResult(
              false, "RUNTIME_PAUSED", null, "runtime_paused", "Runtime ownership is paused"));
    }
    return Optional.empty();
  }

  private static Optional<AdmissionResult> rejectIfCurrentPointerAuthorityMismatch(
      AdmissionRequest request,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      List<GameplayAdmissionPointerSnapshot> preReadCurrentPointers) {
    GameplayAdmissionPointerSnapshots.RoutingBundle requestedRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            request.worldSlug(), request.realmSlug(), request.pointerVersion());
    if (requestedRoutingBundle == null || gameplayAdmissionPointerAuthorityService == null) {
      return Optional.empty();
    }

    final List<GameplayAdmissionPointerSnapshot> currentPointers;
    if (preReadCurrentPointers != null) {
      currentPointers = preReadCurrentPointers;
    } else {
      try {
        currentPointers =
            gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
                request.tenantId(), request.gameInstanceId());
      } catch (RuntimeException ex) {
        return Optional.of(temporaryPointerAuthorityUnavailable(true));
      }
    }
    if (currentPointers == null
        || currentPointers.size() != 1
        || !GameplayAdmissionPointerSnapshots.hasCompleteRoutingBundle(
            currentPointers.getFirst())) {
      return Optional.of(temporaryPointerAuthorityUnavailable(false));
    }
    if (!GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
        currentPointers,
        request.tenantId(),
        request.gameInstanceId(),
        requestedRoutingBundle.worldSlug(),
        requestedRoutingBundle.realmSlug(),
        requestedRoutingBundle.pointerVersion(),
        request.playableStateScope())) {
      return Optional.of(permanentPointerAuthorityMismatch());
    }
    return Optional.empty();
  }

  static AdmissionRequest withRouting(
      AdmissionRequest request,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {
    return new AdmissionRequest(
        request.tenantId(),
        request.gameInstanceId(),
        request.regionId(),
        request.regionEpoch(),
        request.sourceType(),
        request.automationDispatchId(),
        request.automationWorkItemId(),
        request.scriptId(),
        request.scriptPatchVersion(),
        request.pluginId(),
        request.pluginVersionId(),
        playableStateScope,
        worldSlug,
        realmSlug,
        pointerVersion,
        request.originSourceKind(),
        request.originSourceState(),
        request.originSourceOrdinal(),
        request.originSourceDueTickId(),
        request.originSourceDueAtMs(),
        request.targetEntityId(),
        request.remoteCoordinatorId(),
        request.remoteFollowupId(),
        request.command(),
        request.requiresSoloTick(),
        request.dueTickId(),
        request.scriptPinEpoch(),
        request.scriptPinControlPlaneRequestId());
  }

  private static AdmissionResult temporaryPointerAuthorityUnavailable(
      boolean authorityUnavailable) {
    return new AdmissionResult(
        false,
        "RETRY_QUEUED",
        null,
        authorityUnavailable ? "AUTH_UNAVAILABLE" : "ADMISSION_POINTER_UNAVAILABLE",
        authorityUnavailable
            ? "Current gameplay admission pointer authority is unavailable"
            : "Current target gameplay admission pointer is temporarily incomplete");
  }

  private static AdmissionResult permanentPointerAuthorityMismatch() {
    return new AdmissionResult(
        false,
        "REJECTED",
        null,
        "ADMISSION_POINTER_UNAVAILABLE",
        "Current target gameplay admission pointer does not match the requested routing");
  }

  private static Optional<RuntimeRegionStatus> findRuntimeOwnership(
      AdmissionRequest request, RuntimeRegionStatusRepository runtimeRegionStatusRepository) {
    return runtimeRegionStatusRepository
        .findByTenantIdAndRegionId(request.tenantId(), request.regionId())
        .filter(status -> request.gameInstanceId().equals(status.getGameInstanceId()));
  }

  private static Optional<AdmissionResult> rejectIfScriptPinTupleMismatch(
      AdmissionRequest request, GameInstance instance) {
    // This boundary is intentionally local Automation-only. Remote follow-up source/target tuple
    // binding has a separate owner contract and must not be inferred from the local request.
    if (!isLocalAutomation(request)) {
      return Optional.empty();
    }

    try {
      ScriptPinTupleCoherence.requireCoherent(
          request.scriptPatchVersion(),
          request.scriptPinEpoch(),
          request.scriptPinControlPlaneRequestId());
      ScriptPinTupleCoherence.requireCoherent(
          instance.getScriptPatchVersion(),
          instance.getScriptPinEpoch(),
          instance.getScriptPatchPinnedControlPlaneRequestId());
    } catch (IllegalArgumentException ex) {
      return Optional.of(
          new AdmissionResult(
              false,
              "REJECTED",
              null,
              "STALE_TIMELINE",
              "script pin tuple is not coherent with the current game instance"));
    }

    if (!Objects.equals(request.scriptPatchVersion(), instance.getScriptPatchVersion())
        || !Objects.equals(request.scriptPinEpoch(), instance.getScriptPinEpoch())) {
      return Optional.of(
          new AdmissionResult(
              false,
              "REJECTED",
              null,
              "STALE_TIMELINE",
              "script pin tuple does not match current game instance"));
    }
    if (!sameText(
        request.scriptPinControlPlaneRequestId(),
        instance.getScriptPatchPinnedControlPlaneRequestId())) {
      return Optional.of(
          new AdmissionResult(
              false,
              "REJECTED",
              null,
              "STALE_TIMELINE",
              "script pin request identity does not match current game instance"));
    }
    return Optional.empty();
  }

  private static boolean sameAutomationScriptPinTuple(
      GameplayCommand existing, GameplayCommand requested) {
    if (!isLocalAutomation(existing) || !isLocalAutomation(requested)) {
      return true;
    }
    return Objects.equals(existing.getScriptPinEpoch(), requested.getScriptPinEpoch())
        && sameText(
            existing.getScriptPinControlPlaneRequestId(),
            requested.getScriptPinControlPlaneRequestId());
  }

  private static boolean isLocalAutomation(AdmissionRequest request) {
    return "AUTOMATION".equals(normalizeSourceType(request.sourceType()))
        && blankToNull(request.remoteFollowupId()) == null;
  }

  private static boolean isLocalAutomation(GameplayCommand command) {
    return "AUTOMATION".equals(normalizeSourceType(command.getSourceType()))
        && blankToNull(command.getRemoteFollowupId()) == null;
  }

  private static GameplayCommand acceptedAutomationCommand(AdmissionRequest request) {
    Instant now = Instant.now();
    GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            request.worldSlug(), request.realmSlug(), request.pointerVersion());
    GameplayCommand command = new GameplayCommand();
    command.setCommandId(commandId(request));
    command.setTenantId(request.tenantId());
    command.setGameInstanceId(request.gameInstanceId());
    command.setSessionId(0L);
    command.setCommandName(commandName(request.command()));
    command.setCommandText(request.command());
    command.setSanitizedCommandText(GameSessionCommandLogSanitizer.sanitize(request.command()));
    command.setRequiresSoloTick(request.requiresSoloTick());
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(now);
    command.setLastAttemptAt(now);
    command.setAttemptCount(1);
    command.setSourceType(normalizeSourceType(request.sourceType()));
    command.setAutomationDispatchId(blankToNull(request.automationDispatchId()));
    command.setAutomationWorkItemId(blankToNull(request.automationWorkItemId()));
    command.setScriptId(blankToNull(request.scriptId()));
    command.setScriptPatchVersion(blankToNull(request.scriptPatchVersion()));
    if (isLocalAutomation(request)) {
      command.setScriptPinEpoch(request.scriptPinEpoch());
      command.setScriptPinControlPlaneRequestId(
          blankToNull(request.scriptPinControlPlaneRequestId()));
    }
    command.setPluginId(blankToNull(request.pluginId()));
    command.setPluginVersionId(blankToNull(request.pluginVersionId()));
    command.setPlayableStateScope(blankToNull(request.playableStateScope()));
    command.setWorldSlug(routingBundle == null ? null : routingBundle.worldSlug());
    command.setRealmSlug(routingBundle == null ? null : routingBundle.realmSlug());
    command.setPointerVersion(routingBundle == null ? null : routingBundle.pointerVersion());
    command.setOriginSourceKind(blankToNull(request.originSourceKind()));
    command.setOriginSourceState(blankToNull(request.originSourceState()));
    command.setOriginSourceOrdinal(request.originSourceOrdinal());
    command.setOriginSourceDueTickId(request.originSourceDueTickId());
    command.setOriginSourceDueAtMs(request.originSourceDueAtMs());
    command.setTargetEntityId(request.targetEntityId());
    command.setRemoteCoordinatorId(blankToNull(request.remoteCoordinatorId()));
    command.setRemoteFollowupId(blankToNull(request.remoteFollowupId()));
    command.setCharacterId(
        GameplayCharacterIdParser.parseGameplayCharacterId(request.targetEntityId()));
    command.setRegionId(request.regionId());
    command.setRegionEpoch(request.regionEpoch());
    command.setDueTickId(request.dueTickId());
    return command;
  }

  private static String commandId(AdmissionRequest request) {
    if ("REMOTE_FOLLOWUP".equals(normalizeSourceType(request.sourceType()))
        && request.remoteFollowupId() != null
        && !request.remoteFollowupId().isBlank()) {
      return "rfcmd-" + request.remoteFollowupId();
    }
    return "auto-" + UUID.randomUUID();
  }

  private static void markAutomationFailed(
      GameplayCommand command,
      String code,
      String message,
      GameplayCommandRepository gameplayCommandRepository) {
    gameplayCommandRepository.markAcceptedCommandFailed(
        command.getCommandId(), code, message, Instant.now());
  }

  private static void triggerImmediateAutomationTick(
      TickService tickService, long tenantId, long gameInstanceId) {
    try {
      tickService.processTick(tenantId, gameInstanceId);
    } catch (RuntimeException ex) {
      // Best effort kick only; durable command row is already staged.
    }
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String commandName(String command) {
    String trimmed = command == null ? "" : command.trim();
    if (trimmed.isEmpty()) {
      return "UNKNOWN";
    }
    int firstSpace = trimmed.indexOf(' ');
    String token = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    return token.toUpperCase(java.util.Locale.ROOT);
  }

  record AdmissionRequest(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      Long regionEpoch,
      String sourceType,
      String automationDispatchId,
      String automationWorkItemId,
      String scriptId,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      String originSourceKind,
      String originSourceState,
      Long originSourceOrdinal,
      Long originSourceDueTickId,
      Long originSourceDueAtMs,
      String targetEntityId,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String command,
      boolean requiresSoloTick,
      Long dueTickId,
      Long scriptPinEpoch,
      String scriptPinControlPlaneRequestId) {
    AdmissionRequest(
        Long tenantId,
        Long gameInstanceId,
        String regionId,
        Long regionEpoch,
        String sourceType,
        String automationDispatchId,
        String automationWorkItemId,
        String scriptId,
        String scriptPatchVersion,
        String pluginId,
        String pluginVersionId,
        String playableStateScope,
        String worldSlug,
        String realmSlug,
        Long pointerVersion,
        String originSourceKind,
        String originSourceState,
        Long originSourceOrdinal,
        Long originSourceDueTickId,
        Long originSourceDueAtMs,
        String targetEntityId,
        String remoteCoordinatorId,
        String remoteFollowupId,
        String command,
        boolean requiresSoloTick,
        Long dueTickId) {
      this(
          tenantId,
          gameInstanceId,
          regionId,
          regionEpoch,
          sourceType,
          automationDispatchId,
          automationWorkItemId,
          scriptId,
          scriptPatchVersion,
          pluginId,
          pluginVersionId,
          playableStateScope,
          worldSlug,
          realmSlug,
          pointerVersion,
          originSourceKind,
          originSourceState,
          originSourceOrdinal,
          originSourceDueTickId,
          originSourceDueAtMs,
          targetEntityId,
          remoteCoordinatorId,
          remoteFollowupId,
          command,
          requiresSoloTick,
          dueTickId,
          null,
          null);
    }

    AdmissionRequest(
        Long tenantId,
        Long gameInstanceId,
        String regionId,
        Long regionEpoch,
        String sourceType,
        String automationDispatchId,
        String automationWorkItemId,
        String scriptId,
        String scriptPatchVersion,
        String pluginId,
        String pluginVersionId,
        String playableStateScope,
        String worldSlug,
        String realmSlug,
        Long pointerVersion,
        String originSourceKind,
        String originSourceState,
        Long originSourceOrdinal,
        Long originSourceDueTickId,
        Long originSourceDueAtMs,
        String targetEntityId,
        String remoteCoordinatorId,
        String remoteFollowupId,
        String command,
        boolean requiresSoloTick,
        Long dueTickId,
        Long scriptPinEpoch) {
      this(
          tenantId,
          gameInstanceId,
          regionId,
          regionEpoch,
          sourceType,
          automationDispatchId,
          automationWorkItemId,
          scriptId,
          scriptPatchVersion,
          pluginId,
          pluginVersionId,
          playableStateScope,
          worldSlug,
          realmSlug,
          pointerVersion,
          originSourceKind,
          originSourceState,
          originSourceOrdinal,
          originSourceDueTickId,
          originSourceDueAtMs,
          targetEntityId,
          remoteCoordinatorId,
          remoteFollowupId,
          command,
          requiresSoloTick,
          dueTickId,
          scriptPinEpoch,
          null);
    }
  }

  record AdmissionResult(
      boolean accepted,
      String admissionOutcome,
      String commandId,
      String errorCode,
      String errorMessage) {}
}
