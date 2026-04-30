package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
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
  private final RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DefaultDurableRemoteFollowupExecutionService(
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService) {
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
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

    FailureDetail failureDetail = validatePayloadKind(followup.getPayloadJson());
    remoteFollowupRuntimeService.recordResult(
        new RemoteFollowupRuntimeService.ResultRequest(
            followup.getTenantId(),
            "remote-result-" + UUID.randomUUID(),
            coordinator.getCoordinatorId(),
            followup.getFollowupId(),
            coordinator.getOriginRegionId(),
            coordinator.getOriginRegionEpoch(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch(),
            "ABANDONED",
            "{\"failureCode\":\""
                + failureDetail.failureCode()
                + "\",\"message\":\""
                + jsonEscape(failureDetail.failureMessage())
                + "\"}"));
    return new DurableRemoteFollowupExecutionResult(
        "ABANDONED", failureDetail.failureCode(), failureDetail.failureMessage());
  }

  private FailureDetail validatePayloadKind(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return new FailureDetail(
          "REMOTE_FOLLOWUP_PAYLOAD_REQUIRED",
          "Target-side remote followup execution requires a typed payload");
    }
    try {
      JsonNode root = objectMapper.readTree(payloadJson);
      String payloadKind = root.path("kind").asText("").trim();
      if (payloadKind.isBlank()) {
        return new FailureDetail(
            "REMOTE_FOLLOWUP_KIND_REQUIRED",
            "Target-side remote followup payload must declare a kind");
      }
      return new FailureDetail(
          "REMOTE_FOLLOWUP_KIND_UNSUPPORTED",
          "Target-side remote followup payload kind '%s' is not yet supported"
              .formatted(payloadKind));
    } catch (IOException ex) {
      return new FailureDetail(
          "REMOTE_FOLLOWUP_PAYLOAD_INVALID",
          "Target-side remote followup payload is not valid JSON");
    }
  }

  private static String jsonEscape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private record FailureDetail(String failureCode, String failureMessage) {}
}
