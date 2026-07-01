package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsResponse;
import net.firedevops.firemud.gamesession.v1.RemoteCommandCoordinatorEntry;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorListRequest;
import net.firedevops.firemud.loggingadmin.service.RemoteCommandCoordinatorService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed gRPC client dependency is stored and not exposed")
public class RemoteCommandCoordinatorServiceImpl implements RemoteCommandCoordinatorService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public RemoteCommandCoordinatorServiceImpl(
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.remoteCommandCoordinator.getRemoteCommandCoordinator")
  public RemoteCommandCoordinatorDto getRemoteCommandCoordinator(
      long tenantId, String coordinatorId) {
    SessionContext.requireTenantAccess(tenantId);
    GetRemoteCommandCoordinatorResponse response =
        gameSessionControlPlaneClient.getRemoteCommandCoordinator(tenantId, coordinatorId);
    requireNoError(response.getError());
    RemoteCommandCoordinatorEntry coordinator = response.getCoordinator();
    if (coordinator.getCoordinatorId().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Remote command coordinator not found");
    }
    long responseTenantId = parseLong(coordinator.getTenantId(), "tenant_id");
    if (responseTenantId != tenantId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane remote command coordinator response did not match requested tenant_id");
    }
    if (!coordinatorId.equals(coordinator.getCoordinatorId())) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane remote command coordinator response did not match requested coordinator_id");
    }
    return toDto(coordinator);
  }

  @Override
  @Timed(value = "loggingadmin.remoteCommandCoordinator.listRemoteCommandCoordinators")
  public List<RemoteCommandCoordinatorDto> listRemoteCommandCoordinators(
      long tenantId, RemoteCommandCoordinatorListRequest request) {
    SessionContext.requireTenantAccess(tenantId);
    ListRemoteCommandCoordinatorsResponse response =
        gameSessionControlPlaneClient.listRemoteCommandCoordinators(toRequest(tenantId, request));
    requireNoError(response.getError());
    return response.getCoordinatorsList().stream()
        .map(
            coordinator -> {
              long responseTenantId = parseLong(coordinator.getTenantId(), "tenant_id");
              if (responseTenantId != tenantId) {
                throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "control-plane remote command coordinator list response did not match requested tenant_id");
              }
              return toDto(coordinator);
            })
        .toList();
  }

  private ListRemoteCommandCoordinatorsRequest toRequest(
      long tenantId, RemoteCommandCoordinatorListRequest request) {
    ListRemoteCommandCoordinatorsRequest.Builder builder =
        ListRemoteCommandCoordinatorsRequest.newBuilder().setTenantId(Long.toString(tenantId));
    if (hasText(request.getOriginGameInstanceId())) {
      builder.setOriginGameInstanceId(request.getOriginGameInstanceId());
    }
    if (hasText(request.getOriginRegionId())) {
      builder.setOriginRegionId(request.getOriginRegionId());
    }
    if (hasText(request.getTargetGameInstanceId())) {
      builder.setTargetGameInstanceId(request.getTargetGameInstanceId());
    }
    if (hasText(request.getTargetRegionId())) {
      builder.setTargetRegionId(request.getTargetRegionId());
    }
    if (hasText(request.getState())) {
      builder.setState(request.getState());
    }
    if (hasText(request.getFollowupId())) {
      builder.setFollowupId(request.getFollowupId());
    }
    if (hasText(request.getCommandId())) {
      builder.setCommandId(request.getCommandId());
    }
    if (request.getLimit() != null) {
      builder.setLimit(request.getLimit());
    }
    return builder.build();
  }

  private RemoteCommandCoordinatorDto toDto(RemoteCommandCoordinatorEntry coordinator) {
    return new RemoteCommandCoordinatorDto(
        coordinator.getCoordinatorId(),
        parseLong(coordinator.getTenantId(), "tenant_id"),
        blankToNull(coordinator.getCommandId()),
        blankToNull(coordinator.getFollowupId()),
        parseLong(coordinator.getOriginGameInstanceId(), "origin_game_instance_id"),
        blankToNull(coordinator.getOriginRegionId()),
        coordinator.getOriginRegionEpoch(),
        parseLong(coordinator.getTargetGameInstanceId(), "target_game_instance_id"),
        blankToNull(coordinator.getTargetRegionId()),
        coordinator.getTargetRegionEpoch(),
        optionalLong(coordinator.getTargetDueTickId()),
        optionalLong(coordinator.getOriginDeadlineRegionEpoch()),
        optionalLong(coordinator.getOriginDeadlineTickId()),
        blankToNull(coordinator.getState()),
        blankToNull(coordinator.getLateResultPolicy()),
        blankToNull(coordinator.getExecutionOutcome()),
        blankToNull(coordinator.getGameplayResult()),
        toInstant(coordinator.getUpdatedAtMs()),
        blankToNull(coordinator.getFollowupStatus()),
        parseOptionalLong(
            coordinator.getFollowupClaimedTickBatchId(), "followup_claimed_tick_batch_id"),
        blankToNull(coordinator.getLatestResultOutcome()),
        blankToNull(coordinator.getLatestResultPayloadJson()),
        toInstant(coordinator.getLatestResultObservedAtMs()),
        optionalLong(coordinator.getFollowupClaimOrdinal()),
        blankToNull(coordinator.getScriptPatchVersion()),
        blankToNull(coordinator.getPluginId()),
        blankToNull(coordinator.getPluginVersionId()),
        toDto(coordinator.getPublication()),
        coordinator.getPlayableStateScope().name(),
        blankToNull(coordinator.getWorldSlug()),
        blankToNull(coordinator.getRealmSlug()),
        optionalLong(coordinator.getPointerVersion()),
        blankToNull(coordinator.getAutomationDispatchId()),
        blankToNull(coordinator.getAutomationWorkItemId()),
        blankToNull(coordinator.getScriptId()),
        blankToNull(coordinator.getFollowupPayloadKind()),
        blankToNull(coordinator.getFollowupRequestedCommand()),
        blankToNull(coordinator.getLatestResultCommandId()),
        blankToNull(coordinator.getLatestResultErrorCode()),
        blankToNull(coordinator.getTargetCommandId()),
        blankToNull(coordinator.getTargetCommandExecutionOutcome()),
        blankToNull(coordinator.getTargetCommandGameplayResult()),
        blankToNull(coordinator.getLatestResultMessage()),
        toDto(coordinator.getPluginPublication()),
        coordinator.getFollowupRequiresSoloTick(),
        blankToNull(coordinator.getFollowupOriginSourceKind()),
        blankToNull(coordinator.getFollowupOriginSourceState()),
        optionalLong(coordinator.getFollowupOriginSourceOrdinal()),
        optionalLong(coordinator.getFollowupOriginSourceDueTickId()),
        optionalLong(coordinator.getFollowupOriginSourceDueAtMs()),
        blankToNull(coordinator.getTargetEntityId()),
        blankToNull(coordinator.getFollowupEffectKey()),
        blankToNull(coordinator.getFollowupFailureCode()),
        blankToNull(coordinator.getFollowupFailureMessage()),
        blankToNull(coordinator.getFollowupEventType()),
        blankToNull(coordinator.getFollowupEventSchemaVersion()),
        blankToNull(coordinator.getFollowupScriptEventId()),
        blankToNull(coordinator.getFollowupTriggerMode()),
        blankToNull(coordinator.getFollowupReadSnapshotToken()),
        blankToNull(coordinator.getFollowupEventPayloadJson()),
        blankToNull(coordinator.getFollowupClaimTargetAggregate()),
        blankToNull(coordinator.getCurrentOriginRuntimeRegionId()),
        optionalLong(coordinator.getCurrentOriginRuntimeRegionEpoch()),
        blankToNull(coordinator.getCurrentTargetRuntimeRegionId()),
        optionalLong(coordinator.getCurrentTargetRuntimeRegionEpoch()),
        blankToNull(coordinator.getFollowupQueueSourceKind()),
        blankToNull(coordinator.getFollowupQueueSourceState()),
        optionalLong(coordinator.getFollowupQueueSourceOrdinal()),
        optionalLong(coordinator.getFollowupQueueSourceDueTickId()),
        optionalLong(coordinator.getFollowupQueueSourceDueAtMs()),
        parseOptionalLong(
            coordinator.getCurrentOriginRuntimeGameInstanceId(),
            "current_origin_runtime_game_instance_id"),
        parseOptionalLong(
            coordinator.getCurrentTargetRuntimeGameInstanceId(),
            "current_target_runtime_game_instance_id"),
        coordinator.getCurrentOriginRuntimePlayableStateScope().name(),
        blankToNull(coordinator.getCurrentOriginRuntimeWorldSlug()),
        blankToNull(coordinator.getCurrentOriginRuntimeRealmSlug()),
        optionalLong(coordinator.getCurrentOriginRuntimePointerVersion()),
        coordinator.getCurrentTargetRuntimePlayableStateScope().name(),
        blankToNull(coordinator.getCurrentTargetRuntimeWorldSlug()),
        blankToNull(coordinator.getCurrentTargetRuntimeRealmSlug()),
        optionalLong(coordinator.getCurrentTargetRuntimePointerVersion()),
        coordinator.getIsOriginRoutingBundleStale(),
        coordinator.getIsTargetRoutingBundleStale());
  }

  private RemoteCommandCoordinatorDto.ScriptPatchPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink publication) {
    return new RemoteCommandCoordinatorDto.ScriptPatchPublicationLinkDto(
        blankToNull(publication.getScriptPatchVersion()),
        optionalLong(publication.getVersionId()),
        optionalLong(publication.getBaseVersionId()),
        publication.getPublicationState().name(),
        toInstant(publication.getLastChangedAtMs()),
        blankToNull(publication.getLookupErrorCode()),
        blankToNull(publication.getLookupErrorMessage()));
  }

  private RemoteCommandCoordinatorDto.PluginPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.PluginPublicationLink publication) {
    return new RemoteCommandCoordinatorDto.PluginPublicationLinkDto(
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

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
