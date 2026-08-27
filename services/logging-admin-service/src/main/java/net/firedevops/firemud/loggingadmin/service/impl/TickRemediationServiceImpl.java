package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import net.firedevops.firemud.loggingadmin.service.TickRemediationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed client dependency is stored internally")
public class TickRemediationServiceImpl implements TickRemediationService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public TickRemediationServiceImpl(GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
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

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private long parseResponseLong(String value, String field) {
    return ControlPlaneResponseReaders.parseLong(value, field);
  }
}
