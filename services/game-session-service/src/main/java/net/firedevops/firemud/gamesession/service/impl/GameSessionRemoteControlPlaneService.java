package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupResultRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots.RoutingBundle;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorResponse;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResultResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteCommandCoordinatorsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsRequest;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsResponse;
import net.firedevops.firemud.gamesession.v1.PluginPublicationLink;
import net.firedevops.firemud.gamesession.v1.RemoteCommandCoordinatorEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupResultEntry;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
final class GameSessionRemoteControlPlaneService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
      super(message);
    }
  }

  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private final RemoteFollowupResultRepository remoteFollowupResultRepository;
  private final RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameDesignClient gameDesignClient;

  GameSessionRemoteControlPlaneService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameDesignClient gameDesignClient) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.remoteFollowupResultRepository = remoteFollowupResultRepository;
    this.remoteFollowupRuntimeService = remoteFollowupRuntimeService;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.gameDesignClient = gameDesignClient;
  }

  GetRemoteCommandCoordinatorResponse getRemoteCommandCoordinator(
      long tenantId, String coordinatorId) {
    requireText(coordinatorId, "coordinator_id is required");
    RemoteCommandCoordinator coordinator =
        remoteCommandCoordinatorRepository
            .findByTenantIdAndCoordinatorId(tenantId, coordinatorId)
            .orElseThrow(() -> new NotFoundException("Remote command coordinator not found"));
    RemoteFollowup followup =
        remoteFollowupRepository
            .findByTenantIdAndFollowupId(tenantId, coordinator.getFollowupId())
            .filter(candidate -> matchesCoordinatorScope(candidate, coordinator))
            .orElse(null);
    return GetRemoteCommandCoordinatorResponse.newBuilder()
        .setCoordinator(
            toRemoteCoordinatorEntry(
                coordinator,
                followup,
                latestRemoteResult(coordinator),
                linkedTargetCommand(tenantId, followup),
                new HashMap<>()))
        .build();
  }

  GetRemoteFollowupResponse getRemoteFollowup(long tenantId, String followupId) {
    requireText(followupId, "followup_id is required");
    RemoteFollowup followup =
        remoteFollowupRepository
            .findByTenantIdAndFollowupId(tenantId, followupId)
            .orElseThrow(() -> new NotFoundException("Remote followup not found"));
    RemoteCommandCoordinator coordinator =
        remoteCommandCoordinatorRepository
            .findByTenantIdAndFollowupId(tenantId, followupId)
            .filter(candidate -> matchesCoordinatorScope(followup, candidate))
            .orElse(null);
    return GetRemoteFollowupResponse.newBuilder()
        .setFollowup(
            toRemoteFollowupEntry(
                followup, linkedTargetCommand(tenantId, followup), coordinator, new HashMap<>()))
        .build();
  }

  GetRemoteFollowupResultResponse getRemoteFollowupResult(long tenantId, String resultId) {
    requireText(resultId, "result_id is required");
    RemoteFollowupResult result =
        remoteFollowupResultRepository
            .findByTenantIdAndResultId(tenantId, resultId)
            .orElseThrow(() -> new NotFoundException("Remote followup result not found"));
    RemoteCommandCoordinator coordinator =
        result.getCoordinatorId() == null || result.getCoordinatorId().isBlank()
            ? null
            : remoteCommandCoordinatorRepository
                .findByTenantIdAndCoordinatorId(tenantId, result.getCoordinatorId())
                .filter(candidate -> matchesCoordinatorScope(result, candidate))
                .orElse(null);
    RemoteFollowup followup =
        result.getFollowupId() == null || result.getFollowupId().isBlank()
            ? null
            : remoteFollowupRepository
                .findByTenantIdAndFollowupId(tenantId, result.getFollowupId())
                .filter(candidate -> matchesCoordinatorScope(candidate, result))
                .orElse(null);
    return GetRemoteFollowupResultResponse.newBuilder()
        .setResult(
            toRemoteFollowupResultEntry(
                result,
                coordinator,
                followup,
                linkedTargetCommand(tenantId, followup),
                new HashMap<>()))
        .build();
  }

  ListRemoteCommandCoordinatorsResponse listRemoteCommandCoordinators(
      long tenantId, ListRemoteCommandCoordinatorsRequest request) {
    Long requestedPointerVersion =
        parseOptionalPositivePointerVersion(
            request.hasPointerVersion(), request.getPointerVersion());
    GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
        blankToEmpty(request.getWorldSlug()),
        blankToEmpty(request.getRealmSlug()),
        requestedPointerVersion,
        "routing filter must include world_slug, realm_slug, and pointer_version together");
    RoutingBundle filterRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            blankToEmpty(request.getWorldSlug()),
            blankToEmpty(request.getRealmSlug()),
            requestedPointerVersion);
    List<RemoteCommandCoordinator> coordinators =
        remoteCommandCoordinatorRepository.findForControlPlane(
            tenantId,
            parseOptionalGameInstanceId(request.getOriginGameInstanceId()),
            blankToEmpty(request.getOriginRegionId()),
            request.getOriginRegionEpoch(),
            parseOptionalGameInstanceId(request.getTargetGameInstanceId()),
            blankToEmpty(request.getTargetRegionId()),
            request.getTargetRegionEpoch(),
            blankToEmpty(request.getCurrentOriginRuntimeRegionId()),
            request.getCurrentOriginRuntimeRegionEpoch(),
            parseOptionalGameInstanceId(request.getCurrentOriginRuntimeGameInstanceId()),
            blankToEmpty(request.getCurrentTargetRuntimeRegionId()),
            request.getCurrentTargetRuntimeRegionEpoch(),
            parseOptionalGameInstanceId(request.getCurrentTargetRuntimeGameInstanceId()),
            blankToEmpty(request.getState()),
            blankToEmpty(request.getFollowupId()),
            blankToEmpty(request.getScriptId()),
            blankToEmpty(request.getPluginId()),
            blankToEmpty(request.getScriptPatchVersion()),
            blankToEmpty(request.getPluginVersionId()),
            normalizePlayableStateScope(request.getPlayableStateScope()),
            filterRoutingBundle == null ? "" : filterRoutingBundle.worldSlug(),
            filterRoutingBundle == null ? "" : filterRoutingBundle.realmSlug(),
            filterRoutingBundle == null ? null : filterRoutingBundle.pointerVersion(),
            blankToEmpty(request.getTargetEntityId()),
            blankToEmpty(request.getClaimTargetAggregate()),
            blankToEmpty(request.getEffectKey()),
            blankToEmpty(request.getPayloadKind()),
            blankToEmpty(request.getOriginSourceKind()),
            blankToEmpty(request.getFollowupOriginSourceState()),
            blankToEmpty(request.getAutomationWorkItemId()),
            blankToEmpty(request.getEventType()),
            blankToEmpty(request.getScriptEventId()),
            blankToEmpty(request.getLateResultPolicy()),
            blankToEmpty(request.getExecutionOutcome()),
            blankToEmpty(request.getGameplayResult()),
            blankToEmpty(request.getFollowupStatus()),
            blankToEmpty(request.getFollowupClaimedTickBatchId()),
            request.getFollowupRequiresSoloTick() ? Boolean.TRUE : null,
            blankToEmpty(request.getFollowupQueueSourceKind()),
            blankToEmpty(request.getFollowupQueueSourceState()),
            request.getFollowupQueueSourceOrdinal(),
            request.getFollowupQueueSourceDueTickId(),
            request.getFollowupQueueSourceDueAtMs(),
            blankToEmpty(request.getAutomationDispatchId()),
            blankToEmpty(request.getCommandId()),
            blankToEmpty(request.getTargetCommandId()),
            blankToEmpty(request.getTargetCommandExecutionOutcome()),
            blankToEmpty(request.getTargetCommandGameplayResult()),
            blankToEmpty(request.getLatestResultOutcome()),
            blankToEmpty(request.getLatestResultErrorCode()),
            PageRequest.of(0, boundedRemoteListLimit(request.getLimit())));
    Map<String, RemoteFollowup> followupsById = followupMap(tenantId, coordinators);
    Map<String, RemoteFollowupResult> latestResultsByCoordinatorId =
        latestResultMap(tenantId, coordinators);
    Map<String, GameplayCommand> targetCommandsByFollowupId =
        targetCommandMap(tenantId, followupsById);
    Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache =
        new HashMap<>();
    ListRemoteCommandCoordinatorsResponse.Builder response =
        ListRemoteCommandCoordinatorsResponse.newBuilder();
    coordinators.forEach(
        coordinator ->
            response.addCoordinators(
                toRemoteCoordinatorEntry(
                    coordinator,
                    followupsById.get(coordinator.getFollowupId()),
                    latestResultsByCoordinatorId.get(coordinator.getCoordinatorId()),
                    targetCommandsByFollowupId.get(coordinator.getFollowupId()),
                    runtimeBoundaryCache)));
    return response.build();
  }

  ScheduleRemoteFollowupResponse scheduleRemoteFollowup(
      long tenantId, ScheduleRemoteFollowupRequest request) {
    if (remoteFollowupRuntimeService == null) {
      throw new IllegalStateException("Remote followup runtime service is not configured");
    }
    Long requestedPointerVersion =
        parseOptionalPositivePointerVersion(
            request.hasPointerVersion(), request.getPointerVersion());
    GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
        normalizeBlank(request.getWorldSlug()),
        normalizeBlank(request.getRealmSlug()),
        requestedPointerVersion,
        "routing bundle must include world_slug, realm_slug, and pointer_version together");
    RoutingBundle requestRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            normalizeBlank(request.getWorldSlug()),
            normalizeBlank(request.getRealmSlug()),
            requestedPointerVersion);
    RemoteFollowupRuntimeService.ScheduleOutcome outcome =
        remoteFollowupRuntimeService.scheduleFollowup(
            new RemoteFollowupRuntimeService.ScheduleRequest(
                tenantId,
                request.getCommandId(),
                request.getCoordinatorId(),
                parseGameInstanceId(request.getOriginGameInstanceId()),
                request.getOriginRegionId(),
                request.getOriginRegionEpoch(),
                parseGameInstanceId(request.getTargetGameInstanceId()),
                request.getTargetRegionId(),
                request.getTargetRegionEpoch(),
                request.getTargetDueTickId(),
                request.getOriginDeadlineRegionEpoch(),
                request.getOriginDeadlineTickId(),
                request.getLateResultPolicy(),
                request.getFollowupId(),
                request.getEffectKey(),
                request.getTargetEntityId(),
                request.getPayloadJson(),
                normalizeBlank(request.getPayloadKind()),
                normalizeBlank(request.getRequestedCommand()),
                request.getRequiresSoloTick(),
                normalizePlayableStateScope(request.getPlayableStateScope()),
                requestRoutingBundle == null ? null : requestRoutingBundle.worldSlug(),
                requestRoutingBundle == null ? null : requestRoutingBundle.realmSlug(),
                requestRoutingBundle == null ? null : requestRoutingBundle.pointerVersion(),
                normalizeBlank(request.getScriptPatchVersion()),
                normalizeBlank(request.getPluginId()),
                normalizeBlank(request.getPluginVersionId()),
                normalizeBlank(request.getAutomationDispatchId()),
                normalizeBlank(request.getAutomationWorkItemId()),
                normalizeBlank(request.getScriptId()),
                normalizeBlank(request.getOriginSourceKind()),
                normalizeBlank(request.getOriginSourceState()),
                request.getOriginSourceOrdinal() > 0 ? request.getOriginSourceOrdinal() : null,
                request.getOriginSourceDueTickId() > 0 ? request.getOriginSourceDueTickId() : null,
                request.getOriginSourceDueAtMs() > 0 ? request.getOriginSourceDueAtMs() : null,
                normalizeBlank(request.getEventType()),
                normalizeBlank(request.getEventSchemaVersion()),
                normalizeBlank(request.getScriptEventId()),
                normalizeBlank(request.getTriggerMode()),
                normalizeBlank(request.getReadSnapshotToken()),
                normalizeBlank(request.getEventPayloadJson()),
                request.getScriptPinEpoch() > 0 ? request.getScriptPinEpoch() : null,
                normalizeBlank(request.getSourceScriptPatchVersion()),
                request.getSourceScriptPinEpoch() > 0 ? request.getSourceScriptPinEpoch() : null,
                normalizeBlank(request.getSourceScriptPinControlPlaneRequestId()),
                normalizeBlank(request.getTargetScriptPatchVersion()),
                request.getTargetScriptPinEpoch() > 0 ? request.getTargetScriptPinEpoch() : null,
                normalizeBlank(request.getTargetScriptPinControlPlaneRequestId())));
    return ScheduleRemoteFollowupResponse.newBuilder()
        .setCoordinatorId(outcome.coordinatorId())
        .setFollowupId(outcome.followupId())
        .setCoordinatorCreated(outcome.coordinatorCreated())
        .setFollowupCreated(outcome.followupCreated())
        .build();
  }

  ListRemoteFollowupsResponse listRemoteFollowups(
      long tenantId, ListRemoteFollowupsRequest request) {
    Long requestedPointerVersion =
        parseOptionalPositivePointerVersion(
            request.hasPointerVersion(), request.getPointerVersion());
    GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
        blankToEmpty(request.getWorldSlug()),
        blankToEmpty(request.getRealmSlug()),
        requestedPointerVersion,
        "routing filter must include world_slug, realm_slug, and pointer_version together");
    RoutingBundle filterRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            blankToEmpty(request.getWorldSlug()),
            blankToEmpty(request.getRealmSlug()),
            requestedPointerVersion);
    String targetRegionId = blankToEmpty(request.getTargetRegionId());
    Long targetGameInstanceId = parseOptionalGameInstanceId(request.getTargetGameInstanceId());
    requireTargetGameInstanceForRegion(targetRegionId, targetGameInstanceId);
    List<RemoteFollowup> followups =
        remoteFollowupRepository.findForControlPlane(
            tenantId,
            targetRegionId,
            blankToEmpty(request.getStatus()),
            parseOptionalGameInstanceId(request.getOriginGameInstanceId()),
            blankToEmpty(request.getOriginRegionId()),
            request.getOriginRegionEpoch(),
            targetGameInstanceId,
            request.getTargetRegionEpoch(),
            blankToEmpty(request.getCurrentOriginRuntimeRegionId()),
            request.getCurrentOriginRuntimeRegionEpoch(),
            parseOptionalGameInstanceId(request.getCurrentOriginRuntimeGameInstanceId()),
            blankToEmpty(request.getCurrentTargetRuntimeRegionId()),
            request.getCurrentTargetRuntimeRegionEpoch(),
            parseOptionalGameInstanceId(request.getCurrentTargetRuntimeGameInstanceId()),
            blankToEmpty(request.getFollowupId()),
            blankToEmpty(request.getScriptId()),
            blankToEmpty(request.getPluginId()),
            blankToEmpty(request.getScriptPatchVersion()),
            blankToEmpty(request.getPluginVersionId()),
            normalizePlayableStateScope(request.getPlayableStateScope()),
            filterRoutingBundle == null ? "" : filterRoutingBundle.worldSlug(),
            filterRoutingBundle == null ? "" : filterRoutingBundle.realmSlug(),
            filterRoutingBundle == null ? null : filterRoutingBundle.pointerVersion(),
            blankToEmpty(request.getPayloadKind()),
            blankToEmpty(request.getOriginSourceKind()),
            blankToEmpty(request.getOriginSourceState()),
            blankToEmpty(request.getAutomationWorkItemId()),
            blankToEmpty(request.getTargetEntityId()),
            blankToEmpty(request.getClaimTargetAggregate()),
            blankToEmpty(request.getEffectKey()),
            blankToEmpty(request.getFailureCode()),
            request.getRequiresSoloTick() ? Boolean.TRUE : null,
            blankToEmpty(request.getClaimedTickBatchId()),
            blankToEmpty(request.getQueueSourceKind()),
            blankToEmpty(request.getQueueSourceState()),
            request.getQueueSourceOrdinal(),
            request.getQueueSourceDueTickId(),
            request.getQueueSourceDueAtMs(),
            blankToEmpty(request.getRequestedCommand()),
            blankToEmpty(request.getEventType()),
            blankToEmpty(request.getScriptEventId()),
            request.getOriginDeadlineRegionEpoch(),
            request.getOriginDeadlineTickId(),
            blankToEmpty(request.getLateResultPolicy()),
            blankToEmpty(request.getAutomationDispatchId()),
            blankToEmpty(request.getCommandId()),
            blankToEmpty(request.getTargetCommandId()),
            blankToEmpty(request.getTargetCommandExecutionOutcome()),
            blankToEmpty(request.getTargetCommandGameplayResult()),
            PageRequest.of(0, boundedRemoteListLimit(request.getLimit())));
    Map<String, GameplayCommand> targetCommandsByFollowupId =
        targetCommandMap(tenantId, followupsById(followups));
    Map<String, RemoteCommandCoordinator> coordinatorsByFollowupId =
        coordinatorByFollowupMap(tenantId, followups);
    Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache =
        new HashMap<>();
    ListRemoteFollowupsResponse.Builder response = ListRemoteFollowupsResponse.newBuilder();
    followups.forEach(
        followup ->
            response.addFollowups(
                toRemoteFollowupEntry(
                    followup,
                    targetCommandsByFollowupId.get(followup.getFollowupId()),
                    coordinatorsByFollowupId.get(followup.getFollowupId()),
                    runtimeBoundaryCache)));
    return response.build();
  }

  ListRemoteFollowupResultsResponse listRemoteFollowupResults(
      long tenantId, ListRemoteFollowupResultsRequest request) {
    Long requestedPointerVersion =
        parseOptionalPositivePointerVersion(
            request.hasPointerVersion(), request.getPointerVersion());
    GameplayAdmissionPointerSnapshots.requireCompleteOrAbsentRoutingBundle(
        blankToEmpty(request.getWorldSlug()),
        blankToEmpty(request.getRealmSlug()),
        requestedPointerVersion,
        "routing filter must include world_slug, realm_slug, and pointer_version together");
    RoutingBundle filterRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            blankToEmpty(request.getWorldSlug()),
            blankToEmpty(request.getRealmSlug()),
            requestedPointerVersion);
    List<RemoteFollowupResult> results =
        remoteFollowupResultRepository.findForControlPlane(
            tenantId,
            blankToEmpty(request.getCoordinatorId()),
            blankToEmpty(request.getFollowupId()),
            parseOptionalGameInstanceId(request.getOriginGameInstanceId()),
            blankToEmpty(request.getOriginRegionId()),
            request.getOriginRegionEpoch(),
            parseOptionalGameInstanceId(request.getTargetGameInstanceId()),
            blankToEmpty(request.getTargetRegionId()),
            request.getTargetRegionEpoch(),
            blankToEmpty(request.getCurrentOriginRuntimeRegionId()),
            request.getCurrentOriginRuntimeRegionEpoch(),
            parseOptionalGameInstanceId(request.getCurrentOriginRuntimeGameInstanceId()),
            blankToEmpty(request.getCurrentTargetRuntimeRegionId()),
            request.getCurrentTargetRuntimeRegionEpoch(),
            parseOptionalGameInstanceId(request.getCurrentTargetRuntimeGameInstanceId()),
            blankToEmpty(request.getOutcome()),
            blankToEmpty(request.getScriptId()),
            blankToEmpty(request.getPluginId()),
            blankToEmpty(request.getScriptPatchVersion()),
            blankToEmpty(request.getPluginVersionId()),
            normalizePlayableStateScope(request.getPlayableStateScope()),
            filterRoutingBundle == null ? "" : filterRoutingBundle.worldSlug(),
            filterRoutingBundle == null ? "" : filterRoutingBundle.realmSlug(),
            filterRoutingBundle == null ? null : filterRoutingBundle.pointerVersion(),
            blankToEmpty(request.getResultErrorCode()),
            blankToEmpty(request.getAutomationWorkItemId()),
            blankToEmpty(request.getResultCommandId()),
            blankToEmpty(request.getResultCommandExecutionOutcome()),
            blankToEmpty(request.getResultCommandGameplayResult()),
            blankToEmpty(request.getTargetEntityId()),
            blankToEmpty(request.getClaimTargetAggregate()),
            blankToEmpty(request.getEffectKey()),
            blankToEmpty(request.getFailureCode()),
            blankToEmpty(request.getPayloadKind()),
            blankToEmpty(request.getOriginSourceKind()),
            blankToEmpty(request.getOriginSourceState()),
            blankToEmpty(request.getEventType()),
            blankToEmpty(request.getScriptEventId()),
            blankToEmpty(request.getResultMessage()),
            request.getRequiresSoloTick() ? Boolean.TRUE : null,
            blankToEmpty(request.getQueueSourceKind()),
            blankToEmpty(request.getQueueSourceState()),
            request.getQueueSourceOrdinal(),
            request.getQueueSourceDueTickId(),
            request.getQueueSourceDueAtMs(),
            blankToEmpty(request.getLateResultPolicy()),
            blankToEmpty(request.getClaimedTickBatchId()),
            blankToEmpty(request.getAutomationDispatchId()),
            blankToEmpty(request.getCommandId()),
            PageRequest.of(0, boundedRemoteListLimit(request.getLimit())));
    Map<String, RemoteCommandCoordinator> coordinatorsById = coordinatorMap(tenantId, results);
    Map<String, RemoteFollowup> followupsById = followupMapForResults(tenantId, results);
    Map<String, GameplayCommand> targetCommandsByFollowupId =
        targetCommandMap(tenantId, followupsById);
    Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache =
        new HashMap<>();
    ListRemoteFollowupResultsResponse.Builder response =
        ListRemoteFollowupResultsResponse.newBuilder();
    results.forEach(
        result -> {
          RemoteCommandCoordinator coordinator = coordinatorsById.get(result.getCoordinatorId());
          if (!matchesCoordinatorScope(result, coordinator)) {
            coordinator = null;
          }
          RemoteFollowup followup = followupsById.get(result.getFollowupId());
          if (!matchesCoordinatorScope(followup, result)) {
            followup = null;
          }
          GameplayCommand targetCommand =
              followup == null ? null : targetCommandsByFollowupId.get(followup.getFollowupId());
          response.addResults(
              toRemoteFollowupResultEntry(
                  result, coordinator, followup, targetCommand, runtimeBoundaryCache));
        });
    return response.build();
  }

  private long parseGameInstanceId(String gameInstanceId) {
    return ControlPlaneRequestParser.parsePositiveLong(gameInstanceId, "game_instance_id");
  }

  private Long parseOptionalGameInstanceId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      return null;
    }
    return parseGameInstanceId(gameInstanceId);
  }

  private static void requireTargetGameInstanceForRegion(
      String targetRegionId, Long targetGameInstanceId) {
    if (targetRegionId != null && !targetRegionId.isBlank() && targetGameInstanceId == null) {
      throw new IllegalArgumentException(
          "target_game_instance_id is required when target_region_id is set");
    }
  }

  private Long parseOptionalPositivePointerVersion(boolean hasPointerVersion, long pointerVersion) {
    if (!hasPointerVersion) {
      return null;
    }
    if (pointerVersion <= 0) {
      throw new IllegalArgumentException("pointerVersion must be positive");
    }
    return pointerVersion;
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }

  private RemoteCommandCoordinatorEntry toRemoteCoordinatorEntry(
      RemoteCommandCoordinator coordinator,
      RemoteFollowup followup,
      RemoteFollowupResult latestResult,
      GameplayCommand targetCommand,
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache) {
    RemoteCommandCoordinatorEntry.Builder builder =
        RemoteCommandCoordinatorEntry.newBuilder()
            .setCoordinatorId(coordinator.getCoordinatorId())
            .setTenantId(Long.toString(coordinator.getTenantId()))
            .setOriginGameInstanceId(Long.toString(coordinator.getOriginGameInstanceId()))
            .setOriginRegionEpoch(coordinator.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(coordinator.getTargetGameInstanceId()))
            .setTargetRegionEpoch(coordinator.getTargetRegionEpoch())
            .setTargetDueTickId(coordinator.getTargetDueTickId())
            .setOriginDeadlineRegionEpoch(coordinator.getOriginDeadlineRegionEpoch())
            .setOriginDeadlineTickId(coordinator.getOriginDeadlineTickId())
            .setUpdatedAtMs(
                coordinator.getUpdatedAt() == null
                    ? 0L
                    : coordinator.getUpdatedAt().toEpochMilli());
    if (coordinator.getCommandId() != null) {
      builder.setCommandId(coordinator.getCommandId());
    }
    if (coordinator.getFollowupId() != null) {
      builder.setFollowupId(coordinator.getFollowupId());
    }
    if (coordinator.getOriginRegionId() != null) {
      builder.setOriginRegionId(coordinator.getOriginRegionId());
    }
    if (coordinator.getTargetRegionId() != null) {
      builder.setTargetRegionId(coordinator.getTargetRegionId());
    }
    if (coordinator.getState() != null) {
      builder.setState(coordinator.getState());
    }
    if (coordinator.getLateResultPolicy() != null) {
      builder.setLateResultPolicy(coordinator.getLateResultPolicy());
    }
    if (coordinator.getExecutionOutcome() != null) {
      builder.setExecutionOutcome(coordinator.getExecutionOutcome());
    }
    if (coordinator.getGameplayResult() != null) {
      builder.setGameplayResult(coordinator.getGameplayResult());
    }
    if (followup != null) {
      if (followup.getTargetEntityId() != null) {
        builder.setTargetEntityId(followup.getTargetEntityId());
      }
      if (followup.getClaimTargetAggregate() != null) {
        builder.setFollowupClaimTargetAggregate(followup.getClaimTargetAggregate());
      }
      if (followup.getEffectKey() != null) {
        builder.setFollowupEffectKey(followup.getEffectKey());
      }
      if (followup.getStatus() != null) {
        builder.setFollowupStatus(followup.getStatus());
      }
      if (followup.getClaimedTickBatchId() != null) {
        builder.setFollowupClaimedTickBatchId(followup.getClaimedTickBatchId());
      }
      if (followup.getClaimOrdinal() != null) {
        builder.setFollowupClaimOrdinal(followup.getClaimOrdinal());
      }
      if (followup.getFailureCode() != null) {
        builder.setFollowupFailureCode(followup.getFailureCode());
      }
      if (followup.getFailureMessage() != null) {
        builder.setFollowupFailureMessage(followup.getFailureMessage());
      }
      applyTriggerScriptEventSummary(builder, followup);
      applyPayloadSummary(
          builder,
          followup.getPayloadJson(),
          followup.getPayloadKind(),
          followup.getRequestedCommand(),
          followup.isRequiresSoloTick());
      applyFollowupOriginSource(builder, followup);
      applyFollowupQueueSource(builder, followup);
      applyClaimTargetAggregate(builder, followup);
    }
    if (latestResult != null) {
      builder.setLatestResultOutcome(latestResult.getOutcome());
      if (latestResult.getResultPayloadJson() != null) {
        builder.setLatestResultPayloadJson(latestResult.getResultPayloadJson());
      }
      if (latestResult.getObservedAt() != null) {
        builder.setLatestResultObservedAtMs(latestResult.getObservedAt().toEpochMilli());
      }
      applyResultSummary(
          builder,
          latestResult.getResultPayloadJson(),
          latestResult.getResultCommandId(),
          latestResult.getResultErrorCode(),
          latestResult.getResultMessage());
    }
    applyDirectCommandProvenance(
        builder,
        coordinator.getTenantId(),
        coordinator.getScriptPatchVersion(),
        coordinator.getPluginId(),
        coordinator.getPluginVersionId());
    if (coordinator.getScriptPinEpoch() != null && coordinator.getScriptPinEpoch() > 0) {
      builder.setScriptPinEpoch(coordinator.getScriptPinEpoch());
    }
    applyDirectCommandIdentity(
        builder,
        coordinator.getAutomationDispatchId(),
        coordinator.getAutomationWorkItemId(),
        coordinator.getScriptId());
    applyRoutingBundle(
        builder,
        coordinator.getPlayableStateScope(),
        coordinator.getWorldSlug(),
        coordinator.getRealmSlug(),
        coordinator.getPointerVersion());
    applyTargetCommandStatus(builder, targetCommand);
    applyCurrentRuntimeScope(
        builder,
        coordinator.getTenantId(),
        coordinator.getOriginGameInstanceId(),
        true,
        runtimeBoundaryCache);
    applyCurrentRuntimeScope(
        builder,
        coordinator.getTenantId(),
        coordinator.getTargetGameInstanceId(),
        false,
        runtimeBoundaryCache);
    builder.setIsOriginRoutingBundleStale(
        isCurrentRoutingBundleStale(
            runtimeBoundaryCache,
            coordinator.getTenantId(),
            coordinator.getOriginGameInstanceId(),
            coordinator.getPlayableStateScope(),
            coordinator.getWorldSlug(),
            coordinator.getRealmSlug(),
            coordinator.getPointerVersion()));
    builder.setIsTargetRoutingBundleStale(
        isCurrentRoutingBundleStale(
            runtimeBoundaryCache,
            coordinator.getTenantId(),
            coordinator.getTargetGameInstanceId(),
            coordinator.getPlayableStateScope(),
            coordinator.getWorldSlug(),
            coordinator.getRealmSlug(),
            coordinator.getPointerVersion()));
    return builder.build();
  }

  private RemoteFollowupEntry toRemoteFollowupEntry(
      RemoteFollowup followup,
      GameplayCommand targetCommand,
      RemoteCommandCoordinator coordinator,
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache) {
    RemoteFollowupEntry.Builder builder =
        RemoteFollowupEntry.newBuilder()
            .setFollowupId(followup.getFollowupId())
            .setTenantId(Long.toString(followup.getTenantId()))
            .setOriginGameInstanceId(Long.toString(followup.getOriginGameInstanceId()))
            .setOriginRegionEpoch(followup.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(followup.getTargetGameInstanceId()))
            .setTargetRegionEpoch(followup.getTargetRegionEpoch())
            .setDueTickId(followup.getDueTickId())
            .setCreatedAtMs(
                followup.getCreatedAt() == null ? 0L : followup.getCreatedAt().toEpochMilli())
            .setUpdatedAtMs(
                followup.getUpdatedAt() == null ? 0L : followup.getUpdatedAt().toEpochMilli());
    if (followup.getOriginRegionId() != null) {
      builder.setOriginRegionId(followup.getOriginRegionId());
    }
    if (followup.getTargetRegionId() != null) {
      builder.setTargetRegionId(followup.getTargetRegionId());
    }
    if (followup.getEffectKey() != null) {
      builder.setEffectKey(followup.getEffectKey());
    }
    if (followup.getStatus() != null) {
      builder.setStatus(followup.getStatus());
    }
    if (followup.getTargetEntityId() != null) {
      builder.setTargetEntityId(followup.getTargetEntityId());
    }
    if (followup.getClaimTargetAggregate() != null) {
      builder.setClaimTargetAggregate(followup.getClaimTargetAggregate());
    }
    if (followup.getClaimedTickBatchId() != null) {
      builder.setClaimedTickBatchId(followup.getClaimedTickBatchId());
    }
    if (followup.getClaimOrdinal() != null) {
      builder.setClaimOrdinal(followup.getClaimOrdinal());
    }
    if (followup.getPayloadJson() != null) {
      builder.setPayloadJson(followup.getPayloadJson());
    }
    if (followup.getFailureCode() != null) {
      builder.setFailureCode(followup.getFailureCode());
    }
    if (followup.getFailureMessage() != null) {
      builder.setFailureMessage(followup.getFailureMessage());
    }
    applyTriggerScriptEventSummary(builder, followup);
    applyDirectCommandProvenance(
        builder,
        followup.getTenantId(),
        followup.getScriptPatchVersion(),
        followup.getPluginId(),
        followup.getPluginVersionId());
    if (followup.getScriptPinEpoch() != null && followup.getScriptPinEpoch() > 0) {
      builder.setScriptPinEpoch(followup.getScriptPinEpoch());
    }
    applyDirectCommandIdentity(
        builder,
        followup.getCommandId(),
        followup.getAutomationDispatchId(),
        followup.getAutomationWorkItemId(),
        followup.getScriptId());
    applyPayloadSummary(
        builder,
        followup.getPayloadJson(),
        followup.getPayloadKind(),
        followup.getRequestedCommand(),
        followup.isRequiresSoloTick());
    applyOriginSource(
        builder,
        followup.getOriginSourceKind(),
        followup.getOriginSourceState(),
        followup.getOriginSourceOrdinal(),
        followup.getOriginSourceDueTickId(),
        followup.getOriginSourceDueAtMs());
    applyQueueSource(builder, followup);
    applyClaimTargetAggregate(builder, followup);
    applyRoutingBundle(
        builder,
        followup.getPlayableStateScope(),
        followup.getWorldSlug(),
        followup.getRealmSlug(),
        followup.getPointerVersion());
    applyCoordinatorDeadlinePolicy(builder, coordinator);
    applyTargetCommandStatus(builder, targetCommand);
    applyCurrentRuntimeScope(
        builder,
        followup.getTenantId(),
        followup.getOriginGameInstanceId(),
        true,
        runtimeBoundaryCache);
    applyCurrentRuntimeScope(
        builder,
        followup.getTenantId(),
        followup.getTargetGameInstanceId(),
        false,
        runtimeBoundaryCache);
    builder.setIsOriginRoutingBundleStale(
        isCurrentRoutingBundleStale(
            runtimeBoundaryCache,
            followup.getTenantId(),
            followup.getOriginGameInstanceId(),
            followup.getPlayableStateScope(),
            followup.getWorldSlug(),
            followup.getRealmSlug(),
            followup.getPointerVersion()));
    builder.setIsTargetRoutingBundleStale(
        isCurrentRoutingBundleStale(
            runtimeBoundaryCache,
            followup.getTenantId(),
            followup.getTargetGameInstanceId(),
            followup.getPlayableStateScope(),
            followup.getWorldSlug(),
            followup.getRealmSlug(),
            followup.getPointerVersion()));
    return builder.build();
  }

  private RemoteFollowupResultEntry toRemoteFollowupResultEntry(
      RemoteFollowupResult result,
      RemoteCommandCoordinator coordinator,
      RemoteFollowup followup,
      GameplayCommand targetCommand,
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache) {
    RemoteFollowupResultEntry.Builder builder =
        RemoteFollowupResultEntry.newBuilder()
            .setResultId(result.getResultId())
            .setTenantId(Long.toString(result.getTenantId()))
            .setOriginGameInstanceId(Long.toString(result.getOriginGameInstanceId()))
            .setOriginRegionEpoch(result.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(result.getTargetGameInstanceId()))
            .setTargetRegionEpoch(result.getTargetRegionEpoch())
            .setObservedAtMs(
                result.getObservedAt() == null ? 0L : result.getObservedAt().toEpochMilli());
    if (result.getCoordinatorId() != null) {
      builder.setCoordinatorId(result.getCoordinatorId());
    }
    if (result.getFollowupId() != null) {
      builder.setFollowupId(result.getFollowupId());
    }
    if (result.getOriginRegionId() != null) {
      builder.setOriginRegionId(result.getOriginRegionId());
    }
    if (result.getTargetRegionId() != null) {
      builder.setTargetRegionId(result.getTargetRegionId());
    }
    if (result.getOutcome() != null) {
      builder.setOutcome(result.getOutcome());
    }
    if (result.getResultPayloadJson() != null) {
      builder.setResultPayloadJson(result.getResultPayloadJson());
    }
    applyDirectCommandProvenance(
        builder,
        result.getTenantId(),
        result.getScriptPatchVersion(),
        result.getPluginId(),
        result.getPluginVersionId());
    applyDirectCommandIdentity(
        builder,
        result.getCommandId(),
        result.getAutomationDispatchId(),
        result.getAutomationWorkItemId(),
        result.getScriptId());
    String resultCommandId =
        applyResultSummary(
            builder,
            result.getResultPayloadJson(),
            result.getResultCommandId(),
            result.getResultErrorCode(),
            result.getResultMessage());
    if (targetCommand == null && resultCommandId != null) {
      targetCommand =
          gameplayCommandRepository
              .findByCommandId(resultCommandId)
              .filter(
                  candidate ->
                      Objects.equals(candidate.getTenantId(), result.getTenantId())
                          && Objects.equals(
                              candidate.getGameInstanceId(), result.getTargetGameInstanceId())
                          && Objects.equals(candidate.getRegionId(), result.getTargetRegionId())
                          && Objects.equals(
                              candidate.getRegionEpoch(), result.getTargetRegionEpoch())
                          && Objects.equals(
                              candidate.getRemoteFollowupId(), result.getFollowupId()))
              .orElse(null);
    }
    if (targetCommand != null && targetCommand.getCommandId() != null) {
      builder.setResultCommandId(targetCommand.getCommandId());
    }
    applyTargetCommandStatus(builder, targetCommand);
    applyRoutingBundle(
        builder,
        result.getPlayableStateScope(),
        result.getWorldSlug(),
        result.getRealmSlug(),
        result.getPointerVersion());
    applyFollowupIdentity(builder, followup);
    applyPayloadSummary(builder, followup);
    applyOriginSource(builder, followup);
    applyQueueSource(builder, followup);
    applyClaimTargetAggregate(builder, followup);
    applyTriggerScriptEventSummary(builder, followup);
    applyCoordinatorDeadlinePolicy(builder, coordinator);
    applyCurrentRuntimeScope(
        builder,
        result.getTenantId(),
        result.getOriginGameInstanceId(),
        true,
        runtimeBoundaryCache);
    applyCurrentRuntimeScope(
        builder,
        result.getTenantId(),
        result.getTargetGameInstanceId(),
        false,
        runtimeBoundaryCache);
    builder.setIsOriginRoutingBundleStale(
        isCurrentRoutingBundleStale(
            runtimeBoundaryCache,
            result.getTenantId(),
            result.getOriginGameInstanceId(),
            result.getPlayableStateScope(),
            result.getWorldSlug(),
            result.getRealmSlug(),
            result.getPointerVersion()));
    builder.setIsTargetRoutingBundleStale(
        isCurrentRoutingBundleStale(
            runtimeBoundaryCache,
            result.getTenantId(),
            result.getTargetGameInstanceId(),
            result.getPlayableStateScope(),
            result.getWorldSlug(),
            result.getRealmSlug(),
            result.getPointerVersion()));
    return builder.build();
  }

  private void applyCurrentRuntimeScope(
      RemoteCommandCoordinatorEntry.Builder builder,
      long tenantId,
      long gameInstanceId,
      boolean originScope,
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache) {
    cachedCurrentRuntimeBoundary(runtimeBoundaryCache, tenantId, gameInstanceId)
        .ifPresent(
            currentBoundary -> {
              CurrentRuntimeScopeFieldEmitter.applyCurrentRuntimeScopeFields(
                  currentBoundary.gameInstanceId(),
                  currentBoundary.regionId(),
                  currentBoundary.regionEpoch(),
                  currentBoundary.playableStateScope(),
                  currentBoundary.worldSlug(),
                  currentBoundary.realmSlug(),
                  currentBoundary.pointerVersion(),
                  originScope,
                  CurrentRuntimeScopeFieldEmitter.writerFor(builder));
            });
  }

  private void applyCurrentRuntimeScope(
      RemoteFollowupEntry.Builder builder,
      long tenantId,
      long gameInstanceId,
      boolean originScope,
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache) {
    cachedCurrentRuntimeBoundary(runtimeBoundaryCache, tenantId, gameInstanceId)
        .ifPresent(
            currentBoundary -> {
              CurrentRuntimeScopeFieldEmitter.applyCurrentRuntimeScopeFields(
                  currentBoundary.gameInstanceId(),
                  currentBoundary.regionId(),
                  currentBoundary.regionEpoch(),
                  currentBoundary.playableStateScope(),
                  currentBoundary.worldSlug(),
                  currentBoundary.realmSlug(),
                  currentBoundary.pointerVersion(),
                  originScope,
                  CurrentRuntimeScopeFieldEmitter.writerFor(builder));
            });
  }

  private void applyCurrentRuntimeScope(
      RemoteFollowupResultEntry.Builder builder,
      long tenantId,
      long gameInstanceId,
      boolean originScope,
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache) {
    cachedCurrentRuntimeBoundary(runtimeBoundaryCache, tenantId, gameInstanceId)
        .ifPresent(
            currentBoundary -> {
              CurrentRuntimeScopeFieldEmitter.applyCurrentRuntimeScopeFields(
                  currentBoundary.gameInstanceId(),
                  currentBoundary.regionId(),
                  currentBoundary.regionEpoch(),
                  currentBoundary.playableStateScope(),
                  currentBoundary.worldSlug(),
                  currentBoundary.realmSlug(),
                  currentBoundary.pointerVersion(),
                  originScope,
                  CurrentRuntimeScopeFieldEmitter.writerFor(builder));
            });
  }

  private Optional<CurrentRuntimeBoundary> currentRuntimeBoundary(
      long tenantId, long gameInstanceId) {
    return currentRuntimeOwnership(tenantId, gameInstanceId)
        .map(
            ownership -> {
              GameInstance instance = getInstanceOrThrow(ownership.getGameInstanceId());
              CurrentRoutingAuthority authority = resolveCurrentRoutingAuthority(instance);
              GameplayRoutingBundle routingBundle = authority.routingBundle();
              RoutingBundle normalizedRoutingBundle =
                  GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
                      routingBundle.worldSlug(),
                      routingBundle.realmSlug(),
                      routingBundle.pointerVersion());
              boolean singularRoutingAuthority =
                  authority.singularRoutingAuthority() && normalizedRoutingBundle != null;
              return new CurrentRuntimeBoundary(
                  ownership.getGameInstanceId(),
                  ownership.getRegionId(),
                  ownership.getRegionEpoch(),
                  routingBundle.playableStateScope(),
                  singularRoutingAuthority,
                  normalizedRoutingBundle == null ? "" : normalizedRoutingBundle.worldSlug(),
                  normalizedRoutingBundle == null ? "" : normalizedRoutingBundle.realmSlug(),
                  normalizedRoutingBundle == null
                      ? null
                      : normalizedRoutingBundle.pointerVersion());
            });
  }

  private boolean isCurrentRoutingBundleStale(
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache,
      long tenantId,
      long gameInstanceId,
      String persistedPlayableStateScope,
      String persistedWorldSlug,
      String persistedRealmSlug,
      Long persistedPointerVersion) {
    return cachedCurrentRuntimeBoundary(runtimeBoundaryCache, tenantId, gameInstanceId)
        .map(
            currentBoundary -> {
              if (!currentBoundary.singularRoutingAuthority()) {
                return hasPersistedRoutingBundleClaim(
                    persistedPlayableStateScope,
                    persistedWorldSlug,
                    persistedRealmSlug,
                    persistedPointerVersion);
              }
              return isRoutingBundleStale(
                  persistedPlayableStateScope,
                  persistedWorldSlug,
                  persistedRealmSlug,
                  persistedPointerVersion,
                  currentBoundary);
            })
        .orElseGet(
            () ->
                hasPersistedRoutingBundleClaim(
                    persistedPlayableStateScope,
                    persistedWorldSlug,
                    persistedRealmSlug,
                    persistedPointerVersion));
  }

  private Optional<CurrentRuntimeBoundary> cachedCurrentRuntimeBoundary(
      Map<RuntimeBoundaryKey, Optional<CurrentRuntimeBoundary>> runtimeBoundaryCache,
      long tenantId,
      long gameInstanceId) {
    return runtimeBoundaryCache.computeIfAbsent(
        new RuntimeBoundaryKey(tenantId, gameInstanceId),
        key -> currentRuntimeBoundary(key.tenantId(), key.gameInstanceId()));
  }

  private static boolean hasPersistedRoutingBundleClaim(
      String persistedPlayableStateScope,
      String persistedWorldSlug,
      String persistedRealmSlug,
      Long persistedPointerVersion) {
    if (persistedPlayableStateScope != null && !persistedPlayableStateScope.isBlank()) {
      return true;
    }
    return GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            persistedWorldSlug, persistedRealmSlug, persistedPointerVersion)
        != null;
  }

  private static boolean isRoutingBundleStale(
      String persistedPlayableStateScope,
      String persistedWorldSlug,
      String persistedRealmSlug,
      Long persistedPointerVersion,
      CurrentRuntimeBoundary currentBoundary) {
    RoutingBundle persistedRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            persistedWorldSlug, persistedRealmSlug, persistedPointerVersion);
    RoutingBundle currentRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            currentBoundary.worldSlug(),
            currentBoundary.realmSlug(),
            currentBoundary.pointerVersion());
    String normalizedPersistedPlayableStateScope =
        persistedPlayableStateScope == null || persistedPlayableStateScope.isBlank()
            ? ""
            : persistedPlayableStateScope;
    String currentPlayableStateScope =
        normalizePlayableStateScope(currentBoundary.playableStateScope());
    String normalizedPersistedWorldSlug =
        persistedRoutingBundle == null ? "" : persistedRoutingBundle.worldSlug();
    String normalizedPersistedRealmSlug =
        persistedRoutingBundle == null ? "" : persistedRoutingBundle.realmSlug();
    long normalizedPersistedPointerVersion =
        persistedRoutingBundle == null ? 0L : persistedRoutingBundle.pointerVersion();
    String currentWorldSlug = currentRoutingBundle == null ? "" : currentRoutingBundle.worldSlug();
    String currentRealmSlug = currentRoutingBundle == null ? "" : currentRoutingBundle.realmSlug();
    long currentPointerVersion =
        currentRoutingBundle == null ? 0L : currentRoutingBundle.pointerVersion();
    return !normalizedPersistedPlayableStateScope.equals(currentPlayableStateScope)
        || !normalizedPersistedWorldSlug.equals(currentWorldSlug)
        || !normalizedPersistedRealmSlug.equals(currentRealmSlug)
        || normalizedPersistedPointerVersion != currentPointerVersion;
  }

  private void applyDirectCommandProvenance(
      RemoteCommandCoordinatorEntry.Builder builder,
      long tenantId,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId) {
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      builder.setScriptPatchVersion(scriptPatchVersion);
      builder.setPublication(scriptPatchPublicationLink(tenantId, scriptPatchVersion));
    }
    if (pluginId != null) {
      builder.setPluginId(pluginId);
    }
    if (pluginVersionId != null) {
      builder.setPluginVersionId(pluginVersionId);
    }
    if (pluginId != null
        && !pluginId.isBlank()
        && pluginVersionId != null
        && !pluginVersionId.isBlank()) {
      builder.setPluginPublication(pluginPublicationLink(tenantId, pluginId, pluginVersionId));
    }
  }

  private static void applyDirectCommandIdentity(
      RemoteCommandCoordinatorEntry.Builder builder,
      String automationDispatchId,
      String automationWorkItemId,
      String scriptId) {
    if (automationDispatchId != null) {
      builder.setAutomationDispatchId(automationDispatchId);
    }
    if (automationWorkItemId != null) {
      builder.setAutomationWorkItemId(automationWorkItemId);
    }
    if (scriptId != null) {
      builder.setScriptId(scriptId);
    }
  }

  private static void applyFollowupOriginSource(
      RemoteCommandCoordinatorEntry.Builder builder, RemoteFollowup followup) {
    if (followup.getOriginSourceKind() != null) {
      builder.setFollowupOriginSourceKind(followup.getOriginSourceKind());
    }
    if (followup.getOriginSourceState() != null) {
      builder.setFollowupOriginSourceState(followup.getOriginSourceState());
    }
    if (followup.getOriginSourceOrdinal() != null) {
      builder.setFollowupOriginSourceOrdinal(followup.getOriginSourceOrdinal());
    }
    if (followup.getOriginSourceDueTickId() != null) {
      builder.setFollowupOriginSourceDueTickId(followup.getOriginSourceDueTickId());
    }
    if (followup.getOriginSourceDueAtMs() != null) {
      builder.setFollowupOriginSourceDueAtMs(followup.getOriginSourceDueAtMs());
    }
  }

  private static void applyFollowupQueueSource(
      RemoteCommandCoordinatorEntry.Builder builder, RemoteFollowup followup) {
    if (followup.getQueueSourceKind() != null) {
      builder.setFollowupQueueSourceKind(followup.getQueueSourceKind());
    }
    if (followup.getQueueSourceState() != null) {
      builder.setFollowupQueueSourceState(followup.getQueueSourceState());
    }
    if (followup.getQueueSourceOrdinal() != null) {
      builder.setFollowupQueueSourceOrdinal(followup.getQueueSourceOrdinal());
    }
    if (followup.getQueueSourceDueTickId() != null) {
      builder.setFollowupQueueSourceDueTickId(followup.getQueueSourceDueTickId());
    }
    if (followup.getQueueSourceDueAtMs() != null) {
      builder.setFollowupQueueSourceDueAtMs(followup.getQueueSourceDueAtMs());
    }
  }

  private static void applyOriginSource(
      RemoteFollowupEntry.Builder builder,
      String originSourceKind,
      String originSourceState,
      Long originSourceOrdinal,
      Long originSourceDueTickId,
      Long originSourceDueAtMs) {
    if (originSourceKind != null) {
      builder.setOriginSourceKind(originSourceKind);
    }
    if (originSourceState != null) {
      builder.setOriginSourceState(originSourceState);
    }
    if (originSourceOrdinal != null) {
      builder.setOriginSourceOrdinal(originSourceOrdinal);
    }
    if (originSourceDueTickId != null) {
      builder.setOriginSourceDueTickId(originSourceDueTickId);
    }
    if (originSourceDueAtMs != null) {
      builder.setOriginSourceDueAtMs(originSourceDueAtMs);
    }
  }

  private static void applyQueueSource(
      RemoteFollowupEntry.Builder builder, RemoteFollowup followup) {
    if (followup == null) {
      return;
    }
    if (followup.getQueueSourceKind() != null) {
      builder.setQueueSourceKind(followup.getQueueSourceKind());
    }
    if (followup.getQueueSourceState() != null) {
      builder.setQueueSourceState(followup.getQueueSourceState());
    }
    if (followup.getQueueSourceOrdinal() != null) {
      builder.setQueueSourceOrdinal(followup.getQueueSourceOrdinal());
    }
    if (followup.getQueueSourceDueTickId() != null) {
      builder.setQueueSourceDueTickId(followup.getQueueSourceDueTickId());
    }
    if (followup.getQueueSourceDueAtMs() != null) {
      builder.setQueueSourceDueAtMs(followup.getQueueSourceDueAtMs());
    }
  }

  private static void applyTriggerScriptEventSummary(
      RemoteCommandCoordinatorEntry.Builder builder, RemoteFollowup followup) {
    if (followup.getEventType() != null) {
      builder.setFollowupEventType(followup.getEventType());
    }
    if (followup.getEventSchemaVersion() != null) {
      builder.setFollowupEventSchemaVersion(followup.getEventSchemaVersion());
    }
    if (followup.getScriptEventId() != null) {
      builder.setFollowupScriptEventId(followup.getScriptEventId());
    }
    if (followup.getTriggerMode() != null) {
      builder.setFollowupTriggerMode(followup.getTriggerMode());
    }
    if (followup.getReadSnapshotToken() != null) {
      builder.setFollowupReadSnapshotToken(followup.getReadSnapshotToken());
    }
    if (followup.getEventPayloadJson() != null) {
      builder.setFollowupEventPayloadJson(followup.getEventPayloadJson());
    }
  }

  private static void applyTriggerScriptEventSummary(
      RemoteFollowupEntry.Builder builder, RemoteFollowup followup) {
    if (followup.getEventType() != null) {
      builder.setEventType(followup.getEventType());
    }
    if (followup.getEventSchemaVersion() != null) {
      builder.setEventSchemaVersion(followup.getEventSchemaVersion());
    }
    if (followup.getScriptEventId() != null) {
      builder.setScriptEventId(followup.getScriptEventId());
    }
    if (followup.getTriggerMode() != null) {
      builder.setTriggerMode(followup.getTriggerMode());
    }
    if (followup.getReadSnapshotToken() != null) {
      builder.setReadSnapshotToken(followup.getReadSnapshotToken());
    }
    if (followup.getEventPayloadJson() != null) {
      builder.setEventPayloadJson(followup.getEventPayloadJson());
    }
  }

  private static void applyRoutingBundle(
      RemoteCommandCoordinatorEntry.Builder builder,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {
    if (playableStateScope != null && !playableStateScope.isBlank()) {
      builder.setPlayableStateScope(toPlayableStateScopeStatus(playableStateScope));
    }
    RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            worldSlug, realmSlug, pointerVersion);
    if (routingBundle != null) {
      builder.setWorldSlug(routingBundle.worldSlug());
      builder.setRealmSlug(routingBundle.realmSlug());
      builder.setPointerVersion(routingBundle.pointerVersion());
    }
  }

  private static void applyTargetCommandStatus(
      RemoteCommandCoordinatorEntry.Builder builder, GameplayCommand targetCommand) {
    if (targetCommand == null) {
      return;
    }
    builder.setTargetCommandId(targetCommand.getCommandId());
    builder.setLatestResultCommandId(targetCommand.getCommandId());
    if (targetCommand.getExecutionOutcome() != null) {
      builder.setTargetCommandExecutionOutcome(targetCommand.getExecutionOutcome());
    }
    if (targetCommand.getGameplayResult() != null) {
      builder.setTargetCommandGameplayResult(targetCommand.getGameplayResult());
    }
  }

  private void applyDirectCommandProvenance(
      RemoteFollowupEntry.Builder builder,
      long tenantId,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId) {
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      builder.setScriptPatchVersion(scriptPatchVersion);
      builder.setPublication(scriptPatchPublicationLink(tenantId, scriptPatchVersion));
    }
    if (pluginId != null) {
      builder.setPluginId(pluginId);
    }
    if (pluginVersionId != null) {
      builder.setPluginVersionId(pluginVersionId);
    }
    if (pluginId != null
        && !pluginId.isBlank()
        && pluginVersionId != null
        && !pluginVersionId.isBlank()) {
      builder.setPluginPublication(pluginPublicationLink(tenantId, pluginId, pluginVersionId));
    }
  }

  private static void applyDirectCommandIdentity(
      RemoteFollowupEntry.Builder builder,
      String commandId,
      String automationDispatchId,
      String automationWorkItemId,
      String scriptId) {
    if (commandId != null) {
      builder.setCommandId(commandId);
    }
    if (automationDispatchId != null) {
      builder.setAutomationDispatchId(automationDispatchId);
    }
    if (automationWorkItemId != null) {
      builder.setAutomationWorkItemId(automationWorkItemId);
    }
    if (scriptId != null) {
      builder.setScriptId(scriptId);
    }
  }

  private static void applyClaimTargetAggregate(
      RemoteFollowupEntry.Builder builder, RemoteFollowup followup) {
    if (followup != null && followup.getClaimTargetAggregate() != null) {
      builder.setClaimTargetAggregate(followup.getClaimTargetAggregate());
    }
  }

  private static void applyClaimTargetAggregate(
      RemoteFollowupResultEntry.Builder builder, RemoteFollowup followup) {
    if (followup != null && followup.getClaimTargetAggregate() != null) {
      builder.setClaimTargetAggregate(followup.getClaimTargetAggregate());
    }
  }

  private static void applyClaimTargetAggregate(
      RemoteCommandCoordinatorEntry.Builder builder, RemoteFollowup followup) {
    if (followup != null && followup.getClaimTargetAggregate() != null) {
      builder.setFollowupClaimTargetAggregate(followup.getClaimTargetAggregate());
    }
  }

  private static void applyRoutingBundle(
      RemoteFollowupEntry.Builder builder,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {
    if (playableStateScope != null && !playableStateScope.isBlank()) {
      builder.setPlayableStateScope(toPlayableStateScopeStatus(playableStateScope));
    }
    RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            worldSlug, realmSlug, pointerVersion);
    if (routingBundle != null) {
      builder.setWorldSlug(routingBundle.worldSlug());
      builder.setRealmSlug(routingBundle.realmSlug());
      builder.setPointerVersion(routingBundle.pointerVersion());
    }
  }

  private static void applyTargetCommandStatus(
      RemoteFollowupEntry.Builder builder, GameplayCommand targetCommand) {
    if (targetCommand == null) {
      return;
    }
    builder.setTargetCommandId(targetCommand.getCommandId());
    if (targetCommand.getExecutionOutcome() != null) {
      builder.setTargetCommandExecutionOutcome(targetCommand.getExecutionOutcome());
    }
    if (targetCommand.getGameplayResult() != null) {
      builder.setTargetCommandGameplayResult(targetCommand.getGameplayResult());
    }
  }

  private static void applyCoordinatorDeadlinePolicy(
      RemoteFollowupEntry.Builder builder, RemoteCommandCoordinator coordinator) {
    if (coordinator == null) {
      return;
    }
    builder.setOriginDeadlineRegionEpoch(coordinator.getOriginDeadlineRegionEpoch());
    builder.setOriginDeadlineTickId(coordinator.getOriginDeadlineTickId());
    if (coordinator.getLateResultPolicy() != null) {
      builder.setLateResultPolicy(coordinator.getLateResultPolicy());
    }
  }

  private void applyDirectCommandProvenance(
      RemoteFollowupResultEntry.Builder builder,
      long tenantId,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId) {
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      builder.setScriptPatchVersion(scriptPatchVersion);
      builder.setPublication(scriptPatchPublicationLink(tenantId, scriptPatchVersion));
    }
    if (pluginId != null) {
      builder.setPluginId(pluginId);
    }
    if (pluginVersionId != null) {
      builder.setPluginVersionId(pluginVersionId);
    }
    if (pluginId != null
        && !pluginId.isBlank()
        && pluginVersionId != null
        && !pluginVersionId.isBlank()) {
      builder.setPluginPublication(pluginPublicationLink(tenantId, pluginId, pluginVersionId));
    }
  }

  private static void applyDirectCommandIdentity(
      RemoteFollowupResultEntry.Builder builder,
      String commandId,
      String automationDispatchId,
      String automationWorkItemId,
      String scriptId) {
    if (commandId != null) {
      builder.setCommandId(commandId);
    }
    if (automationDispatchId != null) {
      builder.setAutomationDispatchId(automationDispatchId);
    }
    if (automationWorkItemId != null) {
      builder.setAutomationWorkItemId(automationWorkItemId);
    }
    if (scriptId != null) {
      builder.setScriptId(scriptId);
    }
  }

  private static void applyRoutingBundle(
      RemoteFollowupResultEntry.Builder builder,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {
    if (playableStateScope != null && !playableStateScope.isBlank()) {
      builder.setPlayableStateScope(toPlayableStateScopeStatus(playableStateScope));
    }
    RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            worldSlug, realmSlug, pointerVersion);
    if (routingBundle != null) {
      builder.setWorldSlug(routingBundle.worldSlug());
      builder.setRealmSlug(routingBundle.realmSlug());
      builder.setPointerVersion(routingBundle.pointerVersion());
    }
  }

  private static void applyTargetCommandStatus(
      RemoteFollowupResultEntry.Builder builder, GameplayCommand targetCommand) {
    if (targetCommand == null) {
      return;
    }
    if (targetCommand.getExecutionOutcome() != null) {
      builder.setResultCommandExecutionOutcome(targetCommand.getExecutionOutcome());
    }
    if (targetCommand.getGameplayResult() != null) {
      builder.setResultCommandGameplayResult(targetCommand.getGameplayResult());
    }
  }

  private static void applyCoordinatorDeadlinePolicy(
      RemoteFollowupResultEntry.Builder builder, RemoteCommandCoordinator coordinator) {
    if (coordinator == null) {
      return;
    }
    builder.setOriginDeadlineRegionEpoch(coordinator.getOriginDeadlineRegionEpoch());
    builder.setOriginDeadlineTickId(coordinator.getOriginDeadlineTickId());
    if (coordinator.getLateResultPolicy() != null) {
      builder.setLateResultPolicy(coordinator.getLateResultPolicy());
    }
  }

  private static void applyFollowupIdentity(
      RemoteFollowupResultEntry.Builder builder, RemoteFollowup followup) {
    if (followup == null) {
      return;
    }
    if (followup.getTargetEntityId() != null) {
      builder.setTargetEntityId(followup.getTargetEntityId());
    }
    if (followup.getClaimTargetAggregate() != null) {
      builder.setClaimTargetAggregate(followup.getClaimTargetAggregate());
    }
    if (followup.getEffectKey() != null) {
      builder.setEffectKey(followup.getEffectKey());
    }
    if (followup.getFailureCode() != null) {
      builder.setFailureCode(followup.getFailureCode());
    }
    if (followup.getFailureMessage() != null) {
      builder.setFailureMessage(followup.getFailureMessage());
    }
  }

  private static void applyPayloadSummary(
      RemoteFollowupResultEntry.Builder builder, RemoteFollowup followup) {
    if (followup == null) {
      return;
    }
    PayloadSummary summary =
        payloadSummary(
            followup.getPayloadJson(),
            followup.getPayloadKind(),
            followup.getRequestedCommand(),
            followup.isRequiresSoloTick());
    if (summary.kind() != null) {
      builder.setPayloadKind(summary.kind());
    }
    if (summary.requiresSoloTick()) {
      builder.setRequiresSoloTick(true);
    }
  }

  private static void applyOriginSource(
      RemoteFollowupResultEntry.Builder builder, RemoteFollowup followup) {
    if (followup == null) {
      return;
    }
    if (followup.getOriginSourceKind() != null) {
      builder.setOriginSourceKind(followup.getOriginSourceKind());
    }
    if (followup.getOriginSourceState() != null) {
      builder.setOriginSourceState(followup.getOriginSourceState());
    }
    if (followup.getOriginSourceOrdinal() != null) {
      builder.setOriginSourceOrdinal(followup.getOriginSourceOrdinal());
    }
    if (followup.getOriginSourceDueTickId() != null) {
      builder.setOriginSourceDueTickId(followup.getOriginSourceDueTickId());
    }
    if (followup.getOriginSourceDueAtMs() != null) {
      builder.setOriginSourceDueAtMs(followup.getOriginSourceDueAtMs());
    }
  }

  private static void applyQueueSource(
      RemoteFollowupResultEntry.Builder builder, RemoteFollowup followup) {
    if (followup == null) {
      return;
    }
    if (followup.getQueueSourceKind() != null) {
      builder.setQueueSourceKind(followup.getQueueSourceKind());
    }
    if (followup.getQueueSourceState() != null) {
      builder.setQueueSourceState(followup.getQueueSourceState());
    }
    if (followup.getQueueSourceOrdinal() != null) {
      builder.setQueueSourceOrdinal(followup.getQueueSourceOrdinal());
    }
    if (followup.getQueueSourceDueTickId() != null) {
      builder.setQueueSourceDueTickId(followup.getQueueSourceDueTickId());
    }
    if (followup.getQueueSourceDueAtMs() != null) {
      builder.setQueueSourceDueAtMs(followup.getQueueSourceDueAtMs());
    }
  }

  private static void applyTriggerScriptEventSummary(
      RemoteFollowupResultEntry.Builder builder, RemoteFollowup followup) {
    if (followup == null) {
      return;
    }
    if (followup.getEventType() != null) {
      builder.setEventType(followup.getEventType());
    }
    if (followup.getEventSchemaVersion() != null) {
      builder.setEventSchemaVersion(followup.getEventSchemaVersion());
    }
    if (followup.getScriptEventId() != null) {
      builder.setScriptEventId(followup.getScriptEventId());
    }
    if (followup.getTriggerMode() != null) {
      builder.setTriggerMode(followup.getTriggerMode());
    }
    if (followup.getReadSnapshotToken() != null) {
      builder.setReadSnapshotToken(followup.getReadSnapshotToken());
    }
    if (followup.getEventPayloadJson() != null) {
      builder.setEventPayloadJson(followup.getEventPayloadJson());
    }
  }

  private static void applyPayloadSummary(
      RemoteCommandCoordinatorEntry.Builder builder,
      String payloadJson,
      String payloadKind,
      String requestedCommand,
      boolean requiresSoloTick) {
    PayloadSummary summary =
        payloadSummary(payloadJson, payloadKind, requestedCommand, requiresSoloTick);
    if (summary.kind() != null) {
      builder.setFollowupPayloadKind(summary.kind());
    }
    if (summary.command() != null) {
      builder.setFollowupRequestedCommand(summary.command());
    }
    if (summary.requiresSoloTick()) {
      builder.setFollowupRequiresSoloTick(true);
    }
  }

  private static void applyPayloadSummary(
      RemoteFollowupEntry.Builder builder,
      String payloadJson,
      String payloadKind,
      String requestedCommand,
      boolean requiresSoloTick) {
    PayloadSummary summary =
        payloadSummary(payloadJson, payloadKind, requestedCommand, requiresSoloTick);
    if (summary.kind() != null) {
      builder.setPayloadKind(summary.kind());
    }
    if (summary.command() != null) {
      builder.setRequestedCommand(summary.command());
    }
    if (summary.requiresSoloTick()) {
      builder.setRequiresSoloTick(true);
    }
  }

  private static PayloadSummary payloadSummary(
      String payloadJson, String payloadKind, String requestedCommand, boolean requiresSoloTick) {
    String effectiveKind = blankToNull(payloadKind);
    String effectiveCommand = blankToNull(requestedCommand);
    boolean effectiveRequiresSoloTick = requiresSoloTick;
    if ((effectiveKind == null || effectiveCommand == null)
        && payloadJson != null
        && !payloadJson.isBlank()) {
      try {
        JsonNode root = OBJECT_MAPPER.readTree(payloadJson);
        if (effectiveKind == null) {
          effectiveKind = blankToNull(root.path("kind").asText(""));
        }
        if (effectiveCommand == null) {
          effectiveCommand = blankToNull(root.path("command").asText(""));
        }
        if (!effectiveRequiresSoloTick) {
          effectiveRequiresSoloTick = root.path("requiresSoloTick").asBoolean(false);
        }
      } catch (IOException ignored) {
        // Ignore malformed payload summaries in control-plane readback.
      }
    }
    return new PayloadSummary(effectiveKind, effectiveCommand, effectiveRequiresSoloTick);
  }

  private static void applyResultSummary(
      RemoteCommandCoordinatorEntry.Builder builder,
      String payloadJson,
      String durableCommandId,
      String durableErrorCode,
      String durableMessage) {
    ResultSummary summary =
        resultSummary(payloadJson, durableCommandId, durableErrorCode, durableMessage);
    if (summary.commandId() != null) {
      builder.setLatestResultCommandId(summary.commandId());
    }
    if (summary.errorCode() != null) {
      builder.setLatestResultErrorCode(summary.errorCode());
    }
    if (summary.message() != null) {
      builder.setLatestResultMessage(summary.message());
    }
  }

  private static String applyResultSummary(
      RemoteFollowupResultEntry.Builder builder,
      String payloadJson,
      String durableCommandId,
      String durableErrorCode,
      String durableMessage) {
    ResultSummary summary =
        resultSummary(payloadJson, durableCommandId, durableErrorCode, durableMessage);
    if (summary.commandId() != null) {
      builder.setResultCommandId(summary.commandId());
    }
    if (summary.errorCode() != null) {
      builder.setResultErrorCode(summary.errorCode());
    }
    if (summary.message() != null) {
      builder.setResultMessage(summary.message());
    }
    return summary.commandId();
  }

  private static ResultSummary resultSummary(
      String payloadJson, String durableCommandId, String durableErrorCode, String durableMessage) {
    ResultSummary payloadSummary = resultSummaryFromJson(payloadJson);
    if ((durableCommandId != null && !durableCommandId.isBlank())
        || payloadSummary.commandId() != null
        || (durableErrorCode != null && !durableErrorCode.isBlank())
        || payloadSummary.errorCode() != null
        || (durableMessage != null && !durableMessage.isBlank())
        || payloadSummary.message() != null) {
      return new ResultSummary(
          durableCommandId != null && !durableCommandId.isBlank()
              ? durableCommandId
              : payloadSummary.commandId(),
          durableErrorCode != null && !durableErrorCode.isBlank()
              ? durableErrorCode
              : payloadSummary.errorCode(),
          durableMessage != null && !durableMessage.isBlank()
              ? durableMessage
              : payloadSummary.message());
    }
    return new ResultSummary(null, null, null);
  }

  private static ResultSummary resultSummaryFromJson(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return new ResultSummary(null, null, null);
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(payloadJson);
      String commandId = blankToNull(root.path("commandId").asText(""));
      String errorCode = blankToNull(root.path("errorCode").asText(""));
      if (errorCode == null && root.has("failureCode")) {
        errorCode = blankToNull(root.path("failureCode").asText(""));
      }
      String message = blankToNull(root.path("message").asText(""));
      return new ResultSummary(commandId, errorCode, message);
    } catch (IOException ignored) {
      return new ResultSummary(null, null, null);
    }
  }

  private GameInstance getInstanceOrThrow(long gameInstanceId) {
    return gameInstanceRepository
        .findById(gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
  }

  private CurrentRoutingAuthority resolveCurrentRoutingAuthority(GameInstance instance) {
    Optional<GameplayAdmissionPointerSnapshot> pointer =
        GameplayAdmissionPointerSnapshots.singularCompletePointer(
            gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
                instance.getTenantId(), instance.getId()));
    if (pointer.isEmpty()) {
      return new CurrentRoutingAuthority(
          new GameplayRoutingBundle(
              PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED, "", "", 0L),
          false);
    }
    return new CurrentRoutingAuthority(
        new GameplayRoutingBundle(
            switch (normalizeBlank(pointer.get().stateScope())) {
              case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
              case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
              default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
            },
            normalizeBlank(pointer.get().worldSlug()),
            normalizeBlank(pointer.get().realmSlug()),
            pointer.get().pointerVersion()),
        true);
  }

  private Optional<RuntimeRegionStatus> currentRuntimeOwnership(
      long tenantId, long gameInstanceId) {
    if (tenantId <= 0 || gameInstanceId <= 0) {
      return Optional.empty();
    }
    return runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
  }

  private RemoteFollowupResult latestRemoteResult(RemoteCommandCoordinator coordinator) {
    if (remoteFollowupResultRepository == null || coordinator == null) {
      return null;
    }
    return remoteFollowupResultRepository
        .findLatestForCoordinator(coordinator)
        .filter(result -> matchesCoordinatorScope(result, coordinator))
        .orElse(null);
  }

  private GameplayCommand linkedTargetCommand(long tenantId, RemoteFollowup followup) {
    if (gameplayCommandRepository == null
        || followup == null
        || followup.getFollowupId() == null
        || followup.getFollowupId().isBlank()
        || !hasCompleteRuntimeScope(
            followup.getTargetGameInstanceId(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch())) {
      return null;
    }
    return gameplayCommandRepository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
            tenantId,
            followup.getTargetGameInstanceId(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch(),
            followup.getFollowupId())
        .filter(candidate -> matchesTargetScope(candidate, tenantId, followup))
        .orElse(null);
  }

  private static boolean matchesTargetScope(
      GameplayCommand candidate, long tenantId, RemoteFollowup followup) {
    return hasCompleteRuntimeScope(
            candidate.getGameInstanceId(), candidate.getRegionId(), candidate.getRegionEpoch())
        && hasCompleteRuntimeScope(
            followup.getTargetGameInstanceId(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch())
        && Objects.equals(candidate.getTenantId(), tenantId)
        && Objects.equals(candidate.getGameInstanceId(), followup.getTargetGameInstanceId())
        && Objects.equals(candidate.getRegionId(), followup.getTargetRegionId())
        && Objects.equals(candidate.getRegionEpoch(), followup.getTargetRegionEpoch());
  }

  private static boolean matchesCoordinatorScope(
      RemoteFollowup followup, RemoteCommandCoordinator coordinator) {
    return followup != null
        && coordinator != null
        && hasCompleteRuntimeScope(
            followup.getOriginGameInstanceId(),
            followup.getOriginRegionId(),
            followup.getOriginRegionEpoch())
        && hasCompleteRuntimeScope(
            followup.getTargetGameInstanceId(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch())
        && hasCompleteRuntimeScope(
            coordinator.getOriginGameInstanceId(),
            coordinator.getOriginRegionId(),
            coordinator.getOriginRegionEpoch())
        && hasCompleteRuntimeScope(
            coordinator.getTargetGameInstanceId(),
            coordinator.getTargetRegionId(),
            coordinator.getTargetRegionEpoch())
        && Objects.equals(followup.getTenantId(), coordinator.getTenantId())
        && Objects.equals(followup.getFollowupId(), coordinator.getFollowupId())
        && matchesOptionalIdentity(followup.getCommandId(), coordinator.getCommandId())
        && Objects.equals(followup.getOriginGameInstanceId(), coordinator.getOriginGameInstanceId())
        && Objects.equals(followup.getOriginRegionId(), coordinator.getOriginRegionId())
        && Objects.equals(followup.getOriginRegionEpoch(), coordinator.getOriginRegionEpoch())
        && Objects.equals(followup.getTargetGameInstanceId(), coordinator.getTargetGameInstanceId())
        && Objects.equals(followup.getTargetRegionId(), coordinator.getTargetRegionId())
        && Objects.equals(followup.getTargetRegionEpoch(), coordinator.getTargetRegionEpoch());
  }

  private static boolean matchesCoordinatorScope(
      RemoteFollowupResult result, RemoteCommandCoordinator coordinator) {
    return result != null
        && coordinator != null
        && hasCompleteRuntimeScope(
            result.getOriginGameInstanceId(),
            result.getOriginRegionId(),
            result.getOriginRegionEpoch())
        && hasCompleteRuntimeScope(
            result.getTargetGameInstanceId(),
            result.getTargetRegionId(),
            result.getTargetRegionEpoch())
        && hasCompleteRuntimeScope(
            coordinator.getOriginGameInstanceId(),
            coordinator.getOriginRegionId(),
            coordinator.getOriginRegionEpoch())
        && hasCompleteRuntimeScope(
            coordinator.getTargetGameInstanceId(),
            coordinator.getTargetRegionId(),
            coordinator.getTargetRegionEpoch())
        && Objects.equals(result.getTenantId(), coordinator.getTenantId())
        && Objects.equals(result.getCoordinatorId(), coordinator.getCoordinatorId())
        && Objects.equals(result.getFollowupId(), coordinator.getFollowupId())
        && Objects.equals(result.getOriginGameInstanceId(), coordinator.getOriginGameInstanceId())
        && Objects.equals(result.getOriginRegionId(), coordinator.getOriginRegionId())
        && Objects.equals(result.getOriginRegionEpoch(), coordinator.getOriginRegionEpoch())
        && Objects.equals(result.getTargetGameInstanceId(), coordinator.getTargetGameInstanceId())
        && Objects.equals(result.getTargetRegionId(), coordinator.getTargetRegionId())
        && Objects.equals(result.getTargetRegionEpoch(), coordinator.getTargetRegionEpoch());
  }

  private static boolean matchesCoordinatorScope(
      RemoteFollowup followup, RemoteFollowupResult result) {
    return followup != null
        && result != null
        && hasCompleteRuntimeScope(
            followup.getOriginGameInstanceId(),
            followup.getOriginRegionId(),
            followup.getOriginRegionEpoch())
        && hasCompleteRuntimeScope(
            followup.getTargetGameInstanceId(),
            followup.getTargetRegionId(),
            followup.getTargetRegionEpoch())
        && hasCompleteRuntimeScope(
            result.getOriginGameInstanceId(),
            result.getOriginRegionId(),
            result.getOriginRegionEpoch())
        && hasCompleteRuntimeScope(
            result.getTargetGameInstanceId(),
            result.getTargetRegionId(),
            result.getTargetRegionEpoch())
        && Objects.equals(followup.getTenantId(), result.getTenantId())
        && Objects.equals(followup.getFollowupId(), result.getFollowupId())
        && Objects.equals(followup.getOriginGameInstanceId(), result.getOriginGameInstanceId())
        && Objects.equals(followup.getOriginRegionId(), result.getOriginRegionId())
        && Objects.equals(followup.getOriginRegionEpoch(), result.getOriginRegionEpoch())
        && Objects.equals(followup.getTargetGameInstanceId(), result.getTargetGameInstanceId())
        && Objects.equals(followup.getTargetRegionId(), result.getTargetRegionId())
        && Objects.equals(followup.getTargetRegionEpoch(), result.getTargetRegionEpoch());
  }

  private Map<String, RemoteFollowup> followupMap(
      long tenantId, List<RemoteCommandCoordinator> coordinators) {
    if (remoteFollowupRepository == null) {
      return Map.of();
    }
    List<String> distinctIds =
        distinctNonBlank(
            coordinators.stream().map(RemoteCommandCoordinator::getFollowupId).toList());
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteFollowupRepository.findByTenantIdAndFollowupIdIn(tenantId, distinctIds).stream()
        .filter(
            followup ->
                coordinators.stream()
                    .anyMatch(coordinator -> matchesCoordinatorScope(followup, coordinator)))
        .collect(Collectors.toMap(RemoteFollowup::getFollowupId, Function.identity()));
  }

  private Map<String, RemoteFollowup> followupMapForResults(
      long tenantId, List<RemoteFollowupResult> results) {
    if (remoteFollowupRepository == null) {
      return Map.of();
    }
    List<String> distinctIds =
        distinctNonBlank(results.stream().map(RemoteFollowupResult::getFollowupId).toList());
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteFollowupRepository.findByTenantIdAndFollowupIdIn(tenantId, distinctIds).stream()
        .filter(
            followup ->
                results.stream().anyMatch(result -> matchesCoordinatorScope(followup, result)))
        .collect(Collectors.toMap(RemoteFollowup::getFollowupId, Function.identity()));
  }

  private Map<String, RemoteCommandCoordinator> coordinatorByFollowupMap(
      long tenantId, List<RemoteFollowup> followups) {
    if (remoteCommandCoordinatorRepository == null) {
      return Map.of();
    }
    List<String> distinctIds =
        distinctNonBlank(followups.stream().map(RemoteFollowup::getFollowupId).toList());
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteCommandCoordinatorRepository
        .findByTenantIdAndFollowupIdIn(tenantId, distinctIds)
        .stream()
        .filter(
            coordinator ->
                followups.stream()
                    .anyMatch(followup -> matchesCoordinatorScope(followup, coordinator)))
        .collect(Collectors.toMap(RemoteCommandCoordinator::getFollowupId, Function.identity()));
  }

  private Map<String, RemoteCommandCoordinator> coordinatorMap(
      long tenantId, List<RemoteFollowupResult> results) {
    if (remoteCommandCoordinatorRepository == null) {
      return Map.of();
    }
    List<String> distinctIds =
        distinctNonBlank(results.stream().map(RemoteFollowupResult::getCoordinatorId).toList());
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteCommandCoordinatorRepository
        .findByTenantIdAndCoordinatorIdIn(tenantId, distinctIds)
        .stream()
        .filter(
            coordinator ->
                results.stream().anyMatch(result -> matchesCoordinatorScope(result, coordinator)))
        .collect(Collectors.toMap(RemoteCommandCoordinator::getCoordinatorId, Function.identity()));
  }

  private Map<String, RemoteFollowupResult> latestResultMap(
      long tenantId, List<RemoteCommandCoordinator> coordinators) {
    if (remoteFollowupResultRepository == null || coordinators == null || coordinators.isEmpty()) {
      return Map.of();
    }
    return remoteFollowupResultRepository.findForCoordinatorScopes(coordinators).stream()
        .filter(
            result ->
                coordinators.stream()
                    .anyMatch(coordinator -> matchesCoordinatorScope(result, coordinator)))
        .collect(
            Collectors.toMap(
                RemoteFollowupResult::getCoordinatorId,
                Function.identity(),
                (ignored, replacement) -> replacement));
  }

  private Map<String, GameplayCommand> targetCommandMap(
      long tenantId, Map<String, RemoteFollowup> followupsById) {
    if (gameplayCommandRepository == null || followupsById == null || followupsById.isEmpty()) {
      return Map.of();
    }
    List<String> distinctIds = distinctNonBlank(followupsById.keySet().stream().toList());
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return gameplayCommandRepository
        .findByTenantIdAndRemoteFollowupIdIn(tenantId, distinctIds)
        .stream()
        .filter(
            candidate -> {
              RemoteFollowup followup = followupsById.get(candidate.getRemoteFollowupId());
              return followup != null && matchesTargetScope(candidate, tenantId, followup);
            })
        .collect(
            Collectors.toMap(
                GameplayCommand::getRemoteFollowupId,
                Function.identity(),
                (existing, ignored) -> existing));
  }

  private static boolean hasCompleteRuntimeScope(
      Long gameInstanceId, String regionId, Long regionEpoch) {
    return gameInstanceId != null
        && gameInstanceId > 0
        && regionId != null
        && !regionId.isBlank()
        && regionEpoch != null
        && regionEpoch > 0;
  }

  private static boolean matchesOptionalIdentity(String expected, String actual) {
    return expected == null || expected.isBlank() || Objects.equals(expected, actual);
  }

  private static Map<String, RemoteFollowup> followupsById(List<RemoteFollowup> followups) {
    if (followups == null || followups.isEmpty()) {
      return Map.of();
    }
    return followups.stream()
        .filter(followup -> followup.getFollowupId() != null)
        .collect(
            Collectors.toMap(
                RemoteFollowup::getFollowupId,
                Function.identity(),
                (existing, ignored) -> existing));
  }

  private static List<String> distinctNonBlank(List<String> values) {
    LinkedHashSet<String> distinct = new LinkedHashSet<>();
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        distinct.add(value);
      }
    }
    return List.copyOf(distinct);
  }

  private ScriptPatchPublicationLink scriptPatchPublicationLink(
      long tenantId, String scriptPatchVersion) {
    String normalizedScriptPatchVersion = scriptPatchVersion == null ? "" : scriptPatchVersion;
    GetPublishedScriptPatchVersionResponse response =
        gameDesignClient == null
            ? GetPublishedScriptPatchVersionResponse.getDefaultInstance()
            : gameDesignClient.getPublishedScriptPatchVersion(
                tenantId, normalizedScriptPatchVersion);
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return ScriptPatchPublicationLink.newBuilder()
          .setScriptPatchVersion(normalizedScriptPatchVersion)
          .setVersionId(0L)
          .setBaseVersionId(0L)
          .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED)
          .setLastChangedAtMs(0L)
          .setLookupErrorCode(response.getError().getCode())
          .setLookupErrorMessage(response.getError().getMessage())
          .build();
    }
    return ScriptPatchPublicationLink.newBuilder()
        .setScriptPatchVersion(response.getScriptPatch().getScriptPatchVersion())
        .setVersionId(response.getScriptPatch().getVersionId())
        .setBaseVersionId(response.getScriptPatch().getBaseVersionId())
        .setPublicationState(response.getScriptPatch().getPublicationState())
        .setLastChangedAtMs(response.getScriptPatch().getLastChangedAtMs())
        .build();
  }

  private PluginPublicationLink pluginPublicationLink(
      long tenantId, String pluginId, String pluginVersionId) {
    String normalizedPluginVersionId = pluginVersionId == null ? "" : pluginVersionId;
    GetPublishedPluginVersionResponse response =
        gameDesignClient == null
            ? GetPublishedPluginVersionResponse.getDefaultInstance()
            : gameDesignClient.getPublishedPluginVersion(
                tenantId, pluginId, normalizedPluginVersionId);
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return PluginPublicationLink.newBuilder()
          .setPluginVersionId(normalizedPluginVersionId)
          .setPublicationId(0L)
          .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED)
          .setStatusReason("")
          .setLastChangedAtMs(0L)
          .setLookupErrorCode(response.getError().getCode())
          .setLookupErrorMessage(response.getError().getMessage())
          .build();
    }
    return PluginPublicationLink.newBuilder()
        .setPluginVersionId(response.getPluginVersion().getPluginVersionId())
        .setPublicationId(response.getPluginVersion().getPublicationId())
        .setPublicationState(response.getPluginVersion().getPublicationState())
        .setStatusReason(response.getPluginVersion().getStatusReason())
        .setLastChangedAtMs(response.getPluginVersion().getLastChangedAtMs())
        .build();
  }

  private static PlayableStateScope toPlayableStateScopeStatus(String playableStateScope) {
    if (playableStateScope == null || playableStateScope.isBlank()) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    }
    return switch (playableStateScope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private static String normalizePlayableStateScope(PlayableStateScope playableStateScope) {
    if (playableStateScope == null) {
      return "";
    }
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> "";
    };
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private static String normalizeBlank(String value) {
    return value == null ? "" : value.trim();
  }

  private int boundedRemoteListLimit(int requestedLimit) {
    if (requestedLimit <= 0) {
      return 100;
    }
    return Math.min(requestedLimit, 500);
  }

  private record GameplayRoutingBundle(
      PlayableStateScope playableStateScope,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {}

  private record CurrentRoutingAuthority(
      GameplayRoutingBundle routingBundle, boolean singularRoutingAuthority) {}

  private record CurrentRuntimeBoundary(
      long gameInstanceId,
      String regionId,
      long regionEpoch,
      PlayableStateScope playableStateScope,
      boolean singularRoutingAuthority,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {}

  private record RuntimeBoundaryKey(long tenantId, long gameInstanceId) {}

  private record PayloadSummary(String kind, String command, boolean requiresSoloTick) {}

  private record ResultSummary(String commandId, String errorCode, String message) {}
}
