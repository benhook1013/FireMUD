package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResultResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsResponse;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupResultEntry;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultListRequest;
import net.firedevops.firemud.loggingadmin.service.RemoteFollowupResultService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed gRPC client dependency is stored and not exposed")
public class RemoteFollowupResultServiceImpl implements RemoteFollowupResultService {
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public RemoteFollowupResultServiceImpl(
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
  }

  @Override
  @Timed(value = "loggingadmin.remoteFollowupResult.getRemoteFollowupResult")
  public RemoteFollowupResultDto getRemoteFollowupResult(long tenantId, String resultId) {
    SessionContext.requireTenantAccess(tenantId);
    GetRemoteFollowupResultResponse response =
        gameSessionControlPlaneClient.getRemoteFollowupResult(tenantId, resultId);
    requireNoError(response.getError());
    RemoteFollowupResultEntry result = response.getResult();
    if (result.getResultId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Remote followup result not found");
    }
    long responseTenantId = parseLong(result.getTenantId(), "tenant_id");
    if (responseTenantId != tenantId) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane remote followup result response did not match requested tenant_id");
    }
    if (!resultId.equals(result.getResultId())) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "control-plane remote followup result response did not match requested result_id");
    }
    return toDto(result);
  }

  @Override
  @Timed(value = "loggingadmin.remoteFollowupResult.listRemoteFollowupResults")
  public List<RemoteFollowupResultDto> listRemoteFollowupResults(
      long tenantId, RemoteFollowupResultListRequest request) {
    SessionContext.requireTenantAccess(tenantId);
    ListRemoteFollowupResultsResponse response =
        gameSessionControlPlaneClient.listRemoteFollowupResults(toRequest(tenantId, request));
    requireNoError(response.getError());
    return response.getResultsList().stream()
        .map(
            result -> {
              long responseTenantId = parseLong(result.getTenantId(), "tenant_id");
              if (responseTenantId != tenantId) {
                throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "control-plane remote followup result list response did not match requested tenant_id");
              }
              return toDto(result);
            })
        .toList();
  }

  private ListRemoteFollowupResultsRequest toRequest(
      long tenantId, RemoteFollowupResultListRequest request) {
    ListRemoteFollowupResultsRequest.Builder builder =
        ListRemoteFollowupResultsRequest.newBuilder().setTenantId(Long.toString(tenantId));
    if (hasText(request.getCoordinatorId())) {
      builder.setCoordinatorId(request.getCoordinatorId());
    }
    if (hasText(request.getFollowupId())) {
      builder.setFollowupId(request.getFollowupId());
    }
    if (hasText(request.getOriginRegionId())) {
      builder.setOriginRegionId(request.getOriginRegionId());
    }
    if (hasText(request.getTargetRegionId())) {
      builder.setTargetRegionId(request.getTargetRegionId());
    }
    if (hasText(request.getOutcome())) {
      builder.setOutcome(request.getOutcome());
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
    if (hasText(request.getOriginGameInstanceId())) {
      builder.setOriginGameInstanceId(request.getOriginGameInstanceId());
    }
    if (hasText(request.getTargetGameInstanceId())) {
      builder.setTargetGameInstanceId(request.getTargetGameInstanceId());
    }
    if (request.getOriginRegionEpoch() != null) {
      builder.setOriginRegionEpoch(request.getOriginRegionEpoch());
    }
    if (request.getTargetRegionEpoch() != null) {
      builder.setTargetRegionEpoch(request.getTargetRegionEpoch());
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
    if (hasText(request.getResultErrorCode())) {
      builder.setResultErrorCode(request.getResultErrorCode());
    }
    if (hasText(request.getAutomationWorkItemId())) {
      builder.setAutomationWorkItemId(request.getAutomationWorkItemId());
    }
    if (hasText(request.getResultCommandId())) {
      builder.setResultCommandId(request.getResultCommandId());
    }
    if (hasText(request.getResultCommandExecutionOutcome())) {
      builder.setResultCommandExecutionOutcome(request.getResultCommandExecutionOutcome());
    }
    if (hasText(request.getResultCommandGameplayResult())) {
      builder.setResultCommandGameplayResult(request.getResultCommandGameplayResult());
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
    if (hasText(request.getPayloadKind())) {
      builder.setPayloadKind(request.getPayloadKind());
    }
    if (hasText(request.getOriginSourceKind())) {
      builder.setOriginSourceKind(request.getOriginSourceKind());
    }
    if (hasText(request.getEventType())) {
      builder.setEventType(request.getEventType());
    }
    if (hasText(request.getScriptEventId())) {
      builder.setScriptEventId(request.getScriptEventId());
    }
    if (hasText(request.getResultMessage())) {
      builder.setResultMessage(request.getResultMessage());
    }
    if (Boolean.TRUE.equals(request.getRequiresSoloTick())) {
      builder.setRequiresSoloTick(true);
    }
    if (hasText(request.getOriginSourceState())) {
      builder.setOriginSourceState(request.getOriginSourceState());
    }
    if (hasText(request.getLateResultPolicy())) {
      builder.setLateResultPolicy(request.getLateResultPolicy());
    }
    if (hasText(request.getClaimedTickBatchId())) {
      builder.setClaimedTickBatchId(request.getClaimedTickBatchId());
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

  private RemoteFollowupResultDto toDto(RemoteFollowupResultEntry result) {
    return new RemoteFollowupResultDto(
        result.getResultId(),
        parseLong(result.getTenantId(), "tenant_id"),
        blankToNull(result.getCoordinatorId()),
        blankToNull(result.getFollowupId()),
        blankToNull(result.getOriginRegionId()),
        optionalLong(result.getOriginRegionEpoch()),
        blankToNull(result.getTargetRegionId()),
        optionalLong(result.getTargetRegionEpoch()),
        blankToNull(result.getOutcome()),
        blankToNull(result.getResultPayloadJson()),
        toInstant(result.getObservedAtMs()),
        blankToNull(result.getScriptPatchVersion()),
        blankToNull(result.getPluginId()),
        blankToNull(result.getPluginVersionId()),
        toDto(result.getPublication()),
        result.getPlayableStateScope().name(),
        blankToNull(result.getWorldSlug()),
        blankToNull(result.getRealmSlug()),
        optionalLong(result.getPointerVersion()),
        blankToNull(result.getCommandId()),
        blankToNull(result.getAutomationDispatchId()),
        blankToNull(result.getAutomationWorkItemId()),
        blankToNull(result.getScriptId()),
        blankToNull(result.getResultCommandId()),
        blankToNull(result.getResultErrorCode()),
        blankToNull(result.getResultCommandExecutionOutcome()),
        blankToNull(result.getResultCommandGameplayResult()),
        blankToNull(result.getResultMessage()),
        toDto(result.getPluginPublication()),
        optionalLong(result.getOriginDeadlineRegionEpoch()),
        optionalLong(result.getOriginDeadlineTickId()),
        blankToNull(result.getLateResultPolicy()),
        blankToNull(result.getOriginGameInstanceId()),
        blankToNull(result.getTargetGameInstanceId()),
        blankToNull(result.getTargetEntityId()),
        blankToNull(result.getEffectKey()),
        blankToNull(result.getPayloadKind()),
        result.getRequiresSoloTick(),
        blankToNull(result.getOriginSourceKind()),
        blankToNull(result.getOriginSourceState()),
        optionalLong(result.getOriginSourceOrdinal()),
        optionalLong(result.getOriginSourceDueTickId()),
        optionalLong(result.getOriginSourceDueAtMs()),
        blankToNull(result.getFailureCode()),
        blankToNull(result.getFailureMessage()),
        blankToNull(result.getEventType()),
        blankToNull(result.getEventSchemaVersion()),
        blankToNull(result.getScriptEventId()),
        blankToNull(result.getTriggerMode()),
        blankToNull(result.getReadSnapshotToken()),
        blankToNull(result.getEventPayloadJson()),
        blankToNull(result.getClaimTargetAggregate()),
        blankToNull(result.getCurrentOriginRuntimeRegionId()),
        optionalLong(result.getCurrentOriginRuntimeRegionEpoch()),
        blankToNull(result.getCurrentTargetRuntimeRegionId()),
        optionalLong(result.getCurrentTargetRuntimeRegionEpoch()),
        blankToNull(result.getQueueSourceKind()),
        blankToNull(result.getQueueSourceState()),
        optionalLong(result.getQueueSourceOrdinal()),
        optionalLong(result.getQueueSourceDueTickId()),
        optionalLong(result.getQueueSourceDueAtMs()),
        blankToNull(result.getCurrentOriginRuntimeGameInstanceId()),
        blankToNull(result.getCurrentTargetRuntimeGameInstanceId()),
        result.getCurrentOriginRuntimePlayableStateScope().name(),
        blankToNull(result.getCurrentOriginRuntimeWorldSlug()),
        blankToNull(result.getCurrentOriginRuntimeRealmSlug()),
        optionalLong(result.getCurrentOriginRuntimePointerVersion()),
        result.getCurrentTargetRuntimePlayableStateScope().name(),
        blankToNull(result.getCurrentTargetRuntimeWorldSlug()),
        blankToNull(result.getCurrentTargetRuntimeRealmSlug()),
        optionalLong(result.getCurrentTargetRuntimePointerVersion()),
        result.getIsOriginRoutingBundleStale(),
        result.getIsTargetRoutingBundleStale());
  }

  private RemoteFollowupResultDto.ScriptPatchPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink publication) {
    return new RemoteFollowupResultDto.ScriptPatchPublicationLinkDto(
        blankToNull(publication.getScriptPatchVersion()),
        optionalLong(publication.getVersionId()),
        optionalLong(publication.getBaseVersionId()),
        publication.getPublicationState().name(),
        toInstant(publication.getLastChangedAtMs()),
        blankToNull(publication.getLookupErrorCode()),
        blankToNull(publication.getLookupErrorMessage()));
  }

  private RemoteFollowupResultDto.PluginPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.PluginPublicationLink publication) {
    return new RemoteFollowupResultDto.PluginPublicationLinkDto(
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
