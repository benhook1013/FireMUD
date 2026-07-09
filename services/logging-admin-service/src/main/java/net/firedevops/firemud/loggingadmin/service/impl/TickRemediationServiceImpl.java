package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.TickRemediationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed client and audit service dependencies are stored internally")
public class TickRemediationServiceImpl implements TickRemediationService {
  private static final String ACTION_PAUSE = "pause";
  private static final String ACTION_RESUME = "resume";

  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final LogEventService logEventService;

  public TickRemediationServiceImpl(
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      LogEventService logEventService) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.logEventService = logEventService;
  }

  @Override
  public RuntimeOwnershipStatusDto getRuntimeOwnershipStatus(
      long tenantId, String gameInstanceId, String regionId) {
    Long requestedGameInstanceId = validateScope(gameInstanceId, regionId);
    GetRuntimeOwnershipStatusResponse response =
        gameSessionControlPlaneClient.getRuntimeOwnershipStatus(tenantId, gameInstanceId, regionId);
    requireNoError(response.getError());
    RuntimeOwnershipStatus ownership = response.getOwnership();
    if (parseResponseLong(ownership.getTenantId(), "tenant_id") != tenantId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane runtime ownership response did not match requested tenant_id");
    }
    if (requestedGameInstanceId != null
        && parseResponseLong(ownership.getGameInstanceId(), "game_instance_id")
            != requestedGameInstanceId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane runtime ownership response did not match requested gameInstanceId");
    }
    if (hasText(regionId) && !regionId.equals(ownership.getRegionId())) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane runtime ownership response did not match requested regionId");
    }
    return toDto(ownership);
  }

  @Override
  public TickRemediationActionDto pauseTicksForScope(TickRemediationRequest request) {
    validateScope(request.gameInstanceId(), request.regionId());
    PauseTicksForScopeResponse response =
        gameSessionControlPlaneClient.pauseTicksForScope(toPauseRequest(request));
    requireNoError(response.getError());
    requireSuccess(response.getSuccess());
    return auditAndReturn(request, ACTION_PAUSE);
  }

  @Override
  public TickRemediationActionDto resumeTicksForScope(TickRemediationRequest request) {
    validateScope(request.gameInstanceId(), request.regionId());
    ResumeTicksForScopeResponse response =
        gameSessionControlPlaneClient.resumeTicksForScope(toResumeRequest(request));
    requireNoError(response.getError());
    requireSuccess(response.getSuccess());
    return auditAndReturn(request, ACTION_RESUME);
  }

  private net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest toPauseRequest(
      TickRemediationRequest request) {
    var builder =
        net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest.newBuilder()
            .setTenantId(Long.toString(request.tenantId()))
            .setControlPlaneRequestId(UUID.randomUUID().toString())
            .setActorPrincipal(actorPrincipal())
            .setReason(normalizedReason(request.reason()));
    if (hasText(request.gameInstanceId())) {
      builder.setGameInstanceId(request.gameInstanceId());
    } else {
      builder.setRegionId(request.regionId());
    }
    return builder.build();
  }

  private net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest toResumeRequest(
      TickRemediationRequest request) {
    var builder =
        net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest.newBuilder()
            .setTenantId(Long.toString(request.tenantId()))
            .setControlPlaneRequestId(UUID.randomUUID().toString())
            .setActorPrincipal(actorPrincipal())
            .setReason(normalizedReason(request.reason()));
    if (hasText(request.gameInstanceId())) {
      builder.setGameInstanceId(request.gameInstanceId());
    } else {
      builder.setRegionId(request.regionId());
    }
    return builder.build();
  }

  private TickRemediationActionDto auditAndReturn(TickRemediationRequest request, String action) {
    TickRemediationActionDto dto =
        new TickRemediationActionDto(
            request.tenantId(),
            scopeType(request),
            scopeId(request),
            action,
            actorPrincipal(),
            normalizedReason(request.reason()));
    logEventService.createLogEvent(
        new CreateLogEventRequest(
            request.tenantId(),
            parseAccountId(),
            "tick_remediation_" + action,
            buildAuditMessage(dto)));
    return dto;
  }

  private RuntimeOwnershipStatusDto toDto(RuntimeOwnershipStatus ownership) {
    return new RuntimeOwnershipStatusDto(
        parseResponseLong(ownership.getTenantId(), "tenant_id"),
        parseResponseLong(ownership.getGameInstanceId(), "game_instance_id"),
        ownership.getRegionEpoch(),
        ownership.getExecutorFence(),
        ownership.getOwnerService(),
        ownership.getOwnerInstanceId(),
        ownership.getPaused(),
        ownership.getLastCommittedTickBatchId(),
        ownership.getUpdatedAtMs() <= 0 ? null : Instant.ofEpochMilli(ownership.getUpdatedAtMs()),
        ownership.getLastCommittedTickId(),
        ownership.getRegionId(),
        ownership.getPendingGameplayCommandCount(),
        ownership.getDueRemoteFollowupCount(),
        ownership.getOldestDueRemoteFollowupTickId(),
        ownership.getRemoteFollowupDrainLagMs());
  }

  private void requireSuccess(boolean success) {
    if (success) {
      return;
    }
    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tick remediation failed");
  }

  private void requireNoError(ErrorDetail error) {
    if (error == null || error.getCode().isBlank()) {
      return;
    }
    throw new ResponseStatusException(
        switch (error.getCode()) {
          case "INVALID_ARGUMENT" -> HttpStatus.BAD_REQUEST;
          case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
          case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
          default -> HttpStatus.INTERNAL_SERVER_ERROR;
        },
        error.getMessage());
  }

  private Long validateScope(String gameInstanceId, String regionId) {
    boolean hasGameInstance = hasText(gameInstanceId);
    boolean hasRegion = hasText(regionId);
    if (hasGameInstance == hasRegion) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Exactly one of gameInstanceId or regionId is required");
    }
    if (!hasGameInstance) {
      return null;
    }
    try {
      return RequestIdValidation.requirePositiveLong(gameInstanceId, "gameInstanceId");
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  private String buildAuditMessage(TickRemediationActionDto dto) {
    return "Tick remediation "
        + dto.action()
        + " requested for "
        + dto.scopeType()
        + "="
        + dto.scopeId()
        + " by "
        + dto.actorPrincipal()
        + " reason="
        + dto.reason();
  }

  private String actorPrincipal() {
    return SessionActorReaders.actorPrincipalOrInternalService();
  }

  private Long parseAccountId() {
    return SessionActorReaders.currentAccountIdOrNull();
  }

  private String scopeType(TickRemediationRequest request) {
    return hasText(request.gameInstanceId()) ? "game_instance" : "region";
  }

  private String scopeId(TickRemediationRequest request) {
    return hasText(request.gameInstanceId()) ? request.gameInstanceId() : request.regionId();
  }

  private static String normalizedReason(String reason) {
    return reason == null || reason.isBlank() ? "operator_request" : reason;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private long parseResponseLong(String value, String field) {
    return ControlPlaneResponseReaders.parseLong(value, field);
  }
}
