package net.firedevops.firemud.gamesession.service.impl;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import org.springframework.stereotype.Service;

@Service
final class GameSessionRuntimeControlPlaneReadService {
  private static final List<String> ACTIVE_GAMEPLAY_COMMAND_OUTCOMES =
      List.of("ACCEPTED", "STAGED", "RETRY_QUEUED", "DRAINED");

  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameDesignClient gameDesignClient;

  GameSessionRuntimeControlPlaneReadService(
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameDesignClient gameDesignClient) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.gameDesignClient = gameDesignClient;
  }

  RuntimeOwnershipStatus getRuntimeOwnershipStatus(
      long tenantId, GetRuntimeOwnershipStatusRequest request, long tickDurationMs) {
    return toStatus(findRuntimeOwnershipStatus(request, tenantId), tickDurationMs);
  }

  GameInstanceRuntimeState getGameInstanceRuntimeState(
      long tenantId, GetGameInstanceRuntimeStateRequest request) {
    RuntimeRegionStatus runtimeStatus =
        resolveRuntimeStateOwnership(
            tenantId,
            normalizeBlank(request.getGameInstanceId()),
            normalizeBlank(request.getRegionId()));
    long gameInstanceId = runtimeStatus.getGameInstanceId();
    GameInstance instance = getInstanceOrThrow(gameInstanceId);
    if (instance.getTenantId() != tenantId) {
      throw new IllegalArgumentException("tenant_id does not own game_instance_id");
    }
    CurrentRoutingProjection routingProjection = resolveGameplayRouting(instance);
    return GameInstanceRuntimeState.newBuilder()
        .setTenantId(Long.toString(instance.getTenantId()))
        .setGameInstanceId(Long.toString(instance.getId()))
        .setRuntimeVersionId(instance.getRuntimeVersion())
        .setPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setLaunchDescriptorId(
            instance.getLaunchDescriptorId() == null ? "" : instance.getLaunchDescriptorId())
        .setStatus(instance.getStatus() == null ? "" : instance.getStatus())
        .setVersionId(instance.getVersionId() == null ? "" : Long.toString(instance.getVersionId()))
        .setReleaseBundleId(
            instance.getReleaseBundleId() == null
                ? ""
                : Long.toString(instance.getReleaseBundleId()))
        .setVersionStateEpoch(
            instance.getVersionStateEpoch() == null ? 0L : instance.getVersionStateEpoch())
        .setScriptPatchPinnedAtMs(
            instance.getScriptPatchPinnedAt() == null
                ? 0L
                : instance.getScriptPatchPinnedAt().toEpochMilli())
        .setScriptPatchPinnedBy(
            instance.getScriptPatchPinnedBy() == null ? "" : instance.getScriptPatchPinnedBy())
        .setScriptPatchPinnedReason(
            instance.getScriptPatchPinnedReason() == null
                ? ""
                : instance.getScriptPatchPinnedReason())
        .setScriptPatchPinnedControlPlaneRequestId(
            instance.getScriptPatchPinnedControlPlaneRequestId() == null
                ? ""
                : instance.getScriptPatchPinnedControlPlaneRequestId())
        .setPlayableStateScope(routingProjection.routingBundle().playableStateScope())
        .setWorldSlug(routingProjection.routingBundle().worldSlug())
        .setRealmSlug(routingProjection.routingBundle().realmSlug())
        .setPointerVersion(routingProjection.routingBundle().pointerVersion())
        .setRegionId(normalizeBlank(runtimeStatus.getRegionId()))
        .setRegionEpoch(runtimeStatus.getRegionEpoch())
        .addAllCurrentAdmissionPointers(routingProjection.currentAdmissionPointers())
        .setPublication(
            scriptPatchPublicationLink(instance.getTenantId(), instance.getScriptPatchVersion()))
        .build();
  }

  private RuntimeRegionStatus findRuntimeOwnershipStatus(
      GetRuntimeOwnershipStatusRequest request, long tenantId) {
    if (!request.getRegionId().isBlank()) {
      return runtimeRegionStatusRepository
          .findByTenantIdAndRegionId(tenantId, request.getRegionId())
          .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
    }
    long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
    return runtimeRegionStatusRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
  }

  private RuntimeRegionStatus resolveRuntimeStateOwnership(
      long tenantId, String requestedGameInstanceId, String requestedRegionId) {
    if (!requestedRegionId.isBlank()) {
      RuntimeRegionStatus status =
          runtimeRegionStatusRepository
              .findByTenantIdAndRegionId(tenantId, requestedRegionId)
              .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
      if (!requestedGameInstanceId.isBlank()) {
        long requestedGameInstance = parseGameInstanceId(requestedGameInstanceId);
        if (!Long.valueOf(requestedGameInstance).equals(status.getGameInstanceId())) {
          throw new IllegalArgumentException("region_id does not match game_instance_id");
        }
      }
      return status;
    }
    long gameInstanceId = parseGameInstanceId(requestedGameInstanceId);
    return runtimeRegionStatusRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Runtime ownership not found"));
  }

  private RuntimeOwnershipStatus toStatus(RuntimeRegionStatus status, long tickDurationMs) {
    long pendingGameplayCommandCount =
        gameplayCommandRepository
            .countByTenantIdAndGameInstanceIdAndCompletedAtIsNullAndExecutionOutcomeIn(
                status.getTenantId(), status.getGameInstanceId(), ACTIVE_GAMEPLAY_COMMAND_OUTCOMES);
    long dueRemoteFollowupCount =
        remoteFollowupRepository.countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
            status.getTenantId(),
            status.getRegionId(),
            RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
            status.getLastCommittedTickId() + 1L);
    long oldestDueRemoteFollowupTickId =
        remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                status.getTenantId(),
                status.getRegionId(),
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                status.getLastCommittedTickId() + 1L)
            .map(RemoteFollowup::getDueTickId)
            .orElse(0L);
    long remoteFollowupDrainLagMs =
        oldestDueRemoteFollowupTickId == 0L
            ? 0L
            : Math.max(
                0L,
                (status.getLastCommittedTickId() + 1L - oldestDueRemoteFollowupTickId)
                    * tickDurationMs);
    return RuntimeOwnershipStatus.newBuilder()
        .setTenantId(Long.toString(status.getTenantId()))
        .setGameInstanceId(Long.toString(status.getGameInstanceId()))
        .setRegionId(status.getRegionId() == null ? "" : status.getRegionId())
        .setRegionEpoch(status.getRegionEpoch())
        .setExecutorFence(status.getExecutorFence())
        .setOwnerService(status.getOwnerService())
        .setOwnerInstanceId(status.getOwnerInstanceId())
        .setPaused(status.isPaused())
        .setLastCommittedTickBatchId(
            status.getLastCommittedTickBatchId() == null
                ? ""
                : status.getLastCommittedTickBatchId())
        .setLastCommittedTickId(status.getLastCommittedTickId())
        .setUpdatedAtMs(status.getUpdatedAt() == null ? 0L : status.getUpdatedAt().toEpochMilli())
        .setPendingGameplayCommandCount(pendingGameplayCommandCount)
        .setDueRemoteFollowupCount(dueRemoteFollowupCount)
        .setOldestDueRemoteFollowupTickId(oldestDueRemoteFollowupTickId)
        .setRemoteFollowupDrainLagMs(remoteFollowupDrainLagMs)
        .build();
  }

  private GameInstance getInstanceOrThrow(long gameInstanceId) {
    return gameInstanceRepository
        .findById(gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
  }

  private CurrentRoutingProjection resolveGameplayRouting(GameInstance instance) {
    List<GameplayAdmissionPointerSnapshot> pointers =
        gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
            instance.getTenantId(), instance.getId());
    List<AdmissionPointerControlPlaneEntry> entries =
        pointers.stream().map(this::toControlPlaneEntry).toList();
    GameplayRoutingBundle singularBundle =
        pointers.size() == 1
            ? toGameplayRoutingBundle(pointers.get(0))
            : new GameplayRoutingBundle(
                PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED, "", "", 0L);
    return new CurrentRoutingProjection(singularBundle, entries);
  }

  private GameplayRoutingBundle toGameplayRoutingBundle(GameplayAdmissionPointerSnapshot pointer) {
    return new GameplayRoutingBundle(
        switch (normalizeBlank(pointer.stateScope())) {
          case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
          case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
          default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
        },
        normalizeBlank(pointer.worldSlug()),
        normalizeBlank(pointer.realmSlug()),
        pointer.pointerVersion());
  }

  private AdmissionPointerControlPlaneEntry toControlPlaneEntry(
      GameplayAdmissionPointerSnapshot pointer) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setWorldSlug(normalizeBlank(pointer.worldSlug()))
        .setWorldDisplayName(normalizeBlank(pointer.worldDisplayName()))
        .setRealmSlug(normalizeBlank(pointer.realmSlug()))
        .setRealmDisplayName(normalizeBlank(pointer.realmDisplayName()))
        .setTenantId(Long.toString(pointer.tenantId()))
        .setGameInstanceId(Long.toString(pointer.gameInstanceId()))
        .setPointerVersion(pointer.pointerVersion())
        .setVisible(pointer.visible())
        .setRequiresCharacterSelection(pointer.requiresCharacterSelection())
        .setStateScope(normalizeBlank(pointer.stateScope()))
        .setCharacterCreationPolicy(normalizeBlank(pointer.characterCreationPolicy()))
        .setPublicProductionRealm(pointer.publicProductionRealm())
        .build();
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

  private static long parseGameInstanceId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("game_instance_id is required");
    }
    try {
      return Long.parseLong(gameInstanceId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("game_instance_id must be a number");
    }
  }

  private static String normalizeBlank(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  private record GameplayRoutingBundle(
      PlayableStateScope playableStateScope,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {}

  private record CurrentRoutingProjection(
      GameplayRoutingBundle routingBundle,
      List<AdmissionPointerControlPlaneEntry> currentAdmissionPointers) {}
}
