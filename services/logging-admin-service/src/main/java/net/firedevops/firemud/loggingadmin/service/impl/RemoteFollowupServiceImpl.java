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
    return RemoteFollowupDto.builder()
        .followupId(followup.getFollowupId())
        .tenantId(parseLong(followup.getTenantId(), "tenant_id"))
        .originGameInstanceId(
            parseLong(followup.getOriginGameInstanceId(), "origin_game_instance_id"))
        .originRegionId(blankToNull(followup.getOriginRegionId()))
        .originRegionEpoch(followup.getOriginRegionEpoch())
        .targetGameInstanceId(
            parseLong(followup.getTargetGameInstanceId(), "target_game_instance_id"))
        .targetRegionId(blankToNull(followup.getTargetRegionId()))
        .targetRegionEpoch(followup.getTargetRegionEpoch())
        .dueTickId(optionalLong(followup.getDueTickId()))
        .effectKey(blankToNull(followup.getEffectKey()))
        .targetEntityId(blankToNull(followup.getTargetEntityId()))
        .status(blankToNull(followup.getStatus()))
        .claimedTickBatchId(blankToNull(followup.getClaimedTickBatchId()))
        .payloadJson(blankToNull(followup.getPayloadJson()))
        .failureCode(blankToNull(followup.getFailureCode()))
        .failureMessage(blankToNull(followup.getFailureMessage()))
        .createdAt(toInstant(followup.getCreatedAtMs()))
        .updatedAt(toInstant(followup.getUpdatedAtMs()))
        .claimOrdinal(optionalLong(followup.getClaimOrdinal()))
        .scriptPatchVersion(blankToNull(followup.getScriptPatchVersion()))
        .pluginId(blankToNull(followup.getPluginId()))
        .pluginVersionId(blankToNull(followup.getPluginVersionId()))
        .publication(toDto(followup.getPublication()))
        .playableStateScope(followup.getPlayableStateScope().name())
        .worldSlug(blankToNull(followup.getWorldSlug()))
        .realmSlug(blankToNull(followup.getRealmSlug()))
        .pointerVersion(optionalLong(followup.getPointerVersion()))
        .commandId(blankToNull(followup.getCommandId()))
        .automationDispatchId(blankToNull(followup.getAutomationDispatchId()))
        .automationWorkItemId(blankToNull(followup.getAutomationWorkItemId()))
        .scriptId(blankToNull(followup.getScriptId()))
        .payloadKind(blankToNull(followup.getPayloadKind()))
        .requestedCommand(blankToNull(followup.getRequestedCommand()))
        .targetCommandId(blankToNull(followup.getTargetCommandId()))
        .targetCommandExecutionOutcome(blankToNull(followup.getTargetCommandExecutionOutcome()))
        .targetCommandGameplayResult(blankToNull(followup.getTargetCommandGameplayResult()))
        .pluginPublication(toDto(followup.getPluginPublication()))
        .requiresSoloTick(followup.getRequiresSoloTick())
        .originSourceKind(blankToNull(followup.getOriginSourceKind()))
        .originSourceState(blankToNull(followup.getOriginSourceState()))
        .originSourceOrdinal(optionalLong(followup.getOriginSourceOrdinal()))
        .originSourceDueTickId(optionalLong(followup.getOriginSourceDueTickId()))
        .originSourceDueAtMs(optionalLong(followup.getOriginSourceDueAtMs()))
        .originDeadlineRegionEpoch(optionalLong(followup.getOriginDeadlineRegionEpoch()))
        .originDeadlineTickId(optionalLong(followup.getOriginDeadlineTickId()))
        .lateResultPolicy(blankToNull(followup.getLateResultPolicy()))
        .eventType(blankToNull(followup.getEventType()))
        .eventSchemaVersion(blankToNull(followup.getEventSchemaVersion()))
        .scriptEventId(blankToNull(followup.getScriptEventId()))
        .triggerMode(blankToNull(followup.getTriggerMode()))
        .readSnapshotToken(blankToNull(followup.getReadSnapshotToken()))
        .eventPayloadJson(blankToNull(followup.getEventPayloadJson()))
        .claimTargetAggregate(blankToNull(followup.getClaimTargetAggregate()))
        .currentOriginRuntimeRegionId(blankToNull(followup.getCurrentOriginRuntimeRegionId()))
        .currentOriginRuntimeRegionEpoch(
            optionalLong(followup.getCurrentOriginRuntimeRegionEpoch()))
        .currentTargetRuntimeRegionId(blankToNull(followup.getCurrentTargetRuntimeRegionId()))
        .currentTargetRuntimeRegionEpoch(
            optionalLong(followup.getCurrentTargetRuntimeRegionEpoch()))
        .queueSourceKind(blankToNull(followup.getQueueSourceKind()))
        .queueSourceState(blankToNull(followup.getQueueSourceState()))
        .queueSourceOrdinal(optionalLong(followup.getQueueSourceOrdinal()))
        .queueSourceDueTickId(optionalLong(followup.getQueueSourceDueTickId()))
        .queueSourceDueAtMs(optionalLong(followup.getQueueSourceDueAtMs()))
        .currentOriginRuntimeGameInstanceId(
            parseOptionalLong(
                followup.getCurrentOriginRuntimeGameInstanceId(),
                "current_origin_runtime_game_instance_id"))
        .currentTargetRuntimeGameInstanceId(
            parseOptionalLong(
                followup.getCurrentTargetRuntimeGameInstanceId(),
                "current_target_runtime_game_instance_id"))
        .currentOriginRuntimePlayableStateScope(
            followup.getCurrentOriginRuntimePlayableStateScope().name())
        .currentOriginRuntimeWorldSlug(blankToNull(followup.getCurrentOriginRuntimeWorldSlug()))
        .currentOriginRuntimeRealmSlug(blankToNull(followup.getCurrentOriginRuntimeRealmSlug()))
        .currentOriginRuntimePointerVersion(
            optionalLong(followup.getCurrentOriginRuntimePointerVersion()))
        .currentTargetRuntimePlayableStateScope(
            followup.getCurrentTargetRuntimePlayableStateScope().name())
        .currentTargetRuntimeWorldSlug(blankToNull(followup.getCurrentTargetRuntimeWorldSlug()))
        .currentTargetRuntimeRealmSlug(blankToNull(followup.getCurrentTargetRuntimeRealmSlug()))
        .currentTargetRuntimePointerVersion(
            optionalLong(followup.getCurrentTargetRuntimePointerVersion()))
        .originRoutingBundleStale(followup.getIsOriginRoutingBundleStale())
        .targetRoutingBundleStale(followup.getIsTargetRoutingBundleStale())
        .build();
  }

  private RemoteFollowupDto.ScriptPatchPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink publication) {
    return RemoteFollowupDto.ScriptPatchPublicationLinkDto.builder()
        .scriptPatchVersion(blankToNull(publication.getScriptPatchVersion()))
        .versionId(optionalLong(publication.getVersionId()))
        .baseVersionId(optionalLong(publication.getBaseVersionId()))
        .publicationState(publication.getPublicationState().name())
        .lastChangedAt(toInstant(publication.getLastChangedAtMs()))
        .lookupErrorCode(blankToNull(publication.getLookupErrorCode()))
        .lookupErrorMessage(blankToNull(publication.getLookupErrorMessage()))
        .build();
  }

  private RemoteFollowupDto.PluginPublicationLinkDto toDto(
      net.firedevops.firemud.gamesession.v1.PluginPublicationLink publication) {
    return RemoteFollowupDto.PluginPublicationLinkDto.builder()
        .pluginVersionId(blankToNull(publication.getPluginVersionId()))
        .publicationId(optionalLong(publication.getPublicationId()))
        .publicationState(publication.getPublicationState().name())
        .statusReason(blankToNull(publication.getStatusReason()))
        .lastChangedAt(toInstant(publication.getLastChangedAtMs()))
        .lookupErrorCode(blankToNull(publication.getLookupErrorCode()))
        .lookupErrorMessage(blankToNull(publication.getLookupErrorMessage()))
        .build();
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
