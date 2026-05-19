package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandAliasResolver;
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
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.GameplayCommandStatus;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.gamesession.v1.PluginPublicationLink;
import net.firedevops.firemud.gamesession.v1.RemoteCommandCoordinatorEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupEntry;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupResultEntry;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasRequest;
import net.firedevops.firemud.gamesession.v1.ValidateBuiltInCommandAliasResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected repository/services and config properties are internal Spring collaborators")
public final class GameSessionCommandControlPlaneService {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Logger logger =
      LoggerFactory.getLogger(GameSessionCommandControlPlaneService.class);
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private final RemoteFollowupResultRepository remoteFollowupResultRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameDesignClient gameDesignClient;
  private final TickService tickService;
  private final BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver;
  private final MeterRegistry meterRegistry;

  @Autowired
  public GameSessionCommandControlPlaneService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameDesignClient gameDesignClient,
      BuiltInTextCommandAliasResolver builtInTextCommandAliasResolver,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.remoteFollowupResultRepository = remoteFollowupResultRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.gameDesignClient = gameDesignClient;
    this.builtInTextCommandAliasResolver = builtInTextCommandAliasResolver;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
  }

  private long parseTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenant_id is required");
    }
    try {
      return Long.parseLong(tenantId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("tenant_id must be a number");
    }
  }

  private long parseGameInstanceId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("game_instance_id is required");
    }
    try {
      return Long.parseLong(gameInstanceId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("game_instance_id must be a number");
    }
  }

  private Long parseOptionalGameInstanceId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      return null;
    }
    return parseGameInstanceId(gameInstanceId);
  }

  private GameInstance getInstanceOrThrow(long gameInstanceId) {
    return gameInstanceRepository
        .findById(gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
  }

  @Timed(value = "gamesessionGrpc.controlPlane.getGameplayCommandStatus")
  public GetGameplayCommandStatusResponse getGameplayCommandStatus(
      GetGameplayCommandStatusRequest request) {
    GameplayCommand command = findGameplayCommandStatus(request);
    return GetGameplayCommandStatusResponse.newBuilder().setCommand(toStatus(command)).build();
  }

  private GameplayCommand findGameplayCommandStatus(GetGameplayCommandStatusRequest request) {
    if (!request.getCommandId().isBlank()) {
      return gameplayCommandRepository
          .findByCommandId(request.getCommandId())
          .orElseThrow(() -> new IllegalArgumentException("Gameplay command not found"));
    }
    long tenantId = parseTenantId(request.getTenantId());
    long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
    requireText(request.getRegionId(), "region_id is required");
    if (request.getRegionEpoch() <= 0) {
      throw new IllegalArgumentException("region_epoch must be positive");
    }
    requireText(request.getAutomationDispatchId(), "automation_dispatch_id is required");
    return gameplayCommandRepository
        .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
            tenantId,
            gameInstanceId,
            request.getRegionId(),
            request.getRegionEpoch(),
            request.getAutomationDispatchId())
        .orElseThrow(() -> new IllegalArgumentException("Gameplay command not found"));
  }

  @Timed(value = "gamesessionGrpc.controlPlane.validateBuiltInCommandAlias")
  public ValidateBuiltInCommandAliasResponse validateBuiltInCommandAlias(
      ValidateBuiltInCommandAliasRequest request) {
    String alias = request.getAlias();
    if (alias == null || alias.isBlank()) {
      throw new IllegalArgumentException("alias is required");
    }
    return builtInTextCommandAliasResolver
        .resolve(alias)
        .map(
            normalized ->
                ValidateBuiltInCommandAliasResponse.newBuilder()
                    .setSupported(true)
                    .setNormalizedAlias(normalized)
                    .build())
        .orElseGet(() -> ValidateBuiltInCommandAliasResponse.newBuilder().build());
  }

  @Timed(value = "gamesessionGrpc.controlPlane.enqueueAutomationCommandIfAbsent")
  public EnqueueAutomationCommandIfAbsentResponse enqueueAutomationCommandIfAbsent(
      EnqueueAutomationCommandIfAbsentRequest request) {
    return enqueueAutomationCommand(request);
  }

  private EnqueueAutomationCommandIfAbsentResponse enqueueAutomationCommand(
      EnqueueAutomationCommandIfAbsentRequest request) {
    AutomationGameplayCommandAdmissionSupport.AdmissionResult result =
        AutomationGameplayCommandAdmissionSupport.admitIfAbsent(
            new AutomationGameplayCommandAdmissionSupport.AdmissionRequest(
                parseTenantId(request.getTenantId()),
                parseGameInstanceId(request.getGameInstanceId()),
                request.getRegionId(),
                request.getRegionEpoch(),
                "AUTOMATION",
                request.getAutomationDispatchId(),
                request.getAutomationWorkItemId(),
                request.getScriptId(),
                request.getScriptPatchVersion(),
                normalizeBlank(request.getPluginId()),
                normalizeBlank(request.getPluginVersionId()),
                normalizePlayableStateScope(request.getPlayableStateScope()),
                normalizeBlank(request.getWorldSlug()),
                normalizeBlank(request.getRealmSlug()),
                parsePointerVersionClaim(request.getPointerVersion()),
                normalizeBlank(request.getOriginSourceKind()),
                normalizeBlank(request.getOriginSourceState()),
                request.getOriginSourceOrdinal() > 0 ? request.getOriginSourceOrdinal() : null,
                request.getOriginSourceDueTickId() > 0 ? request.getOriginSourceDueTickId() : null,
                request.getOriginSourceDueAtMs() > 0 ? request.getOriginSourceDueAtMs() : null,
                request.getTargetEntityId(),
                null,
                null,
                request.getCommand(),
                request.getRequiresSoloTick(),
                request.getDueTickId() > 0 ? request.getDueTickId() : null),
            gameInstanceRepository,
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            tickService);
    EnqueueAutomationCommandIfAbsentResponse.Builder builder =
        EnqueueAutomationCommandIfAbsentResponse.newBuilder()
            .setAccepted(result.accepted())
            .setAdmissionOutcome(result.admissionOutcome());
    if (result.commandId() != null) {
      builder.setCommandId(result.commandId());
    }
    if (result.errorCode() != null) {
      builder.setError(
          GrpcAppErrors.error(meterRegistry, result.errorCode(), result.errorMessage()));
    }
    return builder.build();
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private GameplayRoutingBundle resolveGameplayRouting(GameInstance instance) {
    return gameplayAdmissionPointerAuthorityService
        .findByRuntimeTarget(instance.getTenantId(), instance.getId())
        .map(
            pointer ->
                new GameplayRoutingBundle(
                    switch (normalizeBlank(pointer.stateScope())) {
                      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
                      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
                      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
                    },
                    normalizeBlank(pointer.worldSlug()),
                    normalizeBlank(pointer.realmSlug()),
                    pointer.pointerVersion()))
        .orElse(
            new GameplayRoutingBundle(
                PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED, "", "", 0L));
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

  private static Long parsePointerVersionClaim(String pointerVersion) {
    if (pointerVersion == null || pointerVersion.isBlank()) {
      return null;
    }
    return Long.parseLong(pointerVersion);
  }

  private static Long parseGameplayCharacterId(String targetEntityId) {
    if (targetEntityId == null || targetEntityId.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(targetEntityId);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private record GameplayRoutingBundle(
      PlayableStateScope playableStateScope,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {}

  private int boundedRemoteListLimit(int requestedLimit) {
    if (requestedLimit <= 0) {
      return 100;
    }
    return Math.min(requestedLimit, 500);
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
      GameplayCommand targetCommand) {
    RemoteCommandCoordinatorEntry.Builder builder =
        RemoteCommandCoordinatorEntry.newBuilder()
            .setCoordinatorId(coordinator.getCoordinatorId())
            .setTenantId(Long.toString(coordinator.getTenantId()))
            .setCommandId(coordinator.getCommandId())
            .setFollowupId(coordinator.getFollowupId())
            .setOriginGameInstanceId(Long.toString(coordinator.getOriginGameInstanceId()))
            .setOriginRegionId(coordinator.getOriginRegionId())
            .setOriginRegionEpoch(coordinator.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(coordinator.getTargetGameInstanceId()))
            .setTargetRegionId(coordinator.getTargetRegionId())
            .setTargetRegionEpoch(coordinator.getTargetRegionEpoch())
            .setTargetDueTickId(coordinator.getTargetDueTickId())
            .setOriginDeadlineRegionEpoch(coordinator.getOriginDeadlineRegionEpoch())
            .setOriginDeadlineTickId(coordinator.getOriginDeadlineTickId())
            .setState(coordinator.getState())
            .setLateResultPolicy(coordinator.getLateResultPolicy())
            .setUpdatedAtMs(
                coordinator.getUpdatedAt() == null
                    ? 0L
                    : coordinator.getUpdatedAt().toEpochMilli());
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
      builder.setFollowupStatus(followup.getStatus());
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
        builder, coordinator.getTenantId(), coordinator.getOriginGameInstanceId(), true);
    applyCurrentRuntimeScope(
        builder, coordinator.getTenantId(), coordinator.getTargetGameInstanceId(), false);
    builder.setIsOriginRoutingBundleStale(
        isCurrentRoutingBundleStale(
            coordinator.getTenantId(),
            coordinator.getOriginGameInstanceId(),
            coordinator.getPlayableStateScope(),
            coordinator.getWorldSlug(),
            coordinator.getRealmSlug(),
            coordinator.getPointerVersion()));
    builder.setIsTargetRoutingBundleStale(
        isCurrentRoutingBundleStale(
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
      RemoteCommandCoordinator coordinator) {
    RemoteFollowupEntry.Builder builder =
        RemoteFollowupEntry.newBuilder()
            .setFollowupId(followup.getFollowupId())
            .setTenantId(Long.toString(followup.getTenantId()))
            .setOriginGameInstanceId(Long.toString(followup.getOriginGameInstanceId()))
            .setOriginRegionId(followup.getOriginRegionId())
            .setOriginRegionEpoch(followup.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(followup.getTargetGameInstanceId()))
            .setTargetRegionId(followup.getTargetRegionId())
            .setTargetRegionEpoch(followup.getTargetRegionEpoch())
            .setDueTickId(followup.getDueTickId())
            .setEffectKey(followup.getEffectKey())
            .setStatus(followup.getStatus())
            .setCreatedAtMs(
                followup.getCreatedAt() == null ? 0L : followup.getCreatedAt().toEpochMilli())
            .setUpdatedAtMs(
                followup.getUpdatedAt() == null ? 0L : followup.getUpdatedAt().toEpochMilli());
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
        builder, followup.getTenantId(), followup.getOriginGameInstanceId(), true);
    applyCurrentRuntimeScope(
        builder, followup.getTenantId(), followup.getTargetGameInstanceId(), false);
    builder.setIsOriginRoutingBundleStale(
        isCurrentRoutingBundleStale(
            followup.getTenantId(),
            followup.getOriginGameInstanceId(),
            followup.getPlayableStateScope(),
            followup.getWorldSlug(),
            followup.getRealmSlug(),
            followup.getPointerVersion()));
    builder.setIsTargetRoutingBundleStale(
        isCurrentRoutingBundleStale(
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
      GameplayCommand targetCommand) {
    RemoteFollowupResultEntry.Builder builder =
        RemoteFollowupResultEntry.newBuilder()
            .setResultId(result.getResultId())
            .setTenantId(Long.toString(result.getTenantId()))
            .setCoordinatorId(result.getCoordinatorId())
            .setFollowupId(result.getFollowupId())
            .setOriginGameInstanceId(Long.toString(result.getOriginGameInstanceId()))
            .setOriginRegionId(result.getOriginRegionId())
            .setOriginRegionEpoch(result.getOriginRegionEpoch())
            .setTargetGameInstanceId(Long.toString(result.getTargetGameInstanceId()))
            .setTargetRegionId(result.getTargetRegionId())
            .setTargetRegionEpoch(result.getTargetRegionEpoch())
            .setOutcome(result.getOutcome())
            .setObservedAtMs(
                result.getObservedAt() == null ? 0L : result.getObservedAt().toEpochMilli());
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
      targetCommand = gameplayCommandRepository.findByCommandId(resultCommandId).orElse(null);
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
    applyCurrentRuntimeScope(builder, result.getTenantId(), result.getOriginGameInstanceId(), true);
    applyCurrentRuntimeScope(
        builder, result.getTenantId(), result.getTargetGameInstanceId(), false);
    builder.setIsOriginRoutingBundleStale(
        isCurrentRoutingBundleStale(
            result.getTenantId(),
            result.getOriginGameInstanceId(),
            result.getPlayableStateScope(),
            result.getWorldSlug(),
            result.getRealmSlug(),
            result.getPointerVersion()));
    builder.setIsTargetRoutingBundleStale(
        isCurrentRoutingBundleStale(
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
      boolean originScope) {
    currentRuntimeBoundary(tenantId, gameInstanceId)
        .ifPresent(
            currentBoundary -> {
              if (currentBoundary.gameInstanceId() > 0) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeGameInstanceId(
                      Long.toString(currentBoundary.gameInstanceId()));
                } else {
                  builder.setCurrentTargetRuntimeGameInstanceId(
                      Long.toString(currentBoundary.gameInstanceId()));
                }
              }
              if (currentBoundary.regionId() != null && !currentBoundary.regionId().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeRegionId(currentBoundary.regionId());
                } else {
                  builder.setCurrentTargetRuntimeRegionId(currentBoundary.regionId());
                }
              }
              if (originScope) {
                builder.setCurrentOriginRuntimeRegionEpoch(currentBoundary.regionEpoch());
              } else {
                builder.setCurrentTargetRuntimeRegionEpoch(currentBoundary.regionEpoch());
              }
              if (currentBoundary.playableStateScope() != null) {
                if (originScope) {
                  builder.setCurrentOriginRuntimePlayableStateScope(
                      currentBoundary.playableStateScope());
                } else {
                  builder.setCurrentTargetRuntimePlayableStateScope(
                      currentBoundary.playableStateScope());
                }
              }
              if (currentBoundary.worldSlug() != null && !currentBoundary.worldSlug().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeWorldSlug(currentBoundary.worldSlug());
                } else {
                  builder.setCurrentTargetRuntimeWorldSlug(currentBoundary.worldSlug());
                }
              }
              if (currentBoundary.realmSlug() != null && !currentBoundary.realmSlug().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeRealmSlug(currentBoundary.realmSlug());
                } else {
                  builder.setCurrentTargetRuntimeRealmSlug(currentBoundary.realmSlug());
                }
              }
              if (currentBoundary.pointerVersion() != null
                  && currentBoundary.pointerVersion() > 0) {
                if (originScope) {
                  builder.setCurrentOriginRuntimePointerVersion(currentBoundary.pointerVersion());
                } else {
                  builder.setCurrentTargetRuntimePointerVersion(currentBoundary.pointerVersion());
                }
              }
            });
  }

  private void applyCurrentRuntimeScope(
      RemoteFollowupEntry.Builder builder,
      long tenantId,
      long gameInstanceId,
      boolean originScope) {
    currentRuntimeBoundary(tenantId, gameInstanceId)
        .ifPresent(
            currentBoundary -> {
              if (currentBoundary.gameInstanceId() > 0) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeGameInstanceId(
                      Long.toString(currentBoundary.gameInstanceId()));
                } else {
                  builder.setCurrentTargetRuntimeGameInstanceId(
                      Long.toString(currentBoundary.gameInstanceId()));
                }
              }
              if (currentBoundary.regionId() != null && !currentBoundary.regionId().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeRegionId(currentBoundary.regionId());
                } else {
                  builder.setCurrentTargetRuntimeRegionId(currentBoundary.regionId());
                }
              }
              if (originScope) {
                builder.setCurrentOriginRuntimeRegionEpoch(currentBoundary.regionEpoch());
              } else {
                builder.setCurrentTargetRuntimeRegionEpoch(currentBoundary.regionEpoch());
              }
              if (currentBoundary.playableStateScope() != null) {
                if (originScope) {
                  builder.setCurrentOriginRuntimePlayableStateScope(
                      currentBoundary.playableStateScope());
                } else {
                  builder.setCurrentTargetRuntimePlayableStateScope(
                      currentBoundary.playableStateScope());
                }
              }
              if (currentBoundary.worldSlug() != null && !currentBoundary.worldSlug().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeWorldSlug(currentBoundary.worldSlug());
                } else {
                  builder.setCurrentTargetRuntimeWorldSlug(currentBoundary.worldSlug());
                }
              }
              if (currentBoundary.realmSlug() != null && !currentBoundary.realmSlug().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeRealmSlug(currentBoundary.realmSlug());
                } else {
                  builder.setCurrentTargetRuntimeRealmSlug(currentBoundary.realmSlug());
                }
              }
              if (currentBoundary.pointerVersion() != null
                  && currentBoundary.pointerVersion() > 0) {
                if (originScope) {
                  builder.setCurrentOriginRuntimePointerVersion(currentBoundary.pointerVersion());
                } else {
                  builder.setCurrentTargetRuntimePointerVersion(currentBoundary.pointerVersion());
                }
              }
            });
  }

  private void applyCurrentRuntimeScope(
      RemoteFollowupResultEntry.Builder builder,
      long tenantId,
      long gameInstanceId,
      boolean originScope) {
    currentRuntimeBoundary(tenantId, gameInstanceId)
        .ifPresent(
            currentBoundary -> {
              if (currentBoundary.gameInstanceId() > 0) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeGameInstanceId(
                      Long.toString(currentBoundary.gameInstanceId()));
                } else {
                  builder.setCurrentTargetRuntimeGameInstanceId(
                      Long.toString(currentBoundary.gameInstanceId()));
                }
              }
              if (currentBoundary.regionId() != null && !currentBoundary.regionId().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeRegionId(currentBoundary.regionId());
                } else {
                  builder.setCurrentTargetRuntimeRegionId(currentBoundary.regionId());
                }
              }
              if (originScope) {
                builder.setCurrentOriginRuntimeRegionEpoch(currentBoundary.regionEpoch());
              } else {
                builder.setCurrentTargetRuntimeRegionEpoch(currentBoundary.regionEpoch());
              }
              if (currentBoundary.playableStateScope() != null) {
                if (originScope) {
                  builder.setCurrentOriginRuntimePlayableStateScope(
                      currentBoundary.playableStateScope());
                } else {
                  builder.setCurrentTargetRuntimePlayableStateScope(
                      currentBoundary.playableStateScope());
                }
              }
              if (currentBoundary.worldSlug() != null && !currentBoundary.worldSlug().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeWorldSlug(currentBoundary.worldSlug());
                } else {
                  builder.setCurrentTargetRuntimeWorldSlug(currentBoundary.worldSlug());
                }
              }
              if (currentBoundary.realmSlug() != null && !currentBoundary.realmSlug().isBlank()) {
                if (originScope) {
                  builder.setCurrentOriginRuntimeRealmSlug(currentBoundary.realmSlug());
                } else {
                  builder.setCurrentTargetRuntimeRealmSlug(currentBoundary.realmSlug());
                }
              }
              if (currentBoundary.pointerVersion() != null
                  && currentBoundary.pointerVersion() > 0) {
                if (originScope) {
                  builder.setCurrentOriginRuntimePointerVersion(currentBoundary.pointerVersion());
                } else {
                  builder.setCurrentTargetRuntimePointerVersion(currentBoundary.pointerVersion());
                }
              }
            });
  }

  private Optional<CurrentRuntimeBoundary> currentRuntimeBoundary(
      long tenantId, long gameInstanceId) {
    return currentRuntimeOwnership(tenantId, gameInstanceId)
        .map(
            ownership -> {
              GameInstance instance = getInstanceOrThrow(ownership.getGameInstanceId());
              GameplayRoutingBundle routingBundle = resolveGameplayRouting(instance);
              RoutingBundle normalizedRoutingBundle =
                  normalizeRoutingBundle(
                      routingBundle.worldSlug(),
                      routingBundle.realmSlug(),
                      routingBundle.pointerVersion());
              return new CurrentRuntimeBoundary(
                  ownership.getGameInstanceId(),
                  ownership.getRegionId(),
                  ownership.getRegionEpoch(),
                  routingBundle.playableStateScope(),
                  normalizedRoutingBundle == null ? "" : normalizedRoutingBundle.worldSlug(),
                  normalizedRoutingBundle == null ? "" : normalizedRoutingBundle.realmSlug(),
                  normalizedRoutingBundle == null
                      ? null
                      : normalizedRoutingBundle.pointerVersion());
            });
  }

  private boolean isCurrentRoutingBundleStale(
      long tenantId,
      long gameInstanceId,
      String persistedPlayableStateScope,
      String persistedWorldSlug,
      String persistedRealmSlug,
      Long persistedPointerVersion) {
    return currentRuntimeBoundary(tenantId, gameInstanceId)
        .map(
            currentBoundary ->
                isRoutingBundleStale(
                    persistedPlayableStateScope,
                    persistedWorldSlug,
                    persistedRealmSlug,
                    persistedPointerVersion,
                    currentBoundary))
        .orElse(false);
  }

  private static boolean isRoutingBundleStale(
      String persistedPlayableStateScope,
      String persistedWorldSlug,
      String persistedRealmSlug,
      Long persistedPointerVersion,
      CurrentRuntimeBoundary currentBoundary) {
    RoutingBundle persistedRoutingBundle =
        normalizeRoutingBundle(persistedWorldSlug, persistedRealmSlug, persistedPointerVersion);
    RoutingBundle currentRoutingBundle =
        normalizeRoutingBundle(
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
    RoutingBundle routingBundle = normalizeRoutingBundle(worldSlug, realmSlug, pointerVersion);
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
    RoutingBundle routingBundle = normalizeRoutingBundle(worldSlug, realmSlug, pointerVersion);
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
    RoutingBundle routingBundle = normalizeRoutingBundle(worldSlug, realmSlug, pointerVersion);
    if (routingBundle != null) {
      builder.setWorldSlug(routingBundle.worldSlug());
      builder.setRealmSlug(routingBundle.realmSlug());
      builder.setPointerVersion(routingBundle.pointerVersion());
    }
  }

  private static RoutingBundle normalizeRoutingBundle(
      String worldSlug, String realmSlug, Long pointerVersion) {
    boolean hasWorld = worldSlug != null && !worldSlug.isBlank();
    boolean hasRealm = realmSlug != null && !realmSlug.isBlank();
    boolean hasPointer = pointerVersion != null && pointerVersion > 0;
    if (hasWorld && hasRealm && hasPointer) {
      return new RoutingBundle(worldSlug, realmSlug, pointerVersion);
    }
    return null;
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

  private static void applyResultSummary(
      GameplayCommandStatus.Builder builder,
      String payloadJson,
      String durableCommandId,
      String durableErrorCode,
      String durableMessage) {
    ResultSummary summary =
        resultSummary(payloadJson, durableCommandId, durableErrorCode, durableMessage);
    if (summary.commandId() != null) {
      builder.setRemoteResultCommandId(summary.commandId());
    }
    if (summary.errorCode() != null) {
      builder.setRemoteResultErrorCode(summary.errorCode());
    }
    if (summary.message() != null) {
      builder.setRemoteResultMessage(summary.message());
    }
  }

  private static PayloadSummary payloadSummary(
      String payloadJson, String payloadKind, String requestedCommand, boolean requiresSoloTick) {
    if ((payloadKind != null && !payloadKind.isBlank())
        || (requestedCommand != null && !requestedCommand.isBlank())
        || requiresSoloTick) {
      return new PayloadSummary(
          blankToNull(payloadKind), blankToNull(requestedCommand), requiresSoloTick);
    }
    if (payloadJson == null || payloadJson.isBlank()) {
      return new PayloadSummary(null, null, false);
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(payloadJson);
      String kind = blankToNull(root.path("kind").asText(""));
      String command = blankToNull(root.path("command").asText(""));
      return new PayloadSummary(kind, command, root.path("requiresSoloTick").asBoolean(false));
    } catch (IOException ignored) {
      return new PayloadSummary(null, null, false);
    }
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

  private record PayloadSummary(String kind, String command, boolean requiresSoloTick) {}

  private record ResultSummary(String commandId, String errorCode, String message) {}

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String blankToEmpty(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private GameplayCommandStatus toStatus(GameplayCommand command) {
    RemoteCommandCoordinator remoteCoordinator = resolveRemoteCoordinator(command);
    RemoteFollowup remoteFollowup = resolveRemoteFollowup(command, remoteCoordinator);
    GameplayCommand remoteTargetCommand =
        remoteCoordinator == null
            ? null
            : linkedTargetCommand(command.getTenantId(), remoteCoordinator.getFollowupId());
    RemoteFollowupResult latestRemoteResult =
        remoteCoordinator == null || remoteFollowupResultRepository == null
            ? null
            : latestRemoteResult(command.getTenantId(), remoteCoordinator.getCoordinatorId());
    GameplayCommandStatus.Builder builder =
        GameplayCommandStatus.newBuilder()
            .setCommandId(command.getCommandId())
            .setTenantId(command.getTenantId().toString())
            .setGameInstanceId(command.getGameInstanceId().toString())
            .setSessionId(command.getSessionId().toString())
            .setCommandName(command.getCommandName())
            .setSanitizedCommandText(command.getSanitizedCommandText())
            .setRequiresSoloTick(command.isRequiresSoloTick())
            .setExecutionOutcome(command.getExecutionOutcome())
            .setGameplayResult(command.getGameplayResult())
            .setAcceptedAtMs(toEpochMillis(command.getAcceptedAt()))
            .setLastAttemptAtMs(toEpochMillis(command.getLastAttemptAt()))
            .setAttemptCount(command.getAttemptCount())
            .setPlayableStateScope(toPlayableStateScopeStatus(command.getPlayableStateScope()));
    if (command.getAccountId() != null) {
      builder.setAccountId(command.getAccountId().toString());
    }
    if (command.getCharacterId() != null) {
      builder.setCharacterId(command.getCharacterId().toString());
    }
    if (command.getStagedAt() != null) {
      builder.setStagedAtMs(toEpochMillis(command.getStagedAt()));
    }
    if (command.getCompletedAt() != null) {
      builder.setCompletedAtMs(toEpochMillis(command.getCompletedAt()));
    }
    if (command.getFailureCode() != null) {
      builder.setFailureCode(command.getFailureCode());
    }
    if (command.getFailureMessage() != null) {
      builder.setFailureMessage(command.getFailureMessage());
    }
    if (command.getSourceType() != null) {
      builder.setSourceType(command.getSourceType());
    }
    if (command.getAutomationDispatchId() != null) {
      builder.setAutomationDispatchId(command.getAutomationDispatchId());
    }
    if (command.getAutomationWorkItemId() != null) {
      builder.setAutomationWorkItemId(command.getAutomationWorkItemId());
    }
    if (command.getScriptId() != null) {
      builder.setScriptId(command.getScriptId());
    }
    if (command.getScriptPatchVersion() != null) {
      builder.setScriptPatchVersion(command.getScriptPatchVersion());
    }
    if (command.getPluginId() != null) {
      builder.setPluginId(command.getPluginId());
    }
    if (command.getPluginVersionId() != null) {
      builder.setPluginVersionId(command.getPluginVersionId());
    }
    if (command.getTargetEntityId() != null) {
      builder.setTargetEntityId(command.getTargetEntityId());
    }
    if (command.getRemoteCoordinatorId() != null) {
      builder.setRemoteCoordinatorId(command.getRemoteCoordinatorId());
    }
    if (command.getRemoteFollowupId() != null) {
      builder.setRemoteFollowupId(command.getRemoteFollowupId());
    }
    if (command.getRegionId() != null) {
      builder.setRegionId(command.getRegionId());
    }
    if (command.getRegionEpoch() != null) {
      builder.setRegionEpoch(command.getRegionEpoch());
    }
    if (command.getDueTickId() != null) {
      builder.setDueTickId(command.getDueTickId());
    }
    if (command.getEnqueueSeq() != null) {
      builder.setEnqueueSeq(command.getEnqueueSeq());
    }
    RoutingBundle routingBundle =
        normalizeRoutingBundle(
            command.getWorldSlug(), command.getRealmSlug(), command.getPointerVersion());
    if (routingBundle != null) {
      builder.setWorldSlug(routingBundle.worldSlug());
      builder.setRealmSlug(routingBundle.realmSlug());
      builder.setPointerVersion(routingBundle.pointerVersion());
    }
    if (command.getOriginSourceKind() != null) {
      builder.setOriginSourceKind(command.getOriginSourceKind());
    }
    if (command.getOriginSourceState() != null) {
      builder.setOriginSourceState(command.getOriginSourceState());
    }
    if (command.getOriginSourceOrdinal() != null) {
      builder.setOriginSourceOrdinal(command.getOriginSourceOrdinal());
    }
    if (command.getOriginSourceDueTickId() != null) {
      builder.setOriginSourceDueTickId(command.getOriginSourceDueTickId());
    }
    if (command.getOriginSourceDueAtMs() != null) {
      builder.setOriginSourceDueAtMs(command.getOriginSourceDueAtMs());
    }
    if (command.getQueueSourceKind() != null) {
      builder.setQueueSourceKind(command.getQueueSourceKind());
    }
    if (command.getQueueSourceState() != null) {
      builder.setQueueSourceState(command.getQueueSourceState());
    }
    if (command.getQueueSourceOrdinal() != null) {
      builder.setQueueSourceOrdinal(command.getQueueSourceOrdinal());
    }
    if (command.getQueueSourceDueTickId() != null) {
      builder.setQueueSourceDueTickId(command.getQueueSourceDueTickId());
    }
    if (command.getQueueSourceDueAtMs() != null) {
      builder.setQueueSourceDueAtMs(command.getQueueSourceDueAtMs());
    }
    currentRuntimeBoundary(command.getTenantId(), command.getGameInstanceId())
        .ifPresent(
            currentBoundary -> {
              if (currentBoundary.gameInstanceId() > 0) {
                builder.setCurrentRuntimeGameInstanceId(
                    Long.toString(currentBoundary.gameInstanceId()));
              }
              if (currentBoundary.regionId() != null && !currentBoundary.regionId().isBlank()) {
                builder.setCurrentRuntimeRegionId(currentBoundary.regionId());
              }
              builder.setCurrentRuntimeRegionEpoch(currentBoundary.regionEpoch());
              if (currentBoundary.playableStateScope() != null) {
                builder.setCurrentRuntimePlayableStateScope(currentBoundary.playableStateScope());
              }
              if (currentBoundary.worldSlug() != null && !currentBoundary.worldSlug().isBlank()) {
                builder.setCurrentRuntimeWorldSlug(currentBoundary.worldSlug());
              }
              if (currentBoundary.realmSlug() != null && !currentBoundary.realmSlug().isBlank()) {
                builder.setCurrentRuntimeRealmSlug(currentBoundary.realmSlug());
              }
              if (currentBoundary.pointerVersion() != null
                  && currentBoundary.pointerVersion() > 0) {
                builder.setCurrentRuntimePointerVersion(currentBoundary.pointerVersion());
              }
            });
    builder.setIsCurrentRuntimeRoutingBundleStale(
        isCurrentRoutingBundleStale(
            command.getTenantId(),
            command.getGameInstanceId(),
            command.getPlayableStateScope(),
            command.getWorldSlug(),
            command.getRealmSlug(),
            command.getPointerVersion()));
    if (remoteCoordinator != null) {
      builder.setRemoteCoordinatorId(remoteCoordinator.getCoordinatorId());
      builder.setRemoteFollowupId(remoteCoordinator.getFollowupId());
      builder.setRemoteState(remoteCoordinator.getState());
      if (remoteCoordinator.getOriginGameInstanceId() != null) {
        builder.setRemoteOriginGameInstanceId(
            Long.toString(remoteCoordinator.getOriginGameInstanceId()));
      }
      if (remoteCoordinator.getOriginRegionId() != null) {
        builder.setRemoteOriginRegionId(remoteCoordinator.getOriginRegionId());
      }
      builder.setRemoteOriginRegionEpoch(remoteCoordinator.getOriginRegionEpoch());
      if (remoteCoordinator.getTargetGameInstanceId() != null) {
        builder.setRemoteTargetGameInstanceId(
            Long.toString(remoteCoordinator.getTargetGameInstanceId()));
      }
      if (remoteCoordinator.getTargetRegionId() != null) {
        builder.setRemoteTargetRegionId(remoteCoordinator.getTargetRegionId());
      }
      builder.setRemoteTargetRegionEpoch(remoteCoordinator.getTargetRegionEpoch());
      builder.setRemoteOriginDeadlineRegionEpoch(remoteCoordinator.getOriginDeadlineRegionEpoch());
      builder.setRemoteOriginDeadlineTickId(remoteCoordinator.getOriginDeadlineTickId());
      if (remoteCoordinator.getLateResultPolicy() != null) {
        builder.setRemoteLateResultPolicy(remoteCoordinator.getLateResultPolicy());
      }
    }
    if (remoteFollowup != null) {
      if (remoteFollowup.getStatus() != null) {
        builder.setRemoteFollowupStatus(remoteFollowup.getStatus());
      }
      if (remoteFollowup.getPayloadKind() != null) {
        builder.setRemoteFollowupPayloadKind(remoteFollowup.getPayloadKind());
      }
      if (remoteFollowup.getRequestedCommand() != null) {
        builder.setRemoteFollowupRequestedCommand(remoteFollowup.getRequestedCommand());
      }
      if (remoteFollowup.isRequiresSoloTick()) {
        builder.setRemoteFollowupRequiresSoloTick(true);
      }
      if (remoteFollowup.getOriginSourceKind() != null) {
        builder.setRemoteFollowupOriginSourceKind(remoteFollowup.getOriginSourceKind());
      }
      if (remoteFollowup.getOriginSourceState() != null) {
        builder.setRemoteFollowupOriginSourceState(remoteFollowup.getOriginSourceState());
      }
      if (remoteFollowup.getOriginSourceOrdinal() != null) {
        builder.setRemoteFollowupOriginSourceOrdinal(remoteFollowup.getOriginSourceOrdinal());
      }
      if (remoteFollowup.getOriginSourceDueTickId() != null) {
        builder.setRemoteFollowupOriginSourceDueTickId(remoteFollowup.getOriginSourceDueTickId());
      }
      if (remoteFollowup.getOriginSourceDueAtMs() != null) {
        builder.setRemoteFollowupOriginSourceDueAtMs(remoteFollowup.getOriginSourceDueAtMs());
      }
      if (remoteFollowup.getTargetEntityId() != null) {
        builder.setRemoteTargetEntityId(remoteFollowup.getTargetEntityId());
      }
      if (remoteFollowup.getClaimTargetAggregate() != null) {
        builder.setRemoteFollowupClaimTargetAggregate(remoteFollowup.getClaimTargetAggregate());
      }
      if (remoteFollowup.getEffectKey() != null) {
        builder.setRemoteFollowupEffectKey(remoteFollowup.getEffectKey());
      }
      if (remoteFollowup.getFailureCode() != null) {
        builder.setRemoteFollowupFailureCode(remoteFollowup.getFailureCode());
      }
      if (remoteFollowup.getFailureMessage() != null) {
        builder.setRemoteFollowupFailureMessage(remoteFollowup.getFailureMessage());
      }
      if (remoteFollowup.getEventType() != null) {
        builder.setRemoteFollowupEventType(remoteFollowup.getEventType());
      }
      if (remoteFollowup.getEventSchemaVersion() != null) {
        builder.setRemoteFollowupEventSchemaVersion(remoteFollowup.getEventSchemaVersion());
      }
      if (remoteFollowup.getScriptEventId() != null) {
        builder.setRemoteFollowupScriptEventId(remoteFollowup.getScriptEventId());
      }
      if (remoteFollowup.getTriggerMode() != null) {
        builder.setRemoteFollowupTriggerMode(remoteFollowup.getTriggerMode());
      }
    }
    if (latestRemoteResult != null) {
      builder.setRemoteResultOutcome(latestRemoteResult.getOutcome());
      if (latestRemoteResult.getResultPayloadJson() != null) {
        builder.setRemoteResultPayloadJson(latestRemoteResult.getResultPayloadJson());
      }
      if (latestRemoteResult.getObservedAt() != null) {
        builder.setRemoteResultObservedAtMs(latestRemoteResult.getObservedAt().toEpochMilli());
      }
      applyResultSummary(
          builder,
          latestRemoteResult.getResultPayloadJson(),
          latestRemoteResult.getResultCommandId(),
          latestRemoteResult.getResultErrorCode(),
          latestRemoteResult.getResultMessage());
    }
    if (remoteTargetCommand != null && remoteTargetCommand.getCommandId() != null) {
      builder.setRemoteResultCommandId(remoteTargetCommand.getCommandId());
    }
    if (remoteTargetCommand != null && remoteTargetCommand.getExecutionOutcome() != null) {
      builder.setRemoteTargetCommandExecutionOutcome(remoteTargetCommand.getExecutionOutcome());
    }
    if (remoteTargetCommand != null && remoteTargetCommand.getGameplayResult() != null) {
      builder.setRemoteTargetCommandGameplayResult(remoteTargetCommand.getGameplayResult());
    }
    if (command.getScriptPatchVersion() != null && !command.getScriptPatchVersion().isBlank()) {
      builder.setPublication(
          scriptPatchPublicationLink(command.getTenantId(), command.getScriptPatchVersion()));
    }
    if (command.getPluginId() != null
        && !command.getPluginId().isBlank()
        && command.getPluginVersionId() != null
        && !command.getPluginVersionId().isBlank()) {
      builder.setPluginPublication(
          pluginPublicationLink(
              command.getTenantId(), command.getPluginId(), command.getPluginVersionId()));
    }
    return builder.build();
  }

  private Optional<RuntimeRegionStatus> currentRuntimeOwnership(
      long tenantId, long gameInstanceId) {
    if (tenantId <= 0 || gameInstanceId <= 0) {
      return Optional.empty();
    }
    return runtimeRegionStatusRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
  }

  private RemoteCommandCoordinator resolveRemoteCoordinator(GameplayCommand command) {
    if (remoteCommandCoordinatorRepository == null) {
      return null;
    }
    if (command.getRemoteCoordinatorId() != null && !command.getRemoteCoordinatorId().isBlank()) {
      return remoteCommandCoordinatorRepository
          .findByTenantIdAndCoordinatorId(command.getTenantId(), command.getRemoteCoordinatorId())
          .orElse(null);
    }
    return remoteCommandCoordinatorRepository
        .findByTenantIdAndCommandId(command.getTenantId(), command.getCommandId())
        .orElse(null);
  }

  private RemoteFollowup resolveRemoteFollowup(
      GameplayCommand command, RemoteCommandCoordinator remoteCoordinator) {
    if (remoteFollowupRepository == null) {
      return null;
    }
    String followupId =
        command.getRemoteFollowupId() != null && !command.getRemoteFollowupId().isBlank()
            ? command.getRemoteFollowupId()
            : remoteCoordinator == null ? null : remoteCoordinator.getFollowupId();
    if (followupId == null || followupId.isBlank()) {
      return null;
    }
    return remoteFollowupRepository
        .findByTenantIdAndFollowupId(command.getTenantId(), followupId)
        .orElse(null);
  }

  private RemoteFollowupResult latestRemoteResult(long tenantId, String coordinatorId) {
    java.util.List<RemoteFollowupResult> results =
        remoteFollowupResultRepository.findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
            tenantId, coordinatorId);
    if (results.isEmpty()) {
      return null;
    }
    return results.get(results.size() - 1);
  }

  private GameplayCommand linkedTargetCommand(long tenantId, String followupId) {
    if (gameplayCommandRepository == null || followupId == null || followupId.isBlank()) {
      return null;
    }
    return gameplayCommandRepository
        .findFirstByTenantIdAndRemoteFollowupId(tenantId, followupId)
        .orElse(null);
  }

  private Map<String, RemoteFollowup> followupMap(long tenantId, List<String> followupIds) {
    if (remoteFollowupRepository == null) {
      return Map.of();
    }
    List<String> distinctIds = distinctNonBlank(followupIds);
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteFollowupRepository.findByTenantIdAndFollowupIdIn(tenantId, distinctIds).stream()
        .collect(Collectors.toMap(RemoteFollowup::getFollowupId, Function.identity()));
  }

  private Map<String, RemoteCommandCoordinator> coordinatorByFollowupMap(
      long tenantId, List<String> followupIds) {
    if (remoteCommandCoordinatorRepository == null) {
      return Map.of();
    }
    List<String> distinctIds = distinctNonBlank(followupIds);
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteCommandCoordinatorRepository
        .findByTenantIdAndFollowupIdIn(tenantId, distinctIds)
        .stream()
        .collect(Collectors.toMap(RemoteCommandCoordinator::getFollowupId, Function.identity()));
  }

  private Map<String, RemoteCommandCoordinator> coordinatorMap(
      long tenantId, List<String> coordinatorIds) {
    if (remoteCommandCoordinatorRepository == null) {
      return Map.of();
    }
    List<String> distinctIds = distinctNonBlank(coordinatorIds);
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteCommandCoordinatorRepository
        .findByTenantIdAndCoordinatorIdIn(tenantId, distinctIds)
        .stream()
        .collect(Collectors.toMap(RemoteCommandCoordinator::getCoordinatorId, Function.identity()));
  }

  private Map<String, RemoteFollowupResult> latestResultMap(
      long tenantId, List<String> coordinatorIds) {
    List<String> distinctIds = distinctNonBlank(coordinatorIds);
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return remoteFollowupResultRepository
        .findByTenantIdAndCoordinatorIdInOrderByObservedAtAsc(tenantId, distinctIds)
        .stream()
        .collect(
            Collectors.toMap(
                RemoteFollowupResult::getCoordinatorId,
                Function.identity(),
                (ignored, replacement) -> replacement));
  }

  private Map<String, GameplayCommand> targetCommandMap(long tenantId, List<String> followupIds) {
    if (gameplayCommandRepository == null) {
      return Map.of();
    }
    List<String> distinctIds = distinctNonBlank(followupIds);
    if (distinctIds.isEmpty()) {
      return Map.of();
    }
    return gameplayCommandRepository
        .findByTenantIdAndRemoteFollowupIdIn(tenantId, distinctIds)
        .stream()
        .collect(
            Collectors.toMap(
                GameplayCommand::getRemoteFollowupId,
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

  private long toEpochMillis(Instant instant) {
    return instant == null ? 0L : instant.toEpochMilli();
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

  private record CurrentRuntimeBoundary(
      long gameInstanceId,
      String regionId,
      long regionEpoch,
      PlayableStateScope playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {}

  private record RoutingBundle(String worldSlug, String realmSlug, Long pointerVersion) {}
}
