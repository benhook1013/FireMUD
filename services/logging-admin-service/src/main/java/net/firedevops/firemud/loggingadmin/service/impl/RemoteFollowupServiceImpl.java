package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsResponse;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupEntry;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupListRequest;
import net.firedevops.firemud.loggingadmin.service.RemoteFollowupService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed gRPC client dependency is stored and not exposed")
public class RemoteFollowupServiceImpl implements RemoteFollowupService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public RemoteFollowupServiceImpl(GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.remoteFollowup.getRemoteFollowup")
  public RemoteFollowupDto getRemoteFollowup(long tenantId, String followupId) {
    SessionContext.requireTenantAccess(tenantId);
    GetRemoteFollowupResponse response =
        gameSessionControlPlaneClient.getRemoteFollowup(tenantId, followupId);
    requireNoError(response.getError());
    RemoteFollowupEntry followup = response.getFollowup();
    if (followup.getFollowupId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Remote followup not found");
    }
    long responseTenantId = parseLong(followup.getTenantId(), "tenant_id");
    if (responseTenantId != tenantId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane remote followup response did not match requested tenant_id");
    }
    if (!followupId.equals(followup.getFollowupId())) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane remote followup response did not match requested followup_id");
    }
    return toDto(followup);
  }

  @Override
  @Timed(value = "loggingadmin.remoteFollowup.listRemoteFollowups")
  public List<RemoteFollowupDto> listRemoteFollowups(
      long tenantId, RemoteFollowupListRequest request) {
    SessionContext.requireTenantAccess(tenantId);
    ListRemoteFollowupsResponse response =
        gameSessionControlPlaneClient.listRemoteFollowups(toRequest(tenantId, request));
    requireNoError(response.getError());
    return response.getFollowupsList().stream()
        .map(
            followup -> {
              long responseTenantId = parseLong(followup.getTenantId(), "tenant_id");
              if (responseTenantId != tenantId) {
                throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "control-plane remote followup list response did not match requested tenant_id");
              }
              return toDto(followup);
            })
        .toList();
  }

  private ListRemoteFollowupsRequest toRequest(long tenantId, RemoteFollowupListRequest request) {
    ListRemoteFollowupsRequest.Builder builder =
        ListRemoteFollowupsRequest.newBuilder().setTenantId(Long.toString(tenantId));
    if (hasText(request.getTargetRegionId())) {
      builder.setTargetRegionId(request.getTargetRegionId());
    }
    if (hasText(request.getStatus())) {
      builder.setStatus(request.getStatus());
    }
    if (hasText(request.getOriginGameInstanceId())) {
      builder.setOriginGameInstanceId(request.getOriginGameInstanceId());
    }
    if (hasText(request.getOriginRegionId())) {
      builder.setOriginRegionId(request.getOriginRegionId());
    }
    if (hasText(request.getTargetGameInstanceId())) {
      builder.setTargetGameInstanceId(request.getTargetGameInstanceId());
    }
    if (request.getTargetRegionEpoch() != null) {
      builder.setTargetRegionEpoch(request.getTargetRegionEpoch());
    }
    if (hasText(request.getFollowupId())) {
      builder.setFollowupId(request.getFollowupId());
    }
    if (hasText(request.getScriptId())) {
      builder.setScriptId(request.getScriptId());
    }
    if (hasText(request.getPluginId())) {
      builder.setPluginId(request.getPluginId());
    }
    if (hasText(request.getAutomationDispatchId())) {
      builder.setAutomationDispatchId(request.getAutomationDispatchId());
    }
    if (hasText(request.getCommandId())) {
      builder.setCommandId(request.getCommandId());
    }
    if (request.getLimit() != null) {
      builder.setLimit(request.getLimit());
    }
    if (request.getOriginRegionEpoch() != null) {
      builder.setOriginRegionEpoch(request.getOriginRegionEpoch());
    }
    if (hasText(request.getScriptPatchVersion())) {
      builder.setScriptPatchVersion(request.getScriptPatchVersion());
    }
    if (hasText(request.getPluginVersionId())) {
      builder.setPluginVersionId(request.getPluginVersionId());
    }
    if (hasText(request.getPlayableStateScope())) {
      builder.setPlayableStateScope(parsePlayableStateScope(request.getPlayableStateScope()));
    }
    if (hasText(request.getWorldSlug())) {
      builder.setWorldSlug(request.getWorldSlug());
    }
    if (hasText(request.getRealmSlug())) {
      builder.setRealmSlug(request.getRealmSlug());
    }
    if (request.getPointerVersion() != null) {
      builder.setPointerVersion(request.getPointerVersion());
    }
    if (hasText(request.getPayloadKind())) {
      builder.setPayloadKind(request.getPayloadKind());
    }
    if (hasText(request.getOriginSourceKind())) {
      builder.setOriginSourceKind(request.getOriginSourceKind());
    }
    if (hasText(request.getAutomationWorkItemId())) {
      builder.setAutomationWorkItemId(request.getAutomationWorkItemId());
    }
    if (hasText(request.getTargetEntityId())) {
      builder.setTargetEntityId(request.getTargetEntityId());
    }
    if (hasText(request.getEffectKey())) {
      builder.setEffectKey(request.getEffectKey());
    }
    if (hasText(request.getFailureCode())) {
      builder.setFailureCode(request.getFailureCode());
    }
    if (Boolean.TRUE.equals(request.getRequiresSoloTick())) {
      builder.setRequiresSoloTick(true);
    }
    if (hasText(request.getEventType())) {
      builder.setEventType(request.getEventType());
    }
    if (hasText(request.getScriptEventId())) {
      builder.setScriptEventId(request.getScriptEventId());
    }
    if (hasText(request.getTargetCommandId())) {
      builder.setTargetCommandId(request.getTargetCommandId());
    }
    if (hasText(request.getTargetCommandExecutionOutcome())) {
      builder.setTargetCommandExecutionOutcome(request.getTargetCommandExecutionOutcome());
    }
    if (hasText(request.getTargetCommandGameplayResult())) {
      builder.setTargetCommandGameplayResult(request.getTargetCommandGameplayResult());
    }
    if (hasText(request.getClaimedTickBatchId())) {
      builder.setClaimedTickBatchId(request.getClaimedTickBatchId());
    }
    if (hasText(request.getRequestedCommand())) {
      builder.setRequestedCommand(request.getRequestedCommand());
    }
    if (hasText(request.getOriginSourceState())) {
      builder.setOriginSourceState(request.getOriginSourceState());
    }
    if (request.getOriginDeadlineRegionEpoch() != null) {
      builder.setOriginDeadlineRegionEpoch(request.getOriginDeadlineRegionEpoch());
    }
    if (request.getOriginDeadlineTickId() != null) {
      builder.setOriginDeadlineTickId(request.getOriginDeadlineTickId());
    }
    if (hasText(request.getLateResultPolicy())) {
      builder.setLateResultPolicy(request.getLateResultPolicy());
    }
    if (hasText(request.getClaimTargetAggregate())) {
      builder.setClaimTargetAggregate(request.getClaimTargetAggregate());
    }
    if (hasText(request.getCurrentOriginRuntimeRegionId())) {
      builder.setCurrentOriginRuntimeRegionId(request.getCurrentOriginRuntimeRegionId());
    }
    if (request.getCurrentOriginRuntimeRegionEpoch() != null) {
      builder.setCurrentOriginRuntimeRegionEpoch(request.getCurrentOriginRuntimeRegionEpoch());
    }
    if (hasText(request.getCurrentTargetRuntimeRegionId())) {
      builder.setCurrentTargetRuntimeRegionId(request.getCurrentTargetRuntimeRegionId());
    }
    if (request.getCurrentTargetRuntimeRegionEpoch() != null) {
      builder.setCurrentTargetRuntimeRegionEpoch(request.getCurrentTargetRuntimeRegionEpoch());
    }
    if (hasText(request.getQueueSourceKind())) {
      builder.setQueueSourceKind(request.getQueueSourceKind());
    }
    if (hasText(request.getQueueSourceState())) {
      builder.setQueueSourceState(request.getQueueSourceState());
    }
    if (request.getQueueSourceOrdinal() != null) {
      builder.setQueueSourceOrdinal(request.getQueueSourceOrdinal());
    }
    if (request.getQueueSourceDueTickId() != null) {
      builder.setQueueSourceDueTickId(request.getQueueSourceDueTickId());
    }
    if (request.getQueueSourceDueAtMs() != null) {
      builder.setQueueSourceDueAtMs(request.getQueueSourceDueAtMs());
    }
    if (hasText(request.getCurrentOriginRuntimeGameInstanceId())) {
      builder.setCurrentOriginRuntimeGameInstanceId(
          request.getCurrentOriginRuntimeGameInstanceId());
    }
    if (hasText(request.getCurrentTargetRuntimeGameInstanceId())) {
      builder.setCurrentTargetRuntimeGameInstanceId(
          request.getCurrentTargetRuntimeGameInstanceId());
    }
    return builder.build();
  }

  private RemoteFollowupDto toDto(RemoteFollowupEntry followup) {
    return new RemoteFollowupDto(
        followup.getFollowupId(),
        parseLong(followup.getTenantId(), "tenant_id"),
        parseLong(followup.getOriginGameInstanceId(), "origin_game_instance_id"),
        blankToNull(followup.getOriginRegionId()),
        followup.getOriginRegionEpoch(),
        parseLong(followup.getTargetGameInstanceId(), "target_game_instance_id"),
        blankToNull(followup.getTargetRegionId()),
        followup.getTargetRegionEpoch(),
        optionalLong(followup.getDueTickId()),
        blankToNull(followup.getEffectKey()),
        blankToNull(followup.getTargetEntityId()),
        blankToNull(followup.getStatus()),
        blankToNull(followup.getClaimedTickBatchId()),
        blankToNull(followup.getPayloadJson()),
        blankToNull(followup.getFailureCode()),
        blankToNull(followup.getFailureMessage()),
        toInstant(followup.getCreatedAtMs()),
        toInstant(followup.getUpdatedAtMs()),
        optionalLong(followup.getClaimOrdinal()),
        blankToNull(followup.getScriptPatchVersion()),
        blankToNull(followup.getPluginId()),
        blankToNull(followup.getPluginVersionId()),
        toDto(followup.getPublication()),
        followup.getPlayableStateScope().name(),
        blankToNull(followup.getWorldSlug()),
        blankToNull(followup.getRealmSlug()),
        optionalLong(followup.getPointerVersion()),
        blankToNull(followup.getCommandId()),
        blankToNull(followup.getAutomationDispatchId()),
        blankToNull(followup.getAutomationWorkItemId()),
        blankToNull(followup.getScriptId()),
        blankToNull(followup.getPayloadKind()),
        blankToNull(followup.getRequestedCommand()),
        blankToNull(followup.getTargetCommandId()),
        blankToNull(followup.getTargetCommandExecutionOutcome()),
        blankToNull(followup.getTargetCommandGameplayResult()),
        toDto(followup.getPluginPublication()),
        followup.getRequiresSoloTick(),
        blankToNull(followup.getOriginSourceKind()),
        blankToNull(followup.getOriginSourceState()),
        optionalLong(followup.getOriginSourceOrdinal()),
        optionalLong(followup.getOriginSourceDueTickId()),
        optionalLong(followup.getOriginSourceDueAtMs()),
        optionalLong(followup.getOriginDeadlineRegionEpoch()),
        optionalLong(followup.getOriginDeadlineTickId()),
        blankToNull(followup.getLateResultPolicy()),
        blankToNull(followup.getEventType()),
        blankToNull(followup.getEventSchemaVersion()),
        blankToNull(followup.getScriptEventId()),
        blankToNull(followup.getTriggerMode()),
        blankToNull(followup.getReadSnapshotToken()),
        blankToNull(followup.getEventPayloadJson()),
        blankToNull(followup.getClaimTargetAggregate()),
        blankToNull(followup.getCurrentOriginRuntimeRegionId()),
        optionalLong(followup.getCurrentOriginRuntimeRegionEpoch()),
        blankToNull(followup.getCurrentTargetRuntimeRegionId()),
        optionalLong(followup.getCurrentTargetRuntimeRegionEpoch()),
        blankToNull(followup.getQueueSourceKind()),
        blankToNull(followup.getQueueSourceState()),
        optionalLong(followup.getQueueSourceOrdinal()),
        optionalLong(followup.getQueueSourceDueTickId()),
        optionalLong(followup.getQueueSourceDueAtMs()),
        parseOptionalLong(
            followup.getCurrentOriginRuntimeGameInstanceId(),
            "current_origin_runtime_game_instance_id"),
        parseOptionalLong(
            followup.getCurrentTargetRuntimeGameInstanceId(),
            "current_target_runtime_game_instance_id"),
        followup.getCurrentOriginRuntimePlayableStateScope().name(),
        blankToNull(followup.getCurrentOriginRuntimeWorldSlug()),
        blankToNull(followup.getCurrentOriginRuntimeRealmSlug()),
        optionalLong(followup.getCurrentOriginRuntimePointerVersion()),
        followup.getCurrentTargetRuntimePlayableStateScope().name(),
        blankToNull(followup.getCurrentTargetRuntimeWorldSlug()),
        blankToNull(followup.getCurrentTargetRuntimeRealmSlug()),
        optionalLong(followup.getCurrentTargetRuntimePointerVersion()),
        followup.getIsOriginRoutingBundleStale(),
        followup.getIsTargetRoutingBundleStale());
  }

  private RemoteFollowupDto.ScriptPatchPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink publication) {
    return new RemoteFollowupDto.ScriptPatchPublicationLinkDto(
        blankToNull(publication.getScriptPatchVersion()),
        optionalLong(publication.getVersionId()),
        optionalLong(publication.getBaseVersionId()),
        publication.getPublicationState().name(),
        toInstant(publication.getLastChangedAtMs()),
        blankToNull(publication.getLookupErrorCode()),
        blankToNull(publication.getLookupErrorMessage()));
  }

  private RemoteFollowupDto.PluginPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.PluginPublicationLink publication) {
    return new RemoteFollowupDto.PluginPublicationLinkDto(
        blankToNull(publication.getPluginVersionId()),
        optionalLong(publication.getPublicationId()),
        publication.getPublicationState().name(),
        blankToNull(publication.getStatusReason()),
        toInstant(publication.getLastChangedAtMs()),
        blankToNull(publication.getLookupErrorCode()),
        blankToNull(publication.getLookupErrorMessage()));
  }

  private PlayableStateScope parsePlayableStateScope(String value) {
    try {
      return PlayableStateScope.valueOf(value);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid playableStateScope: " + value, ex);
    }
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
