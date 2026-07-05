package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.TickService;

final class AutomationGameplayCommandAdmissionSupport {
  private AutomationGameplayCommandAdmissionSupport() {}

  static AdmissionResult admitIfAbsent(
      AdmissionRequest request,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      TickService tickService) {
    validate(request);
    GameInstance instance =
        gameInstanceRepository
            .findById(request.gameInstanceId())
            .orElseThrow(() -> new IllegalArgumentException("game_instance_id not found"));
    if (!request.tenantId().equals(instance.getTenantId())) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }

    Optional<GameplayCommand> existing = findExistingCommand(request, gameplayCommandRepository);
    if (existing.isPresent()) {
      return new AdmissionResult(
          true, "DUPLICATE_NOOP", existing.orElseThrow().getCommandId(), null, null);
    }

    Optional<AdmissionResult> rejected =
        rejectIfOwnershipClosed(request, runtimeRegionStatusRepository);
    if (rejected.isPresent()) {
      return rejected.orElseThrow();
    }

    GameplayCommand command = acceptedAutomationCommand(request);
    gameplayCommandRepository.save(command);
    try {
      tickService.enqueueCommand(
          request.tenantId(),
          request.gameInstanceId(),
          command.getCommandId(),
          request.command(),
          request.requiresSoloTick());
      markAutomationStaged(command);
      gameplayCommandRepository.save(command);
      triggerImmediateAutomationTick(tickService, request.tenantId(), request.gameInstanceId());
      return new AdmissionResult(true, "ENQUEUED", command.getCommandId(), null, null);
    } catch (IllegalArgumentException ex) {
      markAutomationFailed(command, "INVALID_ARGUMENT", ex.getMessage());
      gameplayCommandRepository.save(command);
      return new AdmissionResult(
          false, "REJECTED", command.getCommandId(), "INVALID_ARGUMENT", ex.getMessage());
    }
  }

  private static void validate(AdmissionRequest request) {
    if (request.tenantId() == null || request.tenantId() <= 0) {
      throw new IllegalArgumentException("tenant_id must be positive");
    }
    if (request.gameInstanceId() == null || request.gameInstanceId() <= 0) {
      throw new IllegalArgumentException("game_instance_id must be positive");
    }
    requireText(request.regionId(), "region_id is required");
    if (request.regionEpoch() == null || request.regionEpoch() <= 0) {
      throw new IllegalArgumentException("region_epoch must be positive");
    }
    requireText(request.sourceType(), "source_type is required");
    if ("AUTOMATION".equals(request.sourceType())) {
      requireText(request.automationDispatchId(), "automation_dispatch_id is required");
      requireText(request.automationWorkItemId(), "automation_work_item_id is required");
      requireText(request.scriptId(), "script_id is required");
      requireText(request.scriptPatchVersion(), "script_patch_version is required");
    } else if ("REMOTE_FOLLOWUP".equals(request.sourceType())) {
      requireText(request.remoteCoordinatorId(), "remote_coordinator_id is required");
      requireText(request.remoteFollowupId(), "remote_followup_id is required");
    }
    requireText(request.targetEntityId(), "target_entity_id is required");
    requireText(request.command(), "command is required");
    GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
        request.worldSlug(),
        request.realmSlug(),
        request.pointerVersion(),
        "world_slug, realm_slug, and pointer_version must be provided together");
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

  private static Optional<RuntimeRegionStatus> findRuntimeOwnership(
      AdmissionRequest request, RuntimeRegionStatusRepository runtimeRegionStatusRepository) {
    return runtimeRegionStatusRepository
        .findByTenantIdAndRegionId(request.tenantId(), request.regionId())
        .filter(status -> request.gameInstanceId().equals(status.getGameInstanceId()));
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
    command.setSourceType(request.sourceType());
    command.setAutomationDispatchId(blankToNull(request.automationDispatchId()));
    command.setAutomationWorkItemId(blankToNull(request.automationWorkItemId()));
    command.setScriptId(blankToNull(request.scriptId()));
    command.setScriptPatchVersion(blankToNull(request.scriptPatchVersion()));
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
    command.setCharacterId(parseGameplayCharacterId(request.targetEntityId()));
    command.setRegionId(request.regionId());
    command.setRegionEpoch(request.regionEpoch());
    command.setDueTickId(request.dueTickId());
    return command;
  }

  private static String commandId(AdmissionRequest request) {
    if ("REMOTE_FOLLOWUP".equals(request.sourceType())
        && request.remoteFollowupId() != null
        && !request.remoteFollowupId().isBlank()) {
      return "rfcmd-" + request.remoteFollowupId();
    }
    return "auto-" + UUID.randomUUID();
  }

  private static void markAutomationStaged(GameplayCommand command) {
    Instant now = Instant.now();
    command.setExecutionOutcome("STAGED");
    command.setStagedAt(now);
    command.setLastAttemptAt(now);
  }

  private static void markAutomationFailed(GameplayCommand command, String code, String message) {
    Instant now = Instant.now();
    command.setExecutionOutcome("FAILED");
    command.setGameplayResult("NOT_APPLIED");
    command.setCompletedAt(now);
    command.setLastAttemptAt(now);
    command.setFailureCode(code);
    command.setFailureMessage(message);
  }

  private static void triggerImmediateAutomationTick(
      TickService tickService, long tenantId, long gameInstanceId) {
    try {
      tickService.processTick(tenantId, gameInstanceId);
    } catch (RuntimeException ex) {
      // Best effort kick only; durable command row is already staged.
    }
  }

  private static Long parseGameplayCharacterId(String targetEntityId) {
    if (targetEntityId == null || targetEntityId.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(targetEntityId);
    } catch (NumberFormatException ex) {
      return null;
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
      Long dueTickId) {}

  record AdmissionResult(
      boolean accepted,
      String admissionOutcome,
      String commandId,
      String errorCode,
      String errorMessage) {}
}
