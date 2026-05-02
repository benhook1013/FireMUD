package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
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
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DefaultDurableRemoteFollowupExecutionService(
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      @Lazy TickService tickService,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService) {
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.tickService = tickService;
    this.remoteFollowupRuntimeService = remoteFollowupRuntimeService;
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
            payloadExecution.resultPayloadJson()));
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
    if (payloadJson == null || payloadJson.isBlank()) {
      return failure(
          "REMOTE_FOLLOWUP_PAYLOAD_REQUIRED",
          "Target-side remote followup execution requires a typed payload");
    }
    try {
      JsonNode root = objectMapper.readTree(payloadJson);
      String payloadKind = root.path("kind").asText("").trim();
      if (payloadKind.isBlank()) {
        return failure(
            "REMOTE_FOLLOWUP_KIND_REQUIRED",
            "Target-side remote followup payload must declare a kind");
      }
      if ("enqueue_gameplay_command".equals(payloadKind)) {
        return executeEnqueueGameplayCommand(root, coordinator, followup);
      }
      if ("enqueue_automation_command".equals(payloadKind)) {
        return executeEnqueueAutomationCommand(root, coordinator, followup);
      }
      return failure(
          "REMOTE_FOLLOWUP_KIND_UNSUPPORTED",
          "Target-side remote followup payload kind '%s' is not yet supported"
              .formatted(payloadKind));
    } catch (IOException ex) {
      return failure(
          "REMOTE_FOLLOWUP_PAYLOAD_INVALID",
          "Target-side remote followup payload is not valid JSON");
    }
  }

  private PayloadExecution executeEnqueueAutomationCommand(
      JsonNode root, RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
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
                  textOrDefault(root, "originSourceKind", "REMOTE_FOLLOWUP"),
                  textOrDefault(root, "originSourceState", "TARGET_REGION_EXECUTED"),
                  optionalLong(root, "originSourceOrdinal", followup.getDueTickId()),
                  optionalLong(root, "originSourceDueTickId", followup.getDueTickId()),
                  optionalLong(root, "originSourceDueAtMs"),
                  textOrDefault(root, "targetEntityId", followup.getTargetEntityId()),
                  coordinator.getCoordinatorId(),
                  followup.getFollowupId(),
                  requiredText(root, "command"),
                  root.path("requiresSoloTick").asBoolean(false),
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
        return new PayloadExecution("APPLIED", "APPLIED", null, null, payload);
      }
      return new PayloadExecution(
          "ABANDONED",
          "ABANDONED",
          result.errorCode() == null ? "REMOTE_AUTOMATION_REJECTED" : result.errorCode(),
          result.errorMessage() == null
              ? "Target-side remote automation command was not admitted"
              : result.errorMessage(),
          payload);
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_AUTOMATION_PAYLOAD_INVALID", ex.getMessage());
    }
  }

  private PayloadExecution executeEnqueueGameplayCommand(
      JsonNode root, RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
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
                  textOrDefault(root, "originSourceKind", "REMOTE_FOLLOWUP"),
                  textOrDefault(root, "originSourceState", "TARGET_REGION_EXECUTED"),
                  optionalLong(root, "originSourceOrdinal", followup.getDueTickId()),
                  optionalLong(root, "originSourceDueTickId", followup.getDueTickId()),
                  optionalLong(root, "originSourceDueAtMs"),
                  requiredTextOrFallback(root, "targetEntityId", followup.getTargetEntityId()),
                  coordinator.getCoordinatorId(),
                  followup.getFollowupId(),
                  requiredText(root, "command"),
                  root.path("requiresSoloTick").asBoolean(false),
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
        return new PayloadExecution("APPLIED", "APPLIED", null, null, payload);
      }
      return new PayloadExecution(
          "ABANDONED",
          "ABANDONED",
          result.errorCode() == null ? "REMOTE_GAMEPLAY_REJECTED" : result.errorCode(),
          result.errorMessage() == null
              ? "Target-side remote gameplay command was not admitted"
              : result.errorMessage(),
          payload);
    } catch (IllegalArgumentException ex) {
      return failure("REMOTE_GAMEPLAY_PAYLOAD_INVALID", ex.getMessage());
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
            + "\"}");
  }

  private static String requiredText(JsonNode root, String fieldName) {
    String value = optionalText(root, fieldName);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
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
    String value = root.path(fieldName).asText("").trim();
    return value.isBlank() ? null : value;
  }

  private static String textOrDefault(JsonNode root, String fieldName, String defaultValue) {
    String value = optionalText(root, fieldName);
    return value == null ? defaultValue : value;
  }

  private static Long optionalLong(JsonNode root, String fieldName) {
    return optionalLong(root, fieldName, null);
  }

  private static Long optionalLong(JsonNode root, String fieldName, Long defaultValue) {
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
      String resultPayloadJson) {}
}
