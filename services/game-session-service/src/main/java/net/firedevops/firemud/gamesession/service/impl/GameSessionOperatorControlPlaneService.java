package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.AutomationScriptingControlPlaneClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.ScriptPinMutationResult;
import net.firedevops.firemud.gamesession.service.ScriptPinTupleCoherence;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
final class GameSessionOperatorControlPlaneService {
  private static final String SCRIPT_PATCH_AUTHORITY_UNAVAILABLE =
      "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE";
  private static final String SCRIPT_PATCH_NOT_PUBLISHED = "SCRIPT_PATCH_NOT_PUBLISHED";
  private static final String SCRIPT_PATCH_NOT_READY = "SCRIPT_PATCH_NOT_READY";

  private final GameInstanceRepository gameInstanceRepository;
  private final TickService tickService;
  private final GameDesignClient gameDesignClient;
  private final AutomationScriptingControlPlaneClient automationScriptingControlPlaneClient;
  private final GameSessionProperties gameSessionProperties;

  @Autowired
  GameSessionOperatorControlPlaneService(
      GameInstanceRepository gameInstanceRepository,
      TickService tickService,
      GameDesignClient gameDesignClient,
      AutomationScriptingControlPlaneClient automationScriptingControlPlaneClient,
      GameSessionProperties gameSessionProperties) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.tickService = tickService;
    this.gameDesignClient = gameDesignClient;
    this.automationScriptingControlPlaneClient = automationScriptingControlPlaneClient;
    this.gameSessionProperties = gameSessionProperties;
  }

  // Compatibility constructor for tests and read-only callers that do not exercise pinning.
  GameSessionOperatorControlPlaneService(
      GameInstanceRepository gameInstanceRepository,
      TickService tickService,
      GameDesignClient gameDesignClient,
      GameSessionProperties gameSessionProperties) {
    this(gameInstanceRepository, tickService, gameDesignClient, null, gameSessionProperties);
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
        .setObservedScriptPinEpoch(
            instance.getScriptPinEpoch() == null ? 0L : instance.getScriptPinEpoch())
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
    requireText(request.getTargetScriptPatchVersion(), "target_script_patch_version", 100);
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id", 128);
    requireText(request.getActorPrincipal(), "actor_principal", 200);
    requireText(request.getReason(), "reason", 500);
    ExpectedCurrentPin expected = requireExpectedCurrentPin(request.getExpectedCurrentPin());
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    ScriptPinTupleCoherence.requireCoherent(
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId());
    String validationError =
        validateTargetPatch(tenantId, instance, request.getTargetScriptPatchVersion(), false);
    if (validationError != null) {
      return setPinResponse(
          recordPinFailure(
              tenantId,
              gameInstanceId,
              "SET",
              request.getTargetScriptPatchVersion(),
              request.getControlPlaneRequestId(),
              request.getActorPrincipal(),
              request.getReason(),
              expected,
              validationError));
    }
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
            expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH
                ? expected.getScriptPinEpoch()
                : null);
    return setPinResponse(result);
  }

  RollbackScriptPatchVersionResponse rollbackScriptPatchVersion(
      long tenantId, long gameInstanceId, RollbackScriptPatchVersionRequest request) {
    requireText(request.getTargetScriptPatchVersion(), "target_script_patch_version", 100);
    requireText(request.getControlPlaneRequestId(), "control_plane_request_id", 128);
    requireText(request.getActorPrincipal(), "actor_principal", 200);
    requireText(request.getReason(), "reason", 500);
    ExpectedCurrentPin expected = requireExpectedCurrentPin(request.getExpectedCurrentPin());
    GameInstance instance = getOwnedInstance(tenantId, gameInstanceId);
    ScriptPinTupleCoherence.requireCoherent(
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId());
    String validationError =
        validateTargetPatch(tenantId, instance, request.getTargetScriptPatchVersion(), true);
    if (validationError != null) {
      return rollbackPinResponse(
          recordPinFailure(
              tenantId,
              gameInstanceId,
              "ROLLBACK",
              request.getTargetScriptPatchVersion(),
              request.getControlPlaneRequestId(),
              request.getActorPrincipal(),
              request.getReason(),
              expected,
              validationError));
    }
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
            expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH
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

  private ExpectedCurrentPin requireExpectedCurrentPin(ExpectedCurrentPin expected) {
    if (expected == null
        || expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_UNSPECIFIED
        || expected.getKind() == ExpectedCurrentPin.Kind.UNRECOGNIZED) {
      throw new IllegalArgumentException("expected_current_pin kind is required");
    }
    if (expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH
        && expected.getScriptPinEpoch() <= 0L) {
      throw new IllegalArgumentException("expected_current_pin script_pin_epoch must be positive");
    }
    if (expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_UNPINNED
        && expected.getScriptPinEpoch() != 0L) {
      throw new IllegalArgumentException(
          "expected_current_pin script_pin_epoch must be absent for EXPECT_UNPINNED");
    }
    if (expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_UNCONDITIONAL
        && expected.getScriptPinEpoch() != 0L) {
      throw new IllegalArgumentException(
          "expected_current_pin script_pin_epoch must be absent for UNCONDITIONAL");
    }
    if (expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_UNCONDITIONAL
        && !SessionContext.getGlobalRoles().contains("platformAdmin")) {
      throw new AdminAuthorizationException("UNCONDITIONAL requires platformAdmin");
    }
    return expected;
  }

  private SetPinnedScriptPatchVersionResponse setPinResponse(ScriptPinMutationResult result) {
    SetPinnedScriptPatchVersionResponse.Builder response =
        SetPinnedScriptPatchVersionResponse.newBuilder()
            .setPreviousScriptPatchVersion(
                result.previousScriptPatchVersion() == null
                    ? ""
                    : result.previousScriptPatchVersion())
            .setPinnedScriptPatchVersion(
                result.resultingScriptPatchVersion() == null
                    ? ""
                    : result.resultingScriptPatchVersion())
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
                result.previousScriptPatchVersion() == null
                    ? ""
                    : result.previousScriptPatchVersion())
            .setPinnedScriptPatchVersion(
                result.resultingScriptPatchVersion() == null
                    ? ""
                    : result.resultingScriptPatchVersion())
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
    if ("SCRIPT_PIN_EPOCH_EXHAUSTED".equals(errorCode)) {
      return "script pin epoch exhausted";
    }
    return errorCode;
  }

  private ScriptPinMutationResult recordPinFailure(
      long tenantId,
      long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason,
      ExpectedCurrentPin expected,
      String errorCode) {
    return gameInstanceRepository.recordScriptPinFailure(
        tenantId,
        gameInstanceId,
        operationKind,
        targetScriptPatchVersion,
        controlPlaneRequestId,
        actorPrincipal,
        reason,
        expected.getKind().name(),
        expected.getKind() == ExpectedCurrentPin.Kind.EXPECTED_CURRENT_PIN_KIND_EXPECT_EPOCH
            ? expected.getScriptPinEpoch()
            : null,
        errorCode);
  }

  /**
   * Validates both owner authorities before the Game Session pin transaction. A null result is a
   * valid target. The compatibility constructor does not provide the owner authority client, so pin
   * mutations must fail closed when it is used.
   */
  private String validateTargetPatch(
      long tenantId, GameInstance instance, String targetScriptPatchVersion, boolean rollback) {
    if (rollback
        && targetScriptPatchVersion.equals(normalizePatch(instance.getScriptPatchVersion()))) {
      return "SCRIPT_PATCH_ROLLBACK_TARGET_CURRENT";
    }
    if (automationScriptingControlPlaneClient == null) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }

    GetPublishedScriptPatchVersionResponse publicationResponse;
    try {
      publicationResponse =
          gameDesignClient == null
              ? null
              : gameDesignClient.getPublishedScriptPatchVersion(tenantId, targetScriptPatchVersion);
    } catch (RuntimeException ex) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }
    if (publicationResponse == null) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }
    if (publicationResponse.hasError() && !publicationResponse.getError().getCode().isBlank()) {
      String code = publicationResponse.getError().getCode();
      return switch (code) {
        case "NOT_FOUND" -> SCRIPT_PATCH_NOT_PUBLISHED;
        case "GAME_DESIGN_UNAVAILABLE" -> SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
        default -> SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
      };
    }
    if (!publicationResponse.hasScriptPatch()) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }
    PublishedScriptPatchVersion published = publicationResponse.getScriptPatch();
    if (!Long.toString(tenantId).equals(published.getTenantId())) {
      return "SCRIPT_PATCH_TENANT_MISMATCH";
    }
    if (!targetScriptPatchVersion.equals(published.getScriptPatchVersion())
        || published.getVersionId() <= 0L
        || published.getBaseVersionId() <= 0L) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }
    if (published.getPublicationState()
        != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED) {
      return SCRIPT_PATCH_NOT_PUBLISHED;
    }

    GetScriptPatchStatusResponse readiness;
    try {
      readiness =
          automationScriptingControlPlaneClient.getScriptPatchStatus(
              tenantId, targetScriptPatchVersion);
    } catch (RuntimeException ex) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }
    if (readiness == null) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }
    if (readiness.hasError() && !readiness.getError().getCode().isBlank()) {
      String code = readiness.getError().getCode();
      return switch (code) {
        case "NOT_FOUND" -> SCRIPT_PATCH_NOT_READY;
        case "AUTOMATION_SCRIPTING_UNAVAILABLE" -> SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
        default -> SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
      };
    }
    if (readiness.getStatus() != ScriptPatchStatus.SCRIPT_PATCH_STATUS_READY) {
      return SCRIPT_PATCH_NOT_READY;
    }
    if (readiness.getBaseVersionId() <= 0L
        || readiness.getBaseVersionId() != published.getBaseVersionId()) {
      return SCRIPT_PATCH_AUTHORITY_UNAVAILABLE;
    }

    Long runtimeVersionId = runtimeVersionId(instance);
    if (runtimeVersionId == null || !runtimeVersionId.equals(published.getBaseVersionId())) {
      return "SCRIPT_PATCH_BASE_VERSION_MISMATCH";
    }
    return null;
  }

  private Long runtimeVersionId(GameInstance instance) {
    if (instance.getVersionId() != null) {
      return instance.getVersionId() > 0L ? instance.getVersionId() : null;
    }
    if (instance.getRuntimeVersion() == null || instance.getRuntimeVersion().isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(instance.getRuntimeVersion());
      return parsed > 0L ? parsed : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String normalizePatch(String value) {
    return value == null || value.isBlank() ? null : value;
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

  private void requireText(String value, String fieldName, int maxLength) {
    requireText(value, fieldName + " is required");
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          fieldName + " must contain at most " + maxLength + " characters");
    }
  }
}
