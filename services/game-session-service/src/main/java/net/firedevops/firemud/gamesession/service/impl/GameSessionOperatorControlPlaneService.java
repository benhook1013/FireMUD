package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionResponse;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import org.springframework.stereotype.Service;

@Service
final class GameSessionOperatorControlPlaneService {
  private final GameInstanceRepository gameInstanceRepository;
  private final TickService tickService;
  private final GameDesignClient gameDesignClient;
  private final GameSessionProperties gameSessionProperties;

  GameSessionOperatorControlPlaneService(
      GameInstanceRepository gameInstanceRepository,
      TickService tickService,
      GameDesignClient gameDesignClient,
      GameSessionProperties gameSessionProperties) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.tickService = tickService;
    this.gameDesignClient = gameDesignClient;
    this.gameSessionProperties = gameSessionProperties;
  }

  GetPinnedScriptPatchVersionResponse getPinnedScriptPatchVersion(
      long tenantId, long gameInstanceId) {
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    return GetPinnedScriptPatchVersionResponse.newBuilder()
        .setPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setPinnedAtMs(toEpochMillis(instance.getScriptPatchPinnedAt()))
        .setPinnedBy(
            instance.getScriptPatchPinnedBy() == null ? "" : instance.getScriptPatchPinnedBy())
        .setControlPlaneRequestId(
            instance.getScriptPatchPinnedControlPlaneRequestId() == null
                ? ""
                : instance.getScriptPatchPinnedControlPlaneRequestId())
        .setPublication(
            scriptPatchPublicationLink(instance.getTenantId(), instance.getScriptPatchVersion()))
        .build();
  }

  GetGameSessionPinConvergenceResponse getGameSessionPinConvergence(
      long tenantId, long gameInstanceId) {
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    return GetGameSessionPinConvergenceResponse.newBuilder()
        .setTenantId(Long.toString(instance.getTenantId()))
        .setGameInstanceId(Long.toString(instance.getId()))
        .setObservedPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setLastObservedControlPlaneRequestId(
            instance.getScriptPatchPinnedControlPlaneRequestId() == null
                ? ""
                : instance.getScriptPatchPinnedControlPlaneRequestId())
        .setObservedAtMs(toEpochMillis(instance.getScriptPatchPinnedAt()))
        .setIsStale(isPinConvergenceStale(instance.getScriptPatchPinnedAt()))
        .setPublication(
            scriptPatchPublicationLink(instance.getTenantId(), instance.getScriptPatchVersion()))
        .build();
  }

  SetPinnedScriptPatchVersionResponse setPinnedScriptPatchVersion(
      long tenantId, long gameInstanceId, SetPinnedScriptPatchVersionRequest request) {
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    String previous = instance.getScriptPatchVersion();
    updatePinnedPatch(
        instance,
        request.getTargetScriptPatchVersion(),
        request.getActorPrincipal(),
        request.getReason(),
        request.getControlPlaneRequestId());
    return SetPinnedScriptPatchVersionResponse.newBuilder()
        .setPreviousScriptPatchVersion(previous == null ? "" : previous)
        .setPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setControlPlaneRequestId(request.getControlPlaneRequestId())
        .build();
  }

  RollbackScriptPatchVersionResponse rollbackScriptPatchVersion(
      long tenantId, long gameInstanceId, RollbackScriptPatchVersionRequest request) {
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    String previous = instance.getScriptPatchVersion();
    updatePinnedPatch(
        instance,
        request.getTargetScriptPatchVersion(),
        request.getActorPrincipal(),
        request.getReason(),
        request.getControlPlaneRequestId());
    return RollbackScriptPatchVersionResponse.newBuilder()
        .setPreviousScriptPatchVersion(previous == null ? "" : previous)
        .setPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setControlPlaneRequestId(request.getControlPlaneRequestId())
        .build();
  }

  PurgeQueuedTickCommandsForScriptPatchResponse purgeQueuedTickCommandsForScriptPatch(
      long tenantId, long gameInstanceId, PurgeQueuedTickCommandsForScriptPatchRequest request) {
    requireText(request.getScriptPatchVersion(), "script_patch_version is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getReason(), "reason is required");
    getOwnedInstance(tenantId, gameInstanceId);
    long purged =
        tickService.purgeQueuedAutomationCommandsForScriptPatch(
            tenantId,
            gameInstanceId,
            normalizeBlank(request.getRegionId()),
            request.getScriptPatchVersion(),
            request.getReason());
    return PurgeQueuedTickCommandsForScriptPatchResponse.newBuilder()
        .setPurgedCount(purged)
        .build();
  }

  PurgeQueuedTickCommandsForPluginVersionResponse purgeQueuedTickCommandsForPluginVersion(
      long tenantId, long gameInstanceId, PurgeQueuedTickCommandsForPluginVersionRequest request) {
    requireText(request.getPluginId(), "plugin_id is required");
    requireText(request.getPluginVersionId(), "plugin_version_id is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getReason(), "reason is required");
    getOwnedInstance(tenantId, gameInstanceId);
    long purged =
        tickService.purgeQueuedAutomationCommandsForPluginVersion(
            tenantId,
            gameInstanceId,
            normalizeBlank(request.getRegionId()),
            request.getPluginId(),
            request.getPluginVersionId(),
            request.getReason());
    return PurgeQueuedTickCommandsForPluginVersionResponse.newBuilder()
        .setPurgedCount(purged)
        .build();
  }

  PauseTicksForScopeResponse pauseTicksForScope(long tenantId, PauseTicksForScopeRequest request) {
    if (!request.getRegionId().isBlank()) {
      throw new IllegalArgumentException("region_id is not supported; set it empty");
    }
    long gameInstanceId =
        ControlPlaneRequestParser.parsePositiveLong(
            request.getGameInstanceId(), "game_instance_id");
    getOwnedInstance(tenantId, gameInstanceId);
    tickService.pauseTicksForGameInstance(gameInstanceId, request.getReason());
    return PauseTicksForScopeResponse.newBuilder().setSuccess(true).build();
  }

  ResumeTicksForScopeResponse resumeTicksForScope(
      long tenantId, ResumeTicksForScopeRequest request) {
    if (!request.getRegionId().isBlank()) {
      throw new IllegalArgumentException("region_id is not supported; set it empty");
    }
    long gameInstanceId =
        ControlPlaneRequestParser.parsePositiveLong(
            request.getGameInstanceId(), "game_instance_id");
    getOwnedInstance(tenantId, gameInstanceId);
    tickService.resumeTicksForGameInstance(gameInstanceId, request.getReason());
    return ResumeTicksForScopeResponse.newBuilder().setSuccess(true).build();
  }

  private void updatePinnedPatch(
      GameInstance instance,
      String targetScriptPatchVersion,
      String actorPrincipal,
      String reason,
      String controlPlaneRequestId) {
    instance.setScriptPatchVersion(targetScriptPatchVersion);
    instance.setScriptPatchPinnedAt(Instant.now());
    instance.setScriptPatchPinnedBy(actorPrincipal);
    instance.setScriptPatchPinnedReason(reason);
    instance.setScriptPatchPinnedControlPlaneRequestId(controlPlaneRequestId);
    gameInstanceRepository.save(instance);
  }

  private GameInstance getOwnedInstance(long tenantId, long gameInstanceId) {
    GameInstance instance =
        gameInstanceRepository
            .findById(gameInstanceId)
            .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
    if (instance.getTenantId() != tenantId) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }
    return instance;
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

  private long toEpochMillis(Instant instant) {
    return instant == null ? 0L : instant.toEpochMilli();
  }

  private boolean isPinConvergenceStale(Instant pinnedAt) {
    if (pinnedAt == null) {
      return true;
    }
    long ageMs = Instant.now().toEpochMilli() - pinnedAt.toEpochMilli();
    return ageMs > gameSessionProperties.getPinConvergenceStaleThresholdMs();
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }
}
