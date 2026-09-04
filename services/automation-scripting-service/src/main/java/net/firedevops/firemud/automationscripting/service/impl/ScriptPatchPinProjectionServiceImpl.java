package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are internal Spring collaborators")
public class ScriptPatchPinProjectionServiceImpl implements ScriptPatchPinProjectionService {
  private final ScriptPatchPinProjectionRepository repository;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;
  private final ScriptScheduleInstanceService scheduleInstanceService;
  private final ScriptRuntimeProperties runtimeProperties;

  public ScriptPatchPinProjectionServiceImpl(
      ScriptPatchPinProjectionRepository repository,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      @Lazy ScriptPatchInstanceRolloutProjectionService rolloutProjectionService,
      ScriptScheduleInstanceService scheduleInstanceService,
      ScriptRuntimeProperties runtimeProperties) {
    this.repository = repository;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.rolloutProjectionService = rolloutProjectionService;
    this.scheduleInstanceService = scheduleInstanceService;
    this.runtimeProperties = runtimeProperties;
  }

  @Override
  @Transactional
  public PinConvergenceLookup getPinConvergence(String tenantId, String gameInstanceId) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    Instant now = Instant.now();
    Optional<ScriptPatchPinProjection> existing =
        repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    if (existing.isEmpty() || isStale(existing.get(), now)) {
      String regionId =
          existing
              .map(ScriptPatchPinProjection::getRuntimeRegionId)
              .map(ScriptPatchPinProjectionServiceImpl::blankToEmpty)
              .orElse("");
      GetGameInstanceRuntimeStateResponse runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(
              tenantId, gameInstanceId, regionId);
      if (runtime != null
          && !(runtime.hasError() && !runtime.getError().getCode().isBlank())
          && runtime.hasRuntimeState()) {
        GameInstanceRuntimeState runtimeState = runtime.getRuntimeState();
        if (!runtimeStateMatchesScope(tenantId, gameInstanceId, runtimeState)) {
          return new PinConvergenceLookup(
              Optional.empty(),
              "RUNTIME_SCOPE_MISMATCH",
              "GetAutomationPinConvergence failed: runtime_scope_mismatch");
        }
        if (!acceptObservation(existing.orElse(null), runtimeState)) {
          return existing
              .map(value -> new PinConvergenceLookup(Optional.of(toSummary(value, now)), "", ""))
              .orElseGet(
                  () ->
                      new PinConvergenceLookup(
                          Optional.empty(),
                          "INVALID_RUNTIME_PIN_TUPLE",
                          "GetAutomationPinConvergence failed: invalid_runtime_pin_tuple"));
        }
        ScriptPatchPinProjection refreshed =
            saveObservation(
                existing.orElseGet(ScriptPatchPinProjection::new),
                tenantId,
                gameInstanceId,
                runtimeState,
                now);
        scheduleInstanceService.reconcileObservedRuntimeState(
            tenantId, gameInstanceId, runtimeState);
        return new PinConvergenceLookup(Optional.of(toSummary(refreshed, now)), "", "");
      }
      if (existing.isPresent()) {
        return new PinConvergenceLookup(Optional.of(toSummary(existing.get(), now)), "", "");
      }
      if (runtime != null && runtime.hasError() && !runtime.getError().getCode().isBlank()) {
        return new PinConvergenceLookup(
            Optional.empty(), runtime.getError().getCode(), runtime.getError().getMessage());
      }
      return new PinConvergenceLookup(
          Optional.empty(),
          "NOT_FOUND",
          "GetAutomationPinConvergence failed: pin_projection_not_found");
    }
    return new PinConvergenceLookup(Optional.of(toSummary(existing.get(), now)), "", "");
  }

  @Override
  @Transactional
  public void observeRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    if (!runtimeStateMatchesScope(tenantId, gameInstanceId, runtimeState)) {
      return;
    }
    Optional<ScriptPatchPinProjection> existing =
        repository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
    if (!acceptObservation(existing.orElse(null), runtimeState)) {
      return;
    }
    saveObservation(
        existing.orElseGet(ScriptPatchPinProjection::new),
        tenantId,
        gameInstanceId,
        runtimeState,
        Instant.now());
    scheduleInstanceService.reconcileObservedRuntimeState(tenantId, gameInstanceId, runtimeState);
    rolloutProjectionService.refreshForInstance(tenantId, gameInstanceId);
  }

  private static boolean runtimeStateMatchesScope(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState) {
    return runtimeState != null
        && tenantId.equals(runtimeState.getTenantId())
        && gameInstanceId.equals(runtimeState.getGameInstanceId());
  }

  /**
   * Accepts only coherent pin tuples and advances a projection monotonically. The repository's
   * row-version compare-and-set closes the race between this read and the subsequent save.
   */
  private static boolean acceptObservation(
      ScriptPatchPinProjection existing, GameInstanceRuntimeState incoming) {
    if (!coherentPinTuple(
        incoming.getPinnedScriptPatchVersion(),
        incoming.getScriptPinEpoch(),
        incoming.getScriptPatchPinnedControlPlaneRequestId())) {
      return false;
    }
    if (existing == null) {
      return true;
    }
    // V6 initialized retained rows with epoch 0.  Such a row is not an
    // authoritative observation (and may contain only part of the old tuple),
    // so the first coherent runtime observation is allowed to replace it.
    if (isLegacyProjection(existing)) {
      return true;
    }
    if (!coherentPinTuple(
        existing.getObservedPinnedScriptPatchVersion(),
        existing.getScriptPinEpoch(),
        existing.getLastObservedControlPlaneRequestId())) {
      return false;
    }
    if (incoming.getScriptPinEpoch() < existing.getScriptPinEpoch()) {
      return false;
    }
    return incoming.getScriptPinEpoch() > existing.getScriptPinEpoch()
        || samePinTuple(
            existing.getObservedPinnedScriptPatchVersion(),
            existing.getScriptPinEpoch(),
            existing.getLastObservedControlPlaneRequestId(),
            incoming.getPinnedScriptPatchVersion(),
            incoming.getScriptPinEpoch(),
            incoming.getScriptPatchPinnedControlPlaneRequestId());
  }

  private static boolean samePinTuple(
      String existingPatch,
      Long existingEpoch,
      String existingRequestId,
      String incomingPatch,
      Long incomingEpoch,
      String incomingRequestId) {
    return java.util.Objects.equals(existingEpoch, incomingEpoch)
        && blankToEmpty(existingPatch).equals(blankToEmpty(incomingPatch))
        && blankToEmpty(existingRequestId).equals(blankToEmpty(incomingRequestId));
  }

  private static boolean coherentPinTuple(String patchVersion, Long epoch, String requestId) {
    boolean hasPatch = !blankToEmpty(patchVersion).isBlank();
    boolean hasRequestId = !blankToEmpty(requestId).isBlank();
    return epoch == null || epoch == 0L
        ? !hasPatch && !hasRequestId
        : epoch > 0L && hasPatch && hasRequestId;
  }

  private static boolean isLegacyProjection(ScriptPatchPinProjection projection) {
    Long epoch = projection.getScriptPinEpoch();
    return epoch == null || epoch == 0L;
  }

  private ScriptPatchPinProjection saveObservation(
      ScriptPatchPinProjection projection,
      String tenantId,
      String gameInstanceId,
      GameInstanceRuntimeState runtimeState,
      Instant now) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.fromRuntimeState(runtimeState);
    projection.setTenantId(tenantId);
    projection.setGameInstanceId(gameInstanceId);
    projection.setObservedPinnedScriptPatchVersion(runtimeState.getPinnedScriptPatchVersion());
    projection.setScriptPinEpoch(
        runtimeState.getScriptPinEpoch() > 0 ? runtimeState.getScriptPinEpoch() : null);
    projection.setPlayableStateScope(
        normalizePlayableStateScope(runtimeState.getPlayableStateScope()));
    projection.setWorldSlug(routingBundle.worldSlug());
    projection.setRealmSlug(routingBundle.realmSlug());
    projection.setPointerVersion(routingBundle.pointerVersion());
    projection.setRuntimeRegionId(blankToEmpty(runtimeState.getRegionId()));
    projection.setRuntimeRegionEpoch(Math.max(0L, runtimeState.getRegionEpoch()));
    projection.setLastObservedControlPlaneRequestId(
        runtimeState.getScriptPatchPinnedControlPlaneRequestId());
    projection.setObservedAt(
        runtimeState.getScriptPatchPinnedAtMs() > 0
            ? Instant.ofEpochMilli(runtimeState.getScriptPatchPinnedAtMs())
            : now);
    projection.setProjectionRefreshedAt(now);
    return repository.save(projection);
  }

  private PinConvergenceSummary toSummary(ScriptPatchPinProjection projection, Instant now) {
    long projectionAsOfMs = projection.getProjectionRefreshedAt().toEpochMilli();
    long projectionLagMs = Math.max(0L, now.toEpochMilli() - projectionAsOfMs);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            projection.getWorldSlug(), projection.getRealmSlug(), projection.getPointerVersion());
    return new PinConvergenceSummary(
        projection.getTenantId(),
        projection.getGameInstanceId(),
        projection.getObservedPinnedScriptPatchVersion(),
        projection.getScriptPinEpoch() == null ? 0L : projection.getScriptPinEpoch(),
        projection.getLastObservedControlPlaneRequestId(),
        projection.getObservedAt().equals(Instant.EPOCH)
            ? 0L
            : projection.getObservedAt().toEpochMilli(),
        projectionAsOfMs,
        projectionLagMs,
        projectionLagMs >= runtimeProperties.getPinProjectionStaleThresholdMs(),
        blankToEmpty(projection.getRuntimeRegionId()),
        projection.getRuntimeRegionEpoch(),
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion());
  }

  private boolean isStale(ScriptPatchPinProjection projection, Instant now) {
    return Math.max(0L, now.toEpochMilli() - projection.getProjectionRefreshedAt().toEpochMilli())
        >= runtimeProperties.getPinProjectionStaleThresholdMs();
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
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

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }
}
