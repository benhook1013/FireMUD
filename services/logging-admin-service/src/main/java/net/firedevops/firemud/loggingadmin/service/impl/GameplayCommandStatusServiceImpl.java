package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameplayCommandStatus;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.GameplayCommandStatusDto;
import net.firedevops.firemud.loggingadmin.service.GameplayCommandStatusService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed gRPC client dependency is stored and not exposed")
public class GameplayCommandStatusServiceImpl implements GameplayCommandStatusService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public GameplayCommandStatusServiceImpl(
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.gameplayCommandStatus.getGameplayCommandStatus")
  public GameplayCommandStatusDto getGameplayCommandStatus(long tenantId, String commandId) {
    SessionContext.requireTenantAccess(tenantId);
    GetGameplayCommandStatusResponse response =
        gameSessionControlPlaneClient.getGameplayCommandStatus(commandId);
    requireNoError(response.getError());
    GameplayCommandStatus command = response.getCommand();
    if (command.getCommandId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gameplay command not found");
    }
    long responseTenantId = parseLong(command.getTenantId(), "tenant_id");
    if (responseTenantId != tenantId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane gameplay command response did not match requested tenant_id");
    }
    return toDto(command);
  }

  private GameplayCommandStatusDto toDto(GameplayCommandStatus command) {
    return new GameplayCommandStatusDto(
        command.getCommandId(),
        parseLong(command.getTenantId(), "tenant_id"),
        parseLong(command.getGameInstanceId(), "game_instance_id"),
        parseLong(command.getSessionId(), "session_id"),
        parseOptionalLong(command.getAccountId(), "account_id"),
        parseOptionalLong(command.getCharacterId(), "character_id"),
        command.getCommandName(),
        command.getSanitizedCommandText(),
        command.getRequiresSoloTick(),
        command.getExecutionOutcome(),
        command.getGameplayResult(),
        toInstant(command.getAcceptedAtMs()),
        toInstant(command.getStagedAtMs()),
        toInstant(command.getCompletedAtMs()),
        toInstant(command.getLastAttemptAtMs()),
        command.getAttemptCount(),
        blankToNull(command.getFailureCode()),
        blankToNull(command.getFailureMessage()),
        blankToNull(command.getSourceType()),
        blankToNull(command.getAutomationDispatchId()),
        blankToNull(command.getAutomationWorkItemId()),
        blankToNull(command.getScriptId()),
        blankToNull(command.getScriptPatchVersion()),
        blankToNull(command.getPluginId()),
        blankToNull(command.getPluginVersionId()),
        blankToNull(command.getTargetEntityId()),
        blankToNull(command.getRegionId()),
        optionalLong(command.getRegionEpoch()),
        optionalLong(command.getDueTickId()),
        optionalLong(command.getEnqueueSeq()),
        command.getPlayableStateScope().name(),
        blankToNull(command.getWorldSlug()),
        blankToNull(command.getRealmSlug()),
        optionalLong(command.getPointerVersion()),
        blankToNull(command.getOriginSourceKind()),
        blankToNull(command.getOriginSourceState()),
        optionalLong(command.getOriginSourceOrdinal()),
        optionalLong(command.getOriginSourceDueTickId()),
        optionalLong(command.getOriginSourceDueAtMs()),
        blankToNull(command.getQueueSourceKind()),
        blankToNull(command.getQueueSourceState()),
        optionalLong(command.getQueueSourceOrdinal()),
        optionalLong(command.getQueueSourceDueTickId()),
        optionalLong(command.getQueueSourceDueAtMs()),
        blankToNull(command.getRemoteCoordinatorId()),
        blankToNull(command.getRemoteFollowupId()),
        blankToNull(command.getRemoteState()),
        blankToNull(command.getRemoteResultOutcome()),
        blankToNull(command.getRemoteResultPayloadJson()),
        toInstant(command.getRemoteResultObservedAtMs()),
        toDto(command.getPublication()),
        blankToNull(command.getRemoteResultCommandId()),
        blankToNull(command.getRemoteResultErrorCode()),
        blankToNull(command.getRemoteResultMessage()),
        toDto(command.getPluginPublication()),
        parseOptionalLong(
            command.getRemoteOriginGameInstanceId(), "remote_origin_game_instance_id"),
        blankToNull(command.getRemoteOriginRegionId()),
        optionalLong(command.getRemoteOriginRegionEpoch()),
        parseOptionalLong(
            command.getRemoteTargetGameInstanceId(), "remote_target_game_instance_id"),
        blankToNull(command.getRemoteTargetRegionId()),
        optionalLong(command.getRemoteTargetRegionEpoch()),
        optionalLong(command.getRemoteOriginDeadlineRegionEpoch()),
        optionalLong(command.getRemoteOriginDeadlineTickId()),
        blankToNull(command.getRemoteLateResultPolicy()),
        blankToNull(command.getRemoteTargetCommandExecutionOutcome()),
        blankToNull(command.getRemoteTargetCommandGameplayResult()),
        blankToNull(command.getRemoteFollowupStatus()),
        blankToNull(command.getRemoteFollowupPayloadKind()),
        blankToNull(command.getRemoteFollowupRequestedCommand()),
        command.getRemoteFollowupRequiresSoloTick(),
        blankToNull(command.getRemoteFollowupOriginSourceKind()),
        blankToNull(command.getRemoteFollowupOriginSourceState()),
        optionalLong(command.getRemoteFollowupOriginSourceOrdinal()),
        optionalLong(command.getRemoteFollowupOriginSourceDueTickId()),
        optionalLong(command.getRemoteFollowupOriginSourceDueAtMs()),
        blankToNull(command.getRemoteTargetEntityId()),
        blankToNull(command.getRemoteFollowupEffectKey()),
        blankToNull(command.getRemoteFollowupFailureCode()),
        blankToNull(command.getRemoteFollowupFailureMessage()),
        blankToNull(command.getRemoteFollowupEventType()),
        blankToNull(command.getRemoteFollowupEventSchemaVersion()),
        blankToNull(command.getRemoteFollowupScriptEventId()),
        blankToNull(command.getRemoteFollowupTriggerMode()),
        blankToNull(command.getRemoteFollowupClaimTargetAggregate()),
        blankToNull(command.getCurrentRuntimeRegionId()),
        optionalLong(command.getCurrentRuntimeRegionEpoch()),
        parseOptionalLong(
            command.getCurrentRuntimeGameInstanceId(), "current_runtime_game_instance_id"),
        command.getCurrentRuntimePlayableStateScope().name(),
        blankToNull(command.getCurrentRuntimeWorldSlug()),
        blankToNull(command.getCurrentRuntimeRealmSlug()),
        optionalLong(command.getCurrentRuntimePointerVersion()),
        command.getIsCurrentRuntimeRoutingBundleStale());
  }

  private GameplayCommandStatusDto.ScriptPatchPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink publication) {
    return new GameplayCommandStatusDto.ScriptPatchPublicationLinkDto(
        blankToNull(publication.getScriptPatchVersion()),
        optionalLong(publication.getVersionId()),
        optionalLong(publication.getBaseVersionId()),
        publication.getPublicationState().name(),
        toInstant(publication.getLastChangedAtMs()),
        blankToNull(publication.getLookupErrorCode()),
        blankToNull(publication.getLookupErrorMessage()));
  }

  private GameplayCommandStatusDto.PluginPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.PluginPublicationLink publication) {
    return new GameplayCommandStatusDto.PluginPublicationLinkDto(
        blankToNull(publication.getPluginVersionId()),
        optionalLong(publication.getPublicationId()),
        publication.getPublicationState().name(),
        blankToNull(publication.getStatusReason()),
        toInstant(publication.getLastChangedAtMs()),
        blankToNull(publication.getLookupErrorCode()),
        blankToNull(publication.getLookupErrorMessage()));
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
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          field + " was not numeric in control-plane response",
          ex);
    }
  }

  private Long parseOptionalLong(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          field + " was not numeric in control-plane response",
          ex);
    }
  }

  private Long optionalLong(long value) {
    return value <= 0 ? null : value;
  }

  private Instant toInstant(long epochMs) {
    return epochMs <= 0 ? null : Instant.ofEpochMilli(epochMs);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
