package net.firedevops.firemud.loggingadmin.service.impl;

import java.util.UUID;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.TickRemediationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
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
  public TickRemediationActionDto pauseTicksForScope(TickRemediationRequest request) {
    validateScope(request);
    PauseTicksForScopeResponse response =
        gameSessionControlPlaneClient.pauseTicksForScope(toPauseRequest(request));
    requireSuccess(response.getSuccess(), response.getError());
    return auditAndReturn(request, ACTION_PAUSE);
  }

  @Override
  public TickRemediationActionDto resumeTicksForScope(TickRemediationRequest request) {
    validateScope(request);
    ResumeTicksForScopeResponse response =
        gameSessionControlPlaneClient.resumeTicksForScope(toResumeRequest(request));
    requireSuccess(response.getSuccess(), response.getError());
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

  private void requireSuccess(boolean success, ErrorDetail error) {
    if (success && (error == null || error.getCode().isBlank())) {
      return;
    }
    if (error == null || error.getCode().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Tick remediation failed");
    }
    HttpStatus status =
        switch (error.getCode()) {
          case "INVALID_ARGUMENT" -> HttpStatus.BAD_REQUEST;
          case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
          case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
          default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    throw new ResponseStatusException(status, error.getMessage());
  }

  private void validateScope(TickRemediationRequest request) {
    boolean hasGameInstance = hasText(request.gameInstanceId());
    boolean hasRegion = hasText(request.regionId());
    if (hasGameInstance == hasRegion) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Exactly one of gameInstanceId or regionId is required");
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
    String accountId = net.firedevops.firemud.common.security.SessionContext.getAccountId();
    return accountId == null || accountId.isBlank() ? "internal-service" : accountId;
  }

  private Long parseAccountId() {
    String accountId = net.firedevops.firemud.common.security.SessionContext.getAccountId();
    if (accountId == null || accountId.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(accountId);
    } catch (NumberFormatException ex) {
      return null;
    }
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
}
