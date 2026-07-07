package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.GameSessionPinConvergenceDto;
import net.firedevops.firemud.loggingadmin.dto.PinnedScriptPatchVersionDto;
import net.firedevops.firemud.loggingadmin.service.GameSessionPinService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed gRPC client dependency is stored and not exposed")
public class GameSessionPinServiceImpl implements GameSessionPinService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public GameSessionPinServiceImpl(GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.gameSessionPin.getPinnedScriptPatchVersion")
  public PinnedScriptPatchVersionDto getPinnedScriptPatchVersion(
      long tenantId, long gameInstanceId) {
    SessionContext.requireTenantAccess(tenantId);
    GetPinnedScriptPatchVersionResponse response =
        gameSessionControlPlaneClient.getPinnedScriptPatchVersion(tenantId, gameInstanceId);
    requireNoError(response.getError());
    return new PinnedScriptPatchVersionDto(
        response.getPinnedScriptPatchVersion(),
        response.getPinnedAtMs() <= 0 ? null : Instant.ofEpochMilli(response.getPinnedAtMs()),
        response.getPinnedBy(),
        response.getControlPlaneRequestId(),
        toDto(response.getPublication()));
  }

  @Override
  @Timed(value = "loggingadmin.gameSessionPin.getGameSessionPinConvergence")
  public GameSessionPinConvergenceDto getGameSessionPinConvergence(
      long tenantId, long gameInstanceId) {
    SessionContext.requireTenantAccess(tenantId);
    GetGameSessionPinConvergenceResponse response =
        gameSessionControlPlaneClient.getGameSessionPinConvergence(tenantId, gameInstanceId);
    requireNoError(response.getError());
    if (parseLong(response.getTenantId(), "tenant_id") != tenantId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane pin convergence response did not match requested tenant_id");
    }
    if (parseLong(response.getGameInstanceId(), "game_instance_id") != gameInstanceId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane pin convergence response did not match requested gameInstanceId");
    }
    return new GameSessionPinConvergenceDto(
        parseLong(response.getTenantId(), "tenant_id"),
        parseLong(response.getGameInstanceId(), "game_instance_id"),
        response.getObservedPinnedScriptPatchVersion(),
        response.getLastObservedControlPlaneRequestId(),
        response.getObservedAtMs() <= 0 ? null : Instant.ofEpochMilli(response.getObservedAtMs()),
        response.getIsStale(),
        toDto(response.getPublication()));
  }

  private PinnedScriptPatchVersionDto.ScriptPatchPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink publication) {
    return new PinnedScriptPatchVersionDto.ScriptPatchPublicationLinkDto(
        publication.getScriptPatchVersion(),
        publication.getVersionId() <= 0 ? null : publication.getVersionId(),
        publication.getBaseVersionId() <= 0 ? null : publication.getBaseVersionId(),
        publication.getPublicationState().name(),
        publication.getLastChangedAtMs() <= 0
            ? null
            : Instant.ofEpochMilli(publication.getLastChangedAtMs()),
        publication.getLookupErrorCode(),
        publication.getLookupErrorMessage());
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

  private long parseLong(String value, String field) {
    return ControlPlaneResponseReaders.parseLong(value, field);
  }
}
