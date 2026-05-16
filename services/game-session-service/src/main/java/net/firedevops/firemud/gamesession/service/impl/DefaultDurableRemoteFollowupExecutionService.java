package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.service.TickService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository and service collaborators are retained internally")
public final class DefaultDurableRemoteFollowupExecutionService
    implements DurableRemoteFollowupExecutionService {
  private static final String CLAIMED_STATUS = RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED;

  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
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
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
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

    PayloadExecution payloadExecution = executePayload(coordinator, followup);
    remoteFollowupRuntimeService.recordResult(
        new RemoteFollowupRuntimeService.ResultRequest(
            followup.getTenantId(),
            durableResultId(followup),
            coordinator.getCoordinatorId(),
            followup.getFollowupId(),
            coordinator.getOriginRegionId(),
            coordinator.getOriginRegionEpoch(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch(),
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
        if ((followup.getPayloadKind() == null || followup.getPayloadKind().isBlank())
            && (followup.getRequestedCommand() == null
                || followup.getRequestedCommand().isBlank())) {
          return failure(
              "REMOTE_FOLLOWUP_PAYLOAD_INVALID",
              "Target-side remote followup payload is not valid JSON");
        }
      }
    }
    String payloadKind = firstNonBlank(followup.getPayloadKind(), optionalText(root, "kind"));
    String requestedCommand =
        firstNonBlank(followup.getRequestedCommand(), optionalText(root, "command"));
    boolean requiresSoloTick =
        followup.isRequiresSoloTick() || root.path("requiresSoloTick").asBoolean(false);
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
      return executeEnqueueGameplayCommand(
          root, requestedCommand, requiresSoloTick, coordinator, followup);
    }
    if ("enqueue_automation_command".equals(payloadKind)) {
      return executeEnqueueAutomationCommand(
          root, requestedCommand, requiresSoloTick, coordinator, followup);
    }
    if ("trigger_script_event".equals(payloadKind)) {
      return executeTriggerScriptEvent(root, coordinator, followup);
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
      AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
          AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
              new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                  followup.getTenantId(),
                  followup.getTargetGameInstanceId(),
                  followup.getTargetRegionId(),
                  followup.getTargetRegionEpoch(),
                  "AUTOMATION",
                  requiredTextOrFallback(
                      root, "automationDispatchId", coordinator.getAutomationDispatchId()),
                  requiredTextOrFallback(
                      root, "automationWorkItemId", coordinator.getAutomationWorkItemId()),
                  requiredTextOrFallback(root, "scriptId", coordinator.getScriptId()),
                  requiredTextOrFallback(
                      root, "scriptPatchVersion", coordinator.getScriptPatchVersion()),
                  firstNonBlank(optionalText(root, "pluginId"), coordinator.getPluginId()),
                  firstNonBlank(
                      optionalText(root, "pluginVersionId"), coordinator.getPluginVersionId()),
                  firstNonBlank(
                      optionalText(root, "playableStateScope"), followup.getPlayableStateScope()),
                  firstNonBlank(optionalText(root, "worldSlug"), followup.getWorldSlug()),
                  firstNonBlank(optionalText(root, "realmSlug"), followup.getRealmSlug()),
                  firstNonNull(optionalLong(root, "pointerVersion"), followup.getPointerVersion()),
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
                  textOrDefault(root, "targetEntityId", followup.getTargetEntityId()),
                  coordinator.getCoordinatorId(),
                  followup.getFollowupId(),
                  requiredTextOrFallback(root, "command", requestedCommand),
                  requiresSoloTick,
                  followup.getDueTickId()),
              gameInstanceRepository,
              gameplayCommandRepository,
              runtimeRegionStatusRepository,
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
      AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
          AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
              new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                  followup.getTenantId(),
                  followup.getTargetGameInstanceId(),
                  followup.getTargetRegionId(),
                  followup.getTargetRegionEpoch(),
                  "REMOTE_FOLLOWUP",
                  null,
                  null,
                  firstNonBlank(optionalText(root, "scriptId"), coordinator.getScriptId()),
                  firstNonBlank(
                      optionalText(root, "scriptPatchVersion"),
                      coordinator.getScriptPatchVersion()),
                  firstNonBlank(optionalText(root, "pluginId"), coordinator.getPluginId()),
                  firstNonBlank(
                      optionalText(root, "pluginVersionId"), coordinator.getPluginVersionId()),
                  firstNonBlank(
                      optionalText(root, "playableStateScope"), followup.getPlayableStateScope()),
                  firstNonBlank(optionalText(root, "worldSlug"), followup.getWorldSlug()),
                  firstNonBlank(optionalText(root, "realmSlug"), followup.getRealmSlug()),
                  firstNonNull(optionalLong(root, "pointerVersion"), followup.getPointerVersion()),
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
                  requiredTextOrFallback(root, "targetEntityId", followup.getTargetEntityId()),
                  coordinator.getCoordinatorId(),
                  followup.getFollowupId(),
                  requiredTextOrFallback(root, "command", requestedCommand),
                  requiresSoloTick,
                  followup.getDueTickId()),
              gameInstanceRepository,
              gameplayCommandRepository,
              runtimeRegionStatusRepository,
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

  private PayloadExecution executeTriggerScriptEvent(
      JsonNode root, RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
    try {
      String scriptId = firstNonBlank(optionalText(root, "scriptId"), coordinator.getScriptId());
      String pluginId = firstNonBlank(optionalText(root, "pluginId"), coordinator.getPluginId());
      String pluginVersionId =
          firstNonBlank(optionalText(root, "pluginVersionId"), coordinator.getPluginVersionId());
      String worldSlug = firstNonBlank(optionalText(root, "worldSlug"), followup.getWorldSlug());
      String realmSlug = firstNonBlank(optionalText(root, "realmSlug"), followup.getRealmSlug());
      Long pointerVersion =
          firstNonNull(optionalLong(root, "pointerVersion"), followup.getPointerVersion());
      RoutingBundle routingBundle = resolveRoutingBundle(worldSlug, realmSlug, pointerVersion);
      TriggerScriptEventRequest.Builder request =
          TriggerScriptEventRequest.newBuilder()
              .setTenantId(Long.toString(followup.getTenantId()))
              .setGameInstanceId(Long.toString(followup.getTargetGameInstanceId()))
              .setRegionId(followup.getTargetRegionId())
              .setRegionEpoch(followup.getTargetRegionEpoch())
              .setEntityId(requiredTextOrFallback(root, "entityId", followup.getTargetEntityId()))
              .setEventType(requiredTextOrFallback(root, "eventType", followup.getEventType()))
              .setScriptPatchVersion(
                  requiredTextOrFallback(
                      root, "scriptPatchVersion", coordinator.getScriptPatchVersion()))
              .setScriptEventId(
                  requiredTextOrFallback(root, "scriptEventId", followup.getScriptEventId()))
              .setTriggerMode(triggerMode(root, followup))
              .setPayloadJson(eventPayloadJson(root, followup))
              .setEventSchemaVersion(
                  firstNonBlank(
                      optionalText(root, "eventSchemaVersion"),
                      firstNonBlank(followup.getEventSchemaVersion(), "v1")))
              .setReadSnapshotToken(
                  requiredTextOrFallback(
                      root, "readSnapshotToken", followup.getReadSnapshotToken()))
              .setPlayableStateScope(playableStateScope(followup.getPlayableStateScope()));
      if (scriptId != null) {
        request.setScriptId(scriptId);
      }
      if (pluginId != null) {
        request.setPluginId(pluginId);
      }
      if (pluginVersionId != null) {
        request.setPluginVersionId(pluginVersionId);
      }
      if (routingBundle != null) {
        request.setWorldSlug(routingBundle.worldSlug());
        request.setRealmSlug(routingBundle.realmSlug());
        request.setPointerVersion(Long.toString(routingBundle.pointerVersion()));
      }
      Long dueTickId = optionalLong(root, "dueTickId", followup.getDueTickId());
      if (dueTickId != null) {
        request.setDueTickId(dueTickId);
      }
      Long dueAtMs = optionalLong(root, "dueAtMs");
      if (dueAtMs != null) {
        request.setDueAtMs(dueAtMs);
      }
      TriggerScriptEventResponse response =
          automationScriptingClient.triggerScriptEvent(request.build());
      String resultPayload =
          "{\"admitted\":"
              + response.getAdmitted()
              + ",\"admissionOutcome\":\""
              + jsonEscape(response.getAdmissionOutcome().name())
              + "\""
              + jsonStringField("admissionReason", response.getAdmissionReason())
              + ",\"resolvedHandlerCount\":"
              + response.getResolvedHandlerCount()
              + "}";
      if (!response.hasError() && response.getAdmitted()) {
        return new PayloadExecution(
            "APPLIED", "APPLIED", null, null, resultPayload, null, null, null);
      }
      String errorCode =
          response.hasError() && !response.getError().getCode().isBlank()
              ? response.getError().getCode()
              : response.getAdmissionReason().isBlank()
                  ? "REMOTE_SCRIPT_EVENT_REJECTED"
                  : response.getAdmissionReason().toUpperCase();
      String errorMessage =
          response.hasError() && !response.getError().getMessage().isBlank()
              ? response.getError().getMessage()
              : response.getAdmissionReason().isBlank()
                  ? "Target-side remote script event was not admitted"
                  : response.getAdmissionReason();
      return new PayloadExecution(
          "ABANDONED",
          "ABANDONED",
          errorCode,
          errorMessage,
          resultPayload,
          null,
          errorCode,
          errorMessage);
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_SCRIPT_EVENT_PAYLOAD_INVALID", ex.getMessage());
    }
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

  private static String requiredTextOrFallback(
      JsonNode root, String fieldName, String fallbackValue) {
    String value = firstNonBlank(optionalText(root, fieldName), fallbackValue);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String optionalText(JsonNode root, String fieldName) {
    if (root == null) {
      return null;
    }
    String value = root.path(fieldName).asText("").trim();
    return value.isBlank() ? null : value;
  }

  private static String textOrDefault(JsonNode root, String fieldName, String defaultValue) {
    String value = optionalText(root, fieldName);
    return value == null ? defaultValue : value;
  }

  private static TriggerMode triggerMode(JsonNode root, RemoteFollowup followup) {
    String mode = firstNonBlank(optionalText(root, "triggerMode"), followup.getTriggerMode());
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
    JsonNode payloadNode = root.path("eventPayload");
    if (!payloadNode.isMissingNode() && !payloadNode.isNull()) {
      return payloadNode.toString();
    }
    if (followup.getEventPayloadJson() != null && !followup.getEventPayloadJson().isBlank()) {
      return followup.getEventPayloadJson();
    }
    throw new IllegalArgumentException("eventPayload is required");
  }

  private static PlayableStateScope playableStateScope(String value) {
    if (value == null || value.isBlank()) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    }
    return switch (value) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> throw new IllegalArgumentException("Unsupported playableStateScope=" + value);
    };
  }

  private static Long optionalLong(JsonNode root, String fieldName) {
    return optionalLong(root, fieldName, null);
  }

  private static Long optionalLong(JsonNode root, String fieldName, Long defaultValue) {
    if (root == null) {
      return defaultValue;
    }
    JsonNode node = root.path(fieldName);
    if (!node.isNumber()) {
      return defaultValue;
    }
    long value = node.asLong();
    return value > 0 ? Long.valueOf(value) : defaultValue;
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary == null || primary.isBlank() ? fallback : primary;
  }

  private static Long firstNonNull(Long primary, Long fallback) {
    return primary != null ? primary : fallback;
  }

  private static RoutingBundle resolveRoutingBundle(
      String worldSlug, String realmSlug, Long pointerVersion) {
    boolean hasWorld = worldSlug != null && !worldSlug.isBlank();
    boolean hasRealm = realmSlug != null && !realmSlug.isBlank();
    boolean hasPointer = pointerVersion != null && pointerVersion > 0;
    if (!hasWorld && !hasRealm && !hasPointer) {
      return null;
    }
    if (hasWorld && hasRealm && hasPointer) {
      return new RoutingBundle(worldSlug, realmSlug, pointerVersion);
    }
    throw new IllegalArgumentException(
        "worldSlug, realmSlug, and pointerVersion must be provided together");
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

  private record RoutingBundle(String worldSlug, String realmSlug, Long pointerVersion) {}
}
