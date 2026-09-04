package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.ScriptPinMutationResult;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.ExpectedCurrentPin;
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
import net.firedevops.firemud.shared.v1.ErrorDetail;
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
    ScriptPinTupleCoherence.requireCoherent(
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId());
    return GetPinnedScriptPatchVersionResponse.newBuilder()
        .setPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setScriptPinEpoch(instance.getScriptPinEpoch() == null ? 0L : instance.getScriptPinEpoch())
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
    ScriptPinTupleCoherence.requireCoherent(
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId());
    return GetGameSessionPinConvergenceResponse.newBuilder()
        .setTenantId(Long.toString(instance.getTenantId()))
        .setGameInstanceId(Long.toString(instance.getId()))
        .setObservedPinnedScriptPatchVersion(
            instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
        .setObservedScriptPinEpoch(instance.getScriptPinEpoch() == null ? 0L : instance.getScriptPinEpoch())
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
    requireText(request.getTargetScriptPatchVersion(), "target_script_patch_version is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getReason(), "reason is required");
    ExpectedCurrentPin expected = requireExpectedCurrentPin(request.getExpectedCurrentPin());
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    ScriptPinTupleCoherence.requireCoherent(
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId());
    rejectEpochExhaustion(instance);
    ScriptPinMutationResult result =
        gameInstanceRepository.applyScriptPin(
            tenantId,
            gameInstanceId,
            "SET",
            request.getTargetScriptPatchVersion(),
            request.getControlPlaneRequestId(),
            request.getActorPrincipal(),
            request.getReason(),
            expected.getKind().name(),
            expected.getKind() == ExpectedCurrentPin.Kind.EXPECT_EPOCH
                ? expected.getScriptPinEpoch()
                : null);
    return setPinResponse(result);
  }

  RollbackScriptPatchVersionResponse rollbackScriptPatchVersion(
      long tenantId, long gameInstanceId, RollbackScriptPatchVersionRequest request) {
    requireText(request.getTargetScriptPatchVersion(), "target_script_patch_version is required");
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id is required");
    requireText(request.getActorPrincipal(), "actor_principal is required");
    requireText(request.getReason(), "reason is required");
    ExpectedCurrentPin expected = requireExpectedCurrentPin(request.getExpectedCurrentPin());
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    ScriptPinTupleCoherence.requireCoherent(
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId());
    rejectEpochExhaustion(instance);
    ScriptPinMutationResult result =
        gameInstanceRepository.applyScriptPin(
            tenantId,
            gameInstanceId,
            "ROLLBACK",
            request.getTargetScriptPatchVersion(),
            request.getControlPlaneRequestId(),
            request.getActorPrincipal(),
            request.getReason(),
            expected.getKind().name(),
            expected.getKind() == ExpectedCurrentPin.Kind.EXPECT_EPOCH
                ? expected.getScriptPinEpoch()
                : null);
    return rollbackPinResponse(result);
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
    requireText(request.getReason(), "reason is required");
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
    requireText(request.getReason(), "reason is required");
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

  private void rejectEpochExhaustion(GameInstance instance) {
    long currentScriptPinEpoch = instance.getScriptPinEpoch() == null ? 0L : instance.getScriptPinEpoch();
    if (currentScriptPinEpoch == Long.MAX_VALUE) {
      throw new IllegalStateException("script pin epoch exhausted");
    }
  }

  private ExpectedCurrentPin requireExpectedCurrentPin(ExpectedCurrentPin expected) {
    if (expected == null
        || expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_UNSPECIFIED) {
      throw new IllegalArgumentException("expected_current_pin kind is required");
    }
    if (expected.getKind() == ExpectedCurrentPin.Kind.EXPECT_EPOCH
        && expected.getScriptPinEpoch() <= 0L) {
      throw new IllegalArgumentException("expected_current_pin script_pin_epoch must be positive");
    }
    if (expected.getKind() == ExpectedCurrentPin.Kind.UNCONDITIONAL
        && !SessionContext.getGlobalRoles().contains("platformAdmin")) {
      throw new IllegalArgumentException("UNCONDITIONAL requires platformAdmin");
    }
    return expected;
  }

  private SetPinnedScriptPatchVersionResponse setPinResponse(ScriptPinMutationResult result) {
    SetPinnedScriptPatchVersionResponse.Builder response =
        SetPinnedScriptPatchVersionResponse.newBuilder()
            .setPreviousScriptPatchVersion(
                result.previousScriptPatchVersion() == null ? "" : result.previousScriptPatchVersion())
            .setPinnedScriptPatchVersion(
                result.resultingScriptPatchVersion() == null ? "" : result.resultingScriptPatchVersion())
            .setControlPlaneRequestId(result.controlPlaneRequestId());
    if (result.previousScriptPinEpoch() != null) {
      response.setPreviousScriptPinEpoch(result.previousScriptPinEpoch());
    }
    if (result.resultingScriptPinEpoch() != null) {
      response.setScriptPinEpoch(result.resultingScriptPinEpoch());
    }
    if (!result.succeeded()) {
      response.setError(
          ErrorDetail.newBuilder()
              .setCode(result.errorCode())
              .setMessage(pinFailureMessage(result.errorCode()))
              .build());
    }
    return response.build();
  }

  private RollbackScriptPatchVersionResponse rollbackPinResponse(ScriptPinMutationResult result) {
    RollbackScriptPatchVersionResponse.Builder response =
        RollbackScriptPatchVersionResponse.newBuilder()
            .setPreviousScriptPatchVersion(
                result.previousScriptPatchVersion() == null ? "" : result.previousScriptPatchVersion())
            .setPinnedScriptPatchVersion(
                result.resultingScriptPatchVersion() == null ? "" : result.resultingScriptPatchVersion())
            .setControlPlaneRequestId(result.controlPlaneRequestId());
    if (result.previousScriptPinEpoch() != null) {
      response.setPreviousScriptPinEpoch(result.previousScriptPinEpoch());
    }
    if (result.resultingScriptPinEpoch() != null) {
      response.setScriptPinEpoch(result.resultingScriptPinEpoch());
    }
    if (!result.succeeded()) {
      response.setError(
          ErrorDetail.newBuilder()
              .setCode(result.errorCode())
              .setMessage(pinFailureMessage(result.errorCode()))
              .build());
    }
    return response.build();
  }

  private String pinFailureMessage(String errorCode) {
    if ("SCRIPT_PIN_EXPECTATION_FAILED".equals(errorCode)) {
      return "expected current script pin does not match authoritative current tuple";
    }
    return errorCode;
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
