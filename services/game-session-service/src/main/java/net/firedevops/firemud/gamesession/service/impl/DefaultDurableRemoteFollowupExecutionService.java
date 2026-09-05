package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.service.TickService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository and service collaborators are retained internally")
public final class DefaultDurableRemoteFollowupExecutionService
    implements DurableRemoteFollowupExecutionService {
  private static final String CLAIMED_STATUS = RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED;
  private static final String TRIGGER_SCRIPT_EVENT_PAYLOAD_KIND = "trigger_script_event";

  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final TickService tickService;
  private final RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private final AutomationScriptingClient automationScriptingClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DefaultDurableRemoteFollowupExecutionService(
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      @Lazy TickService tickService,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService,
      AutomationScriptingClient automationScriptingClient) {
    this(
        remoteFollowupRepository,
        remoteCommandCoordinatorRepository,
        gameInstanceRepository,
        gameplayCommandRepository,
        runtimeRegionStatusRepository,
        tickService,
        remoteFollowupRuntimeService,
        automationScriptingClient,
        null);
  }

  @Autowired
  public DefaultDurableRemoteFollowupExecutionService(
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      @Lazy TickService tickService,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService,
      AutomationScriptingClient automationScriptingClient,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService) {
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.tickService = tickService;
    this.remoteFollowupRuntimeService = remoteFollowupRuntimeService;
    this.automationScriptingClient = automationScriptingClient;
  }

  @Override
  public DurableRemoteFollowupExecutionResult execute(TickEffect effect) {
    if (effect.getEffectKey() == null || effect.getEffectKey().isBlank()) {
      return new DurableRemoteFollowupExecutionResult(
          "REJECTED",
          "REMOTE_FOLLOWUP_ID_REQUIRED",
          "Durable remote followup execution requires a followup id effect key");
    }
    RemoteFollowup followup =
        remoteFollowupRepository.findByFollowupId(effect.getEffectKey()).orElse(null);
    if (followup == null) {
      return new DurableRemoteFollowupExecutionResult(
          "REJECTED",
          "REMOTE_FOLLOWUP_NOT_FOUND",
          "Durable remote followup execution could not load the linked followup");
    }
    if (RemoteFollowupRuntimeServiceImpl.FOLLOWUP_APPLIED.equals(followup.getStatus())) {
      return new DurableRemoteFollowupExecutionResult("APPLIED", null, null);
    }
    if (RemoteFollowupRuntimeServiceImpl.FOLLOWUP_ABANDONED.equals(followup.getStatus())) {
      return new DurableRemoteFollowupExecutionResult(
          "ABANDONED", followup.getFailureCode(), followup.getFailureMessage());
    }
    if (!CLAIMED_STATUS.equals(followup.getStatus())
        || !effect.getTickBatchId().equals(followup.getClaimedTickBatchId())) {
      return new DurableRemoteFollowupExecutionResult(
          "REJECTED",
          "REMOTE_FOLLOWUP_NOT_CLAIMED",
          "Remote followup is not durably claimed by the executing tick batch");
    }
    RemoteCommandCoordinator coordinator =
        remoteCommandCoordinatorRepository
            .findByTenantIdAndFollowupId(followup.getTenantId(), followup.getFollowupId())
            .orElse(null);
    if (coordinator == null) {
      remoteFollowupRuntimeService.abandonFollowup(
          followup.getTenantId(),
          followup.getFollowupId(),
          "REMOTE_COORDINATOR_NOT_FOUND",
          "Durable remote followup execution could not load the linked coordinator");
      return new DurableRemoteFollowupExecutionResult(
          "REJECTED",
          "REMOTE_COORDINATOR_NOT_FOUND",
          "Durable remote followup execution could not load the linked coordinator");
    }

    ExecutionScope executionScope;
    try {
      executionScope = validateExecutionScope(coordinator, followup);
    } catch (IllegalArgumentException ex) {
      remoteFollowupRuntimeService.abandonFollowup(
          followup.getTenantId(),
          followup.getFollowupId(),
          "REMOTE_FOLLOWUP_SCOPE_INVALID",
          ex.getMessage());
      return new DurableRemoteFollowupExecutionResult(
          "ABANDONED", "REMOTE_FOLLOWUP_SCOPE_INVALID", ex.getMessage());
    }

    PayloadExecution payloadExecution = executePayload(coordinator, followup);
    if ("RETRY_QUEUED".equals(payloadExecution.effectStatus())) {
      retryFollowup(
          followup,
          effect.getTickBatchId(),
          payloadExecution.failureCode(),
          payloadExecution.failureMessage());
      return new DurableRemoteFollowupExecutionResult(
          payloadExecution.effectStatus(),
          payloadExecution.failureCode(),
          payloadExecution.failureMessage());
    }
    remoteFollowupRuntimeService.recordResult(
        new RemoteFollowupRuntimeService.ResultRequest(
            followup.getTenantId(),
            durableResultId(followup),
            coordinator.getCoordinatorId(),
            followup.getFollowupId(),
            executionScope.originGameInstanceId(),
            executionScope.originRegionId(),
            executionScope.originRegionEpoch(),
            executionScope.targetGameInstanceId(),
            executionScope.targetRegionId(),
            executionScope.targetRegionEpoch(),
            payloadExecution.outcome(),
            payloadExecution.resultPayloadJson(),
            payloadExecution.resultCommandId(),
            payloadExecution.resultErrorCode(),
            payloadExecution.resultMessage()));
    return new DurableRemoteFollowupExecutionResult(
        payloadExecution.effectStatus(),
        payloadExecution.failureCode(),
        payloadExecution.failureMessage());
  }

  private static ExecutionScope validateExecutionScope(
      RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
    Long coordinatorOriginRegionEpoch = coordinator.getOriginRegionEpoch();
    Long coordinatorTargetRegionEpoch = coordinator.getTargetRegionEpoch();
    Long followupOriginRegionEpoch = followup.getOriginRegionEpoch();
    Long followupTargetRegionEpoch = followup.getTargetRegionEpoch();
    if (coordinator.getOriginGameInstanceId() == null
        || coordinator.getTargetGameInstanceId() == null
        || followup.getOriginGameInstanceId() == null
        || followup.getTargetGameInstanceId() == null
        || coordinator.getOriginGameInstanceId() <= 0L
        || coordinator.getTargetGameInstanceId() <= 0L
        || followup.getOriginGameInstanceId() <= 0L
        || followup.getTargetGameInstanceId() <= 0L
        || coordinator.getOriginRegionId() == null
        || coordinator.getOriginRegionId().isBlank()
        || coordinator.getTargetRegionId() == null
        || coordinator.getTargetRegionId().isBlank()
        || followup.getOriginRegionId() == null
        || followup.getOriginRegionId().isBlank()
        || followup.getTargetRegionId() == null
        || followup.getTargetRegionId().isBlank()
        || coordinatorOriginRegionEpoch == null
        || coordinatorTargetRegionEpoch == null
        || followupOriginRegionEpoch == null
        || followupTargetRegionEpoch == null
        || coordinatorOriginRegionEpoch <= 0L
        || coordinatorTargetRegionEpoch <= 0L
        || followupOriginRegionEpoch <= 0L
        || followupTargetRegionEpoch <= 0L) {
      throw new IllegalArgumentException(
          "Remote followup execution requires a complete origin and target scope");
    }
    if (!Objects.equals(coordinator.getOriginGameInstanceId(), followup.getOriginGameInstanceId())
        || !Objects.equals(coordinator.getOriginRegionId(), followup.getOriginRegionId())
        || !Objects.equals(coordinatorOriginRegionEpoch, followupOriginRegionEpoch)
        || !Objects.equals(
            coordinator.getTargetGameInstanceId(), followup.getTargetGameInstanceId())
        || !Objects.equals(coordinator.getTargetRegionId(), followup.getTargetRegionId())
        || !Objects.equals(coordinatorTargetRegionEpoch, followupTargetRegionEpoch)) {
      throw new IllegalArgumentException(
          "Remote followup execution scope does not match its coordinator");
    }
    return new ExecutionScope(
        coordinator.getOriginGameInstanceId(),
        coordinator.getOriginRegionId(),
        coordinatorOriginRegionEpoch,
        coordinator.getTargetGameInstanceId(),
        coordinator.getTargetRegionId(),
        coordinatorTargetRegionEpoch);
  }

  private static String durableResultId(RemoteFollowup followup) {
    return "remote-result:" + followup.getFollowupId();
  }

  private PayloadExecution executePayload(
      RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
    String payloadJson = followup.getPayloadJson();
    JsonNode root = MissingNode.getInstance();
    if (payloadJson != null && !payloadJson.isBlank()) {
      try {
        root = objectMapper.readTree(payloadJson);
      } catch (IOException ex) {
        if (TRIGGER_SCRIPT_EVENT_PAYLOAD_KIND.equals(followup.getPayloadKind())) {
          return failure(
              "REMOTE_FOLLOWUP_PAYLOAD_INVALID",
              "Target-side remote followup payload is not valid JSON");
        }
        if ((followup.getPayloadKind() == null || followup.getPayloadKind().isBlank())
            && (followup.getRequestedCommand() == null
                || followup.getRequestedCommand().isBlank())) {
          return failure(
              "REMOTE_FOLLOWUP_PAYLOAD_INVALID",
              "Target-side remote followup payload is not valid JSON");
        }
      }
    }
    String payloadKind;
    boolean requiresSoloTick;
    String requestedCommand;
    try {
      payloadKind = authoritativeText(followup.getPayloadKind(), root, "kind");
      requiresSoloTick =
          authoritativeBoolean(followup.isRequiresSoloTick(), root, "requiresSoloTick");
      requestedCommand =
          firstNonBlank(followup.getRequestedCommand(), optionalText(root, "command"));
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_FOLLOWUP_PAYLOAD_INVALID", ex.getMessage());
    }
    if ((payloadJson == null || payloadJson.isBlank())
        && (payloadKind == null || payloadKind.isBlank())
        && (requestedCommand == null || requestedCommand.isBlank())) {
      return failure(
          "REMOTE_FOLLOWUP_PAYLOAD_REQUIRED",
          "Target-side remote followup execution requires a typed payload");
    }
    if (payloadKind == null || payloadKind.isBlank()) {
      return failure(
          "REMOTE_FOLLOWUP_KIND_REQUIRED",
          "Target-side remote followup payload must declare a kind");
    }
    if ("enqueue_gameplay_command".equals(payloadKind)) {
      try {
        validateSourceRoutingPayload(followup, root);
      } catch (IllegalArgumentException ex) {
        return failure("REMOTE_GAMEPLAY_PAYLOAD_INVALID", ex.getMessage());
      }
      return executeEnqueueGameplayCommand(
          root, requestedCommand, requiresSoloTick, coordinator, followup);
    }
    if ("enqueue_automation_command".equals(payloadKind)) {
      try {
        validateSourceRoutingPayload(followup, root);
      } catch (IllegalArgumentException ex) {
        return failure("REMOTE_AUTOMATION_PAYLOAD_INVALID", ex.getMessage());
      }
      return executeEnqueueAutomationCommand(
          root, requestedCommand, requiresSoloTick, coordinator, followup);
    }
    if (TRIGGER_SCRIPT_EVENT_PAYLOAD_KIND.equals(payloadKind)) {
      try {
        return executeTriggerScriptEvent(root, coordinator, followup);
      } catch (IllegalArgumentException ex) {
        return failure("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", ex.getMessage());
      }
    }
    return failure(
        "REMOTE_FOLLOWUP_KIND_UNSUPPORTED",
        "Target-side remote followup payload kind '%s' is not yet supported"
            .formatted(payloadKind));
  }

  private PayloadExecution executeEnqueueAutomationCommand(
      JsonNode root,
      String requestedCommand,
      boolean requiresSoloTick,
      RemoteCommandCoordinator coordinator,
      RemoteFollowup followup) {
    try {
      GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
          sourceRoutingBundle(followup, root);
      AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
          AutomationGameplayCommandAdmissionSupport.admitRemoteIfAbsent(
              new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                  followup.getTenantId(),
                  followup.getTargetGameInstanceId(),
                  followup.getTargetRegionId(),
                  followup.getTargetRegionEpoch(),
                  "AUTOMATION",
                  requiredAuthoritativeText(
                      coordinator.getAutomationDispatchId(), root, "automationDispatchId"),
                  requiredAuthoritativeText(
                      coordinator.getAutomationWorkItemId(), root, "automationWorkItemId"),
                  requiredAuthoritativeText(coordinator.getScriptId(), root, "scriptId"),
                  requiredAuthoritativeText(
                      coordinator.getScriptPatchVersion(), root, "scriptPatchVersion"),
                  authoritativeText(coordinator.getPluginId(), root, "pluginId"),
                  authoritativeText(coordinator.getPluginVersionId(), root, "pluginVersionId"),
                  routingBundle == null ? null : routingBundle.playableStateScope(),
                  routingBundle == null ? null : routingBundle.worldSlug(),
                  routingBundle == null ? null : routingBundle.realmSlug(),
                  routingBundle == null ? null : routingBundle.pointerVersion(),
                  firstNonBlank(
                      followup.getOriginSourceKind(),
                      textOrDefault(root, "originSourceKind", "REMOTE_FOLLOWUP")),
                  firstNonBlank(
                      followup.getOriginSourceState(),
                      textOrDefault(root, "originSourceState", "TARGET_REGION_EXECUTED")),
                  firstNonNull(
                      followup.getOriginSourceOrdinal(),
                      optionalLong(root, "originSourceOrdinal", followup.getDueTickId())),
                  firstNonNull(
                      followup.getOriginSourceDueTickId(),
                      optionalLong(root, "originSourceDueTickId", followup.getDueTickId())),
                  firstNonNull(
                      followup.getOriginSourceDueAtMs(), optionalLong(root, "originSourceDueAtMs")),
                  authoritativeTextOrDefault(followup.getTargetEntityId(), root, "targetEntityId"),
                  coordinator.getCoordinatorId(),
                  followup.getFollowupId(),
                  requiredAuthoritativeText(requestedCommand, root, "command"),
                  requiresSoloTick,
                  followup.getDueTickId()),
              gameInstanceRepository,
              gameplayCommandRepository,
              runtimeRegionStatusRepository,
              gameplayAdmissionPointerAuthorityService,
              tickService);
      String payload =
          "{\"admissionOutcome\":\""
              + jsonEscape(result.admissionOutcome())
              + "\""
              + jsonStringField("commandId", result.commandId())
              + jsonStringField("errorCode", result.errorCode())
              + jsonStringField("message", result.errorMessage())
              + "}";
      if (result.accepted()) {
        return new PayloadExecution(
            "APPLIED", "APPLIED", null, null, payload, result.commandId(), null, null);
      }
      if (isRetryableTargetAdmissionFailure(result)) {
        return retryablePayloadExecution(
            result.errorCode(), result.errorMessage(), payload, result.commandId());
      }
      return new PayloadExecution(
          "ABANDONED",
          "ABANDONED",
          result.errorCode() == null ? "REMOTE_AUTOMATION_REJECTED" : result.errorCode(),
          result.errorMessage() == null
              ? "Target-side remote automation command was not admitted"
              : result.errorMessage(),
          payload,
          result.commandId(),
          result.errorCode(),
          result.errorMessage());
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_AUTOMATION_PAYLOAD_INVALID", ex.getMessage());
    }
  }

  private PayloadExecution executeEnqueueGameplayCommand(
      JsonNode root,
      String requestedCommand,
      boolean requiresSoloTick,
      RemoteCommandCoordinator coordinator,
      RemoteFollowup followup) {
    try {
      GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
          sourceRoutingBundle(followup, root);
      AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
          AutomationGameplayCommandAdmissionSupport.admitRemoteIfAbsent(
              new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                  followup.getTenantId(),
                  followup.getTargetGameInstanceId(),
                  followup.getTargetRegionId(),
                  followup.getTargetRegionEpoch(),
                  "REMOTE_FOLLOWUP",
                  null,
                  null,
                  authoritativeText(coordinator.getScriptId(), root, "scriptId"),
                  authoritativeText(
                      coordinator.getScriptPatchVersion(), root, "scriptPatchVersion"),
                  authoritativeText(coordinator.getPluginId(), root, "pluginId"),
                  authoritativeText(coordinator.getPluginVersionId(), root, "pluginVersionId"),
                  routingBundle == null ? null : routingBundle.playableStateScope(),
                  routingBundle == null ? null : routingBundle.worldSlug(),
                  routingBundle == null ? null : routingBundle.realmSlug(),
                  routingBundle == null ? null : routingBundle.pointerVersion(),
                  firstNonBlank(
                      followup.getOriginSourceKind(),
                      textOrDefault(root, "originSourceKind", "REMOTE_FOLLOWUP")),
                  firstNonBlank(
                      followup.getOriginSourceState(),
                      textOrDefault(root, "originSourceState", "TARGET_REGION_EXECUTED")),
                  firstNonNull(
                      followup.getOriginSourceOrdinal(),
                      optionalLong(root, "originSourceOrdinal", followup.getDueTickId())),
                  firstNonNull(
                      followup.getOriginSourceDueTickId(),
                      optionalLong(root, "originSourceDueTickId", followup.getDueTickId())),
                  firstNonNull(
                      followup.getOriginSourceDueAtMs(), optionalLong(root, "originSourceDueAtMs")),
                  requiredAuthoritativeText(followup.getTargetEntityId(), root, "targetEntityId"),
                  coordinator.getCoordinatorId(),
                  followup.getFollowupId(),
                  requiredAuthoritativeText(requestedCommand, root, "command"),
                  requiresSoloTick,
                  followup.getDueTickId()),
              gameInstanceRepository,
              gameplayCommandRepository,
              runtimeRegionStatusRepository,
              gameplayAdmissionPointerAuthorityService,
              tickService);
      String payload =
          "{\"admissionOutcome\":\""
              + jsonEscape(result.admissionOutcome())
              + "\""
              + jsonStringField("commandId", result.commandId())
              + jsonStringField("errorCode", result.errorCode())
              + jsonStringField("message", result.errorMessage())
              + "}";
      if (result.accepted()) {
        return new PayloadExecution(
            "APPLIED", "APPLIED", null, null, payload, result.commandId(), null, null);
      }
      if (isRetryableTargetAdmissionFailure(result)) {
        return retryablePayloadExecution(
            result.errorCode(), result.errorMessage(), payload, result.commandId());
      }
      return new PayloadExecution(
          "ABANDONED",
          "ABANDONED",
          result.errorCode() == null ? "REMOTE_GAMEPLAY_REJECTED" : result.errorCode(),
          result.errorMessage() == null
              ? "Target-side remote gameplay command was not admitted"
              : result.errorMessage(),
          payload,
          result.commandId(),
          result.errorCode(),
          result.errorMessage());
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_GAMEPLAY_PAYLOAD_INVALID", ex.getMessage());
    }
  }

  private static GameplayAdmissionPointerSnapshots.RoutingBundle sourceRoutingBundle(
      RemoteFollowup followup, JsonNode root) {
    String worldSlug = authoritativeText(followup.getWorldSlug(), root, "worldSlug");
    String realmSlug = authoritativeText(followup.getRealmSlug(), root, "realmSlug");
    Long pointerVersion = authoritativeLong(followup.getPointerVersion(), root, "pointerVersion");
    String playableStateScope =
        authoritativeText(followup.getPlayableStateScope(), root, "playableStateScope");
    GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
        worldSlug,
        realmSlug,
        pointerVersion,
        playableStateScope,
        "worldSlug, realmSlug, pointerVersion, and playableStateScope must be provided together");
    return GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
        worldSlug, realmSlug, pointerVersion, playableStateScope);
  }

  private static void validateSourceRoutingPayload(RemoteFollowup followup, JsonNode root) {
    sourceRoutingBundle(followup, root);
  }

  private static PayloadExecution retryablePayloadExecution(
      String errorCode, String errorMessage, String payload, String commandId) {
    return new PayloadExecution(
        "RETRY_QUEUED",
        "RETRY_QUEUED",
        errorCode == null ? "AUTH_UNAVAILABLE" : errorCode,
        errorMessage == null
            ? "Target admission authority is temporarily unavailable"
            : errorMessage,
        payload,
        commandId,
        errorCode,
        errorMessage);
  }

  private static boolean isRetryableTargetAdmissionFailure(
      AutomationGameplayCommandAdmissionSupport.AdmissionResult result) {
    String normalized =
        result.errorCode() == null ? "" : result.errorCode().trim().toUpperCase(Locale.ROOT);
    if ("ADMISSION_POINTER_UNAVAILABLE".equals(normalized)) {
      return "RETRY_QUEUED".equalsIgnoreCase(result.admissionOutcome());
    }
    return switch (normalized) {
      case "AUTH_UNAVAILABLE",
          "AUTHORITY_UNAVAILABLE",
          "OWNERSHIP_UNAVAILABLE",
          "RUNTIME_OWNERSHIP_NOT_FOUND",
          "RUNTIME_PAUSED",
          "QUEUE_UNAVAILABLE",
          "UNAVAILABLE" ->
          true;
      default -> false;
    };
  }

  private void retryFollowup(
      RemoteFollowup followup, String tickBatchId, String failureCode, String failureMessage) {
    if (!CLAIMED_STATUS.equals(followup.getStatus())
        || !tickBatchId.equals(followup.getClaimedTickBatchId())) {
      return;
    }
    // Keep the claimed followup bound to the executing durable effect. The effect remains
    // non-terminal and is retried from the same drained batch, preserving its lineage.
    followup.setFailureCode(failureCode);
    followup.setFailureMessage(failureMessage);
    followup.setUpdatedAt(java.time.Instant.now());
    remoteFollowupRepository.save(followup);
  }

  private PayloadExecution executeTriggerScriptEvent(
      JsonNode root, RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
    try {
      if (root != null && !root.isMissingNode() && !root.isObject()) {
        throw new IllegalArgumentException("payload must be a JSON object");
      }
      authoritativeText(coordinator.getScriptId(), root, "scriptId");
      authoritativeText(coordinator.getPluginId(), root, "pluginId");
      authoritativeText(coordinator.getPluginVersionId(), root, "pluginVersionId");
      TriggerScriptEventRequestFactory.requirePlayableStateScope(
          authoritativeText(followup.getPlayableStateScope(), root, "playableStateScope"));
      sourceRoutingBundle(followup, root);
      authoritativeBoolean(false, root, "isDryRun");
      requiredAuthoritativeText(followup.getTargetEntityId(), root, "entityId");
      requiredAuthoritativeText(followup.getEventType(), root, "eventType");
      String eventSchemaVersion =
          firstNonBlank(
              authoritativeText(followup.getEventSchemaVersion(), root, "eventSchemaVersion"),
              "v1");
      requiredAuthoritativeText(coordinator.getScriptPatchVersion(), root, "scriptPatchVersion");
      requiredAuthoritativeText(followup.getScriptEventId(), root, "scriptEventId");
      triggerMode(root, followup);
      requiredAuthoritativeText(followup.getReadSnapshotToken(), root, "readSnapshotToken");
      eventPayloadJson(root, followup);
      authoritativeLong(followup.getDueTickId(), root, "dueTickId");
      optionalLong(root, "dueAtMs");
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", ex.getMessage());
    }
    // The split intentionally removed durable remote source/target pin tuples. Do not derive a
    // target Trigger ingress tuple from the mutable target instance or generic patch metadata.
    return failure(
        "REMOTE_SCRIPT_EVENT_PIN_TUPLE_UNAVAILABLE",
        "Legacy remote trigger delivery is disabled until durable source and target script pin tuples are available");
  }

  private PayloadExecution failure(String failureCode, String failureMessage) {
    return new PayloadExecution(
        "ABANDONED",
        "ABANDONED",
        failureCode,
        failureMessage,
        "{\"failureCode\":\""
            + jsonEscape(failureCode)
            + "\",\"message\":\""
            + jsonEscape(failureMessage)
            + "\"}",
        null,
        failureCode,
        failureMessage);
  }

  private static String requiredAuthoritativeText(
      String authoritativeValue, JsonNode root, String fieldName) {
    String value = authoritativeText(authoritativeValue, root, fieldName);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String authoritativeText(
      String authoritativeValue, JsonNode root, String fieldName) {
    String payloadValue = optionalText(root, fieldName);
    if (authoritativeValue != null
        && !authoritativeValue.isBlank()
        && payloadValue != null
        && !authoritativeValue.equals(payloadValue)) {
      throw new IllegalArgumentException(fieldName + " conflicts with durable followup value");
    }
    return firstNonBlank(authoritativeValue, payloadValue);
  }

  private static String authoritativeTextOrDefault(
      String authoritativeValue, JsonNode root, String fieldName) {
    return firstNonBlank(authoritativeValue, optionalText(root, fieldName));
  }

  private static Long authoritativeLong(Long authoritativeValue, JsonNode root, String fieldName) {
    Long payloadValue = optionalLong(root, fieldName);
    if (authoritativeValue != null && authoritativeValue > 0L) {
      if (payloadValue != null && !authoritativeValue.equals(payloadValue)) {
        throw new IllegalArgumentException(fieldName + " conflicts with durable followup value");
      }
      return authoritativeValue;
    }
    return payloadValue;
  }

  private static boolean authoritativeBoolean(
      boolean authoritativeValue, JsonNode root, String fieldName) {
    Boolean payloadValue = optionalBoolean(root, fieldName);
    if (authoritativeValue) {
      if (payloadValue != null && !payloadValue) {
        throw new IllegalArgumentException(fieldName + " conflicts with durable followup value");
      }
      return true;
    }
    return payloadValue != null ? payloadValue : false;
  }

  private static String optionalText(JsonNode root, String fieldName) {
    if (root == null) {
      return null;
    }
    JsonNode node = root.path(fieldName);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException(fieldName + " must be text");
    }
    String value = node.textValue().trim();
    return value.isBlank() ? null : value;
  }

  private static String textOrDefault(JsonNode root, String fieldName, String defaultValue) {
    String value = optionalText(root, fieldName);
    return value == null ? defaultValue : value;
  }

  private static TriggerMode triggerMode(JsonNode root, RemoteFollowup followup) {
    String mode = firstNonBlank(followup.getTriggerMode(), optionalText(root, "triggerMode"));
    if (mode == null) {
      return TriggerMode.TRIGGER_MODE_NORMAL;
    }
    return switch (mode) {
      case "TRIGGER_MODE_NORMAL", "NORMAL" -> TriggerMode.TRIGGER_MODE_NORMAL;
      case "TRIGGER_MODE_CATCH_UP", "CATCH_UP" -> TriggerMode.TRIGGER_MODE_CATCH_UP;
      default -> throw new IllegalArgumentException("triggerMode is not supported");
    };
  }

  private String eventPayloadJson(JsonNode root, RemoteFollowup followup) {
    if (followup.getEventPayloadJson() != null && !followup.getEventPayloadJson().isBlank()) {
      return followup.getEventPayloadJson();
    }
    JsonNode payloadNode = root.path("eventPayload");
    if (!payloadNode.isMissingNode() && !payloadNode.isNull()) {
      return payloadNode.toString();
    }
    throw new IllegalArgumentException("eventPayload is required");
  }

  private static Long optionalLong(JsonNode root, String fieldName) {
    return optionalLong(root, fieldName, null);
  }

  private static Long optionalLong(JsonNode root, String fieldName, Long defaultValue) {
    if (root == null) {
      return defaultValue;
    }
    JsonNode node = root.path(fieldName);
    if (node.isMissingNode() || node.isNull()) {
      return defaultValue;
    }
    if (!node.isIntegralNumber()) {
      throw new IllegalArgumentException(fieldName + " must be a positive integer");
    }
    long value = node.longValue();
    if (value <= 0L) {
      throw new IllegalArgumentException(fieldName + " must be a positive integer");
    }
    return value;
  }

  @SuppressFBWarnings(
      value = "NP_BOOLEAN_RETURN_NULL",
      justification =
          "Tri-state payload parsing distinguishes absent booleans from explicit false.")
  private static Boolean optionalBoolean(JsonNode root, String fieldName) {
    if (root == null) {
      return null;
    }
    JsonNode node = root.path(fieldName);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException(fieldName + " must be boolean");
    }
    return node.booleanValue();
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary == null || primary.isBlank() ? fallback : primary;
  }

  private static Long firstNonNull(Long primary, Long fallback) {
    return primary != null ? primary : fallback;
  }

  private static String jsonStringField(String fieldName, String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return ",\"" + jsonEscape(fieldName) + "\":\"" + jsonEscape(value) + "\"";
  }

  private static String jsonEscape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private record PayloadExecution(
      String effectStatus,
      String outcome,
      String failureCode,
      String failureMessage,
      String resultPayloadJson,
      String resultCommandId,
      String resultErrorCode,
      String resultMessage) {}

  private record ExecutionScope(
      long originGameInstanceId,
      String originRegionId,
      long originRegionEpoch,
      long targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch) {}
}
