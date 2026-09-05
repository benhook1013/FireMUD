package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptSchedulerProperties;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleInstanceRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained internally.")
public class ScriptScheduleInstanceServiceImpl implements ScriptScheduleInstanceService {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(ScriptScheduleInstanceServiceImpl.class);
  private static final String UNIT_MILLISECONDS = "MILLISECONDS";
  private static final String UNIT_TICKS = "TICKS";
  private static final String STATUS_READY = "READY";
  private static final String STATUS_PENDING_RUNTIME_PROGRESS = "PENDING_RUNTIME_PROGRESS";
  private static final String STATUS_FENCED = "FENCED";
  private static final String ADMISSION_MODE_NORMAL = "NORMAL";
  private static final String ADMISSION_MODE_PAUSED_FOR_ROLLBACK = "PAUSED_FOR_ROLLBACK";
  private static final String DEFAULT_SCHEMA_VERSION = "v1";
  private static final String SOURCE_SERVICE = "automation-scripting-service";
  private static final boolean SCHEDULER_IS_DRY_RUN = false;
  private static final String SCHEDULER_TRIGGER_MODE = TriggerMode.TRIGGER_MODE_CATCH_UP.name();
  private static final String FINAL_STAGE_ADMISSION = "ADMISSION";
  private static final String FINAL_OUTCOME_CANCELED = "canceled";
  private static final String REASON_CATCH_UP_TRUNCATED = "catch_up_truncated";
  private static final String REASON_RUNTIME_SCOPE_CHANGED = "runtime_scope_changed";
  private static final String REASON_PLAYABLE_STATE_SCOPE_CHANGED = "playable_state_scope_changed";
  private static final String REASON_SCRIPT_PATCH_MISMATCH = "script_patch_mismatch";
  private static final String REASON_SCRIPT_PIN_EPOCH_MISMATCH = "script_pin_epoch_mismatch";
  private static final String REASON_SCRIPT_PIN_REQUEST_ID_REQUIRED =
      "script_pin_control_plane_request_id_required";
  private static final String REASON_SCRIPT_PIN_REQUEST_ID_MISMATCH =
      "script_pin_control_plane_request_id_mismatch";
  private static final String REASON_PLUGIN_BINDING_MISMATCH = "plugin_binding_mismatch";
  private static final String REASON_MATERIALIZATION_NOT_READY = "materialization_not_ready";
  private static final String REASON_ROUTING_BUNDLE_CHANGED = "routing_bundle_changed";
  private static final String REASON_INVALID_CADENCE = "invalid_schedule_cadence";
  private static final String REASON_DUE_TICK_OVERFLOW = "schedule_due_tick_overflow";
  private static final String REASON_DUE_TIME_OVERFLOW = "schedule_due_time_overflow";
  private static final String METRIC_TIMER_CATCHUP_TRUNCATED =
      "automation_script_timer_catchup_truncated_total";
  private static final String METRIC_TIMER_RUNTIME_FENCE_DROPPED =
      "automation_script_timer_runtime_fence_dropped_total";

  private final ScriptScheduleDefinitionRepository scheduleDefinitionRepository;
  private final ScriptScheduleInstanceRepository scheduleInstanceRepository;
  private final ScriptPatchPinProjectionRepository pinProjectionRepository;
  private final PluginRuntimeStateRepository pluginRuntimeStateRepository;
  private final ScriptEventBindingRepository bindingRepository;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository eventAuditRepository;
  private final AutomationQueueService automationQueueService;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptSchedulerProperties schedulerProperties;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;

  public ScriptScheduleInstanceServiceImpl(
      ScriptScheduleDefinitionRepository scheduleDefinitionRepository,
      ScriptScheduleInstanceRepository scheduleInstanceRepository,
      ScriptPatchPinProjectionRepository pinProjectionRepository,
      PluginRuntimeStateRepository pluginRuntimeStateRepository,
      ScriptEventBindingRepository bindingRepository,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository eventAuditRepository,
      AutomationQueueService automationQueueService,
      AutomationAdmissionStateService automationAdmissionStateService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ScriptSchedulerProperties schedulerProperties,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper) {
    this.scheduleDefinitionRepository = scheduleDefinitionRepository;
    this.scheduleInstanceRepository = scheduleInstanceRepository;
    this.pinProjectionRepository = pinProjectionRepository;
    this.pluginRuntimeStateRepository = pluginRuntimeStateRepository;
    this.bindingRepository = bindingRepository;
    this.workItemRepository = workItemRepository;
    this.eventAuditRepository = eventAuditRepository;
    this.automationQueueService = automationQueueService;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.schedulerProperties = schedulerProperties;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void reconcileObservedRuntimeState(
      String tenantId,
      String gameInstanceId,
      GameInstanceRuntimeState runtimeState,
      Instant nonPinTransitionSeed,
      String transitionPluginId) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    if (transitionPluginId == null) {
      throw new IllegalArgumentException("transition_plugin_id must not be null");
    }
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    if (runtimeState != null
        && (!tenantId.equals(runtimeState.getTenantId())
            || !gameInstanceId.equals(runtimeState.getGameInstanceId()))) {
      // A runtime response for another scope is not authority for this reconciliation. Preserve
      // the existing projection until the owner returns an exact-scope response.
      return;
    }
    if (runtimeState == null) {
      scheduleInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
      return;
    }
    if (runtimeState.getPinnedScriptPatchVersion().isBlank()) {
      if (runtimeState.getScriptPinEpoch() <= 0
          && blankToEmpty(runtimeState.getScriptPatchPinnedControlPlaneRequestId()).isBlank()) {
        scheduleInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
      } else {
        markRetainedSchedulesPending(tenantId, gameInstanceId);
      }
      return;
    }
    if (runtimeState.getRegionId().isBlank()
        || runtimeState.getRegionEpoch() <= 0
        || runtimeState.getScriptPinEpoch() <= 0
        || blankToEmpty(runtimeState.getScriptPatchPinnedControlPlaneRequestId()).isBlank()
        || !hasExplicitPlayableStateScope(runtimeState.getPlayableStateScope())
        || !RoutingBundleSupport.fromRuntimeState(runtimeState).isPresent()) {
      // Schedule activation is scoped to the authoritative runtime timeline. A partial
      // Game Session response must not activate rows against an unknown runtime scope or routing
      // bundle. Keep the prior materialization until complete evidence arrives; deleting it would
      // also remove schedules owned by unrelated plugins.
      markRetainedSchedulesPending(tenantId, gameInstanceId);
      return;
    }

    String scriptPatchVersion = runtimeState.getPinnedScriptPatchVersion();
    List<ScriptScheduleDefinition> definitions =
        scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                tenantKey, scriptPatchVersion);
    Map<String, List<ScriptEventBinding>> bindingsByScriptEvent =
        bindingsByScriptEvent(tenantKey, scriptPatchVersion);
    Map<String, PluginRuntimeState> activePluginStates =
        activePluginStates(
            tenantId,
            gameInstanceId,
            new AutomationRuntimeScopeSupport.RuntimeScope(
                blankToEmpty(runtimeState.getRegionId()), runtimeState.getRegionEpoch()));
    Map<String, String> activePluginVersions = activePluginVersions(activePluginStates);
    List<ScriptScheduleInstance> existing =
        scheduleInstanceRepository
            .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                tenantId, gameInstanceId);
    Map<String, ScriptScheduleInstance> existingByKey = new HashMap<>();
    for (ScriptScheduleInstance instance : existing) {
      existingByKey.put(
          scopeKey(
              instance.getPlayableStateScope(),
              instance.getPluginId(),
              instance.getPluginVersionId(),
              instance.getScheduleDefinitionId(),
              instance.getTargetScopeType(),
              instance.getTargetScopeId()),
          instance);
    }

    Instant now = Instant.now();
    Instant pinObservedAt =
        runtimeState.getScriptPatchPinnedAtMs() > 0
            ? Instant.ofEpochMilli(runtimeState.getScriptPatchPinnedAtMs())
            : now;
    Set<String> desiredKeys = new HashSet<>();
    List<ScriptScheduleInstance> upserts = new ArrayList<>();
    for (ScriptScheduleDefinition definition : definitions) {
      if (!shouldMaterialize(definition, activePluginVersions)) {
        continue;
      }
      for (ScriptEventBinding binding : matchingBindings(bindingsByScriptEvent, definition)) {
        String key =
            scopeKey(
                normalizePlayableStateScope(runtimeState.getPlayableStateScope()),
                definition.getPluginId(),
                definition.getPluginVersionId(),
                definition.getScheduleDefinitionId(),
                binding.getTargetScopeType(),
                binding.getTargetScopeId());
        desiredKeys.add(key);
        ScriptScheduleInstance instance =
            existingByKey.getOrDefault(key, new ScriptScheduleInstance());
        try {
          populateInstance(
              instance,
              tenantId,
              gameInstanceId,
              definition,
              binding,
              runtimeState,
              activePluginStates,
              pinObservedAt,
              shouldApplyTransitionSeed(definition, nonPinTransitionSeed, transitionPluginId)
                  ? nonPinTransitionSeed
                  : null,
              now);
        } catch (IllegalArgumentException ex) {
          if (!REASON_DUE_TIME_OVERFLOW.equals(ex.getMessage())) {
            throw ex;
          }
          fenceMaterialization(instance, now);
        }
        upserts.add(instance);
      }
    }

    List<ScriptScheduleInstance> deletes =
        existing.stream()
            .filter(
                instance ->
                    !desiredKeys.contains(
                        scopeKey(
                            instance.getPlayableStateScope(),
                            instance.getPluginId(),
                            instance.getPluginVersionId(),
                            instance.getScheduleDefinitionId(),
                            instance.getTargetScopeType(),
                            instance.getTargetScopeId())))
            .toList();
    if (!deletes.isEmpty()) {
      scheduleInstanceRepository.deleteAll(deletes);
    }
    if (!upserts.isEmpty()) {
      scheduleInstanceRepository.saveAll(upserts);
    }
  }

  private static boolean shouldApplyTransitionSeed(
      ScriptScheduleDefinition definition, Instant transitionSeed, String transitionPluginId) {
    return transitionSeed != null
        && (blankToEmpty(transitionPluginId).isBlank()
            || blankToEmpty(definition.getPluginId()).equals(blankToEmpty(transitionPluginId)));
  }

  @Override
  @Transactional
  public void reconcilePinnedPatchInstances(String tenantId, String scriptPatchVersion) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    for (ScriptPatchPinProjection projection :
        pinProjectionRepository.findByTenantIdAndObservedPinnedScriptPatchVersion(
            tenantId, scriptPatchVersion)) {
      RoutingBundleSupport.RoutingBundle routingBundle =
          RoutingBundleSupport.normalize(
              projection.getWorldSlug(), projection.getRealmSlug(), projection.getPointerVersion());
      long scriptPinEpoch =
          projection.getScriptPinEpoch() == null ? 0L : projection.getScriptPinEpoch();
      GameInstanceRuntimeState.Builder runtimeState =
          GameInstanceRuntimeState.newBuilder()
              .setTenantId(projection.getTenantId())
              .setGameInstanceId(projection.getGameInstanceId())
              .setPinnedScriptPatchVersion(projection.getObservedPinnedScriptPatchVersion())
              .setScriptPinEpoch(scriptPinEpoch)
              .setRegionId(blankToEmpty(projection.getRuntimeRegionId()))
              .setRegionEpoch(projection.getRuntimeRegionEpoch())
              .setPlayableStateScope(toPlayableStateScope(projection.getPlayableStateScope()))
              .setWorldSlug(routingBundle.worldSlug())
              .setRealmSlug(routingBundle.realmSlug())
              .setPointerVersion(routingBundle.parsedPointerVersion())
              .setScriptPatchPinnedControlPlaneRequestId(
                  projection.getLastObservedControlPlaneRequestId())
              .setScriptPatchPinnedAtMs(projection.getObservedAt().toEpochMilli());
      if (runtimeState.getRegionId().isBlank()
          || runtimeState.getRegionEpoch() <= 0
          || runtimeState.getScriptPinEpoch() <= 0) {
        // A pin projection without a complete runtime scope is not authoritative enough to
        // reconcile or delete schedule rows. The next complete projection will retry it.
        markRetainedSchedulesPending(tenantId, projection.getGameInstanceId());
        continue;
      }
      if (routingBundle.isPresent()) {
        runtimeState.addCurrentAdmissionPointers(
            AdmissionPointerControlPlaneEntry.newBuilder()
                .setWorldSlug(routingBundle.worldSlug())
                .setRealmSlug(routingBundle.realmSlug())
                .setTenantId(runtimeState.getTenantId())
                .setGameInstanceId(runtimeState.getGameInstanceId())
                .setPointerVersion(routingBundle.parsedPointerVersion())
                .setStateScope(
                    runtimeState
                        .getPlayableStateScope()
                        .name()
                        .replace("PLAYABLE_STATE_SCOPE_", ""))
                .build());
      }
      reconcileObservedRuntimeState(tenantId, projection.getGameInstanceId(), runtimeState.build());
    }
  }

  private void markRetainedSchedulesPending(String tenantId, String gameInstanceId) {
    List<ScriptScheduleInstance> pending = new ArrayList<>();
    for (ScriptScheduleInstance instance :
        safeInstances(
            scheduleInstanceRepository
                .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                    tenantId, gameInstanceId))) {
      if (STATUS_PENDING_RUNTIME_PROGRESS.equals(instance.getMaterializationStatus())
          || STATUS_FENCED.equals(instance.getMaterializationStatus())) {
        continue;
      }
      instance.setMaterializationStatus(STATUS_PENDING_RUNTIME_PROGRESS);
      instance.setUpdatedAt(Instant.now());
      pending.add(instance);
    }
    if (!pending.isEmpty()) {
      scheduleInstanceRepository.saveAll(pending);
    }
  }

  @Override
  @Transactional
  public RuntimeTickProgressResult observeRuntimeTickProgress(
      RuntimeTickProgressObservation observation) {
    if (observation == null) {
      throw new IllegalArgumentException("runtime_tick_progress_observation_required");
    }
    requireText(observation.tenantId(), "tenant_id");
    requireText(observation.gameInstanceId(), "game_instance_id");
    requireText(observation.regionId(), "region_id");
    if (observation.regionEpoch() <= 0) {
      throw new IllegalArgumentException("region_epoch must be positive");
    }
    if (observation.tickId() < 0) {
      throw new IllegalArgumentException("tick_id must be non-negative");
    }
    Instant observedAt =
        observation.observedAtMs() > 0
            ? Instant.ofEpochMilli(observation.observedAtMs())
            : Instant.now();
    Instant now = Instant.now();
    AutomationAdmissionStateService.AdmissionStateSummary admissionState =
        automationAdmissionStateService.getState(
            observation.tenantId(), observation.gameInstanceId(), observation.regionId());
    Map<AdmissionStateCacheKey, Optional<AutomationAdmissionStateService.AdmissionStateSummary>>
        admissionStateCache = new HashMap<>();
    admissionStateCache.put(
        new AdmissionStateCacheKey(
            observation.tenantId(), observation.gameInstanceId(), observation.regionId()),
        Optional.ofNullable(admissionState));
    if (!usableObservationAdmissionState(admissionState, observation)) {
      // Leave both tick and wall-clock due points untouched until a usable admission state is
      // available.
      return new RuntimeTickProgressResult(0, 0, 0);
    }
    if (ADMISSION_MODE_PAUSED_FOR_ROLLBACK.equals(admissionState.mode())) {
      // Leave both tick and wall-clock due points untouched. The next observation after a
      // resume must be able to admit the same candidate under the resumed epoch.
      return new RuntimeTickProgressResult(0, 0, 0);
    }
    List<ScriptScheduleInstance> updates = new ArrayList<>();
    int maxFirings = schedulerProperties.getMaxCatchUpFiringsPerObservation();
    if (maxFirings <= 0) {
      throw new IllegalArgumentException("max_catch_up_firings_per_observation must be positive");
    }
    int perScheduleCandidateLimit;
    try {
      perScheduleCandidateLimit = Math.addExact(maxFirings, 1);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("max_catch_up_firings_per_observation is too large", ex);
    }
    List<TimerFiringCandidate> candidates = new ArrayList<>();
    List<TimerFiringCandidate> suppressedCandidates = new ArrayList<>();
    GetGameInstanceRuntimeStateResponse observedRuntimeState = null;
    Map<String, Optional<PluginRuntimeState>> pluginStateCache = new HashMap<>();
    List<ScriptScheduleInstance> tickInstances =
        safeInstances(
            scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
                observation.tenantId(), observation.gameInstanceId(), UNIT_TICKS));
    List<ScriptScheduleInstance> wallClockInstances =
        safeInstances(
            scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
                observation.tenantId(), observation.gameInstanceId(), UNIT_MILLISECONDS));
    List<ScriptScheduleInstance> staleTickInstances =
        tickInstances.stream()
            .filter(instance -> isObservationStale(instance, observation))
            .toList();
    List<ScriptScheduleInstance> staleWallClockInstances =
        wallClockInstances.stream()
            .filter(instance -> isObservationStale(instance, observation))
            .toList();
    int totalInstances = tickInstances.size() + wallClockInstances.size();
    int staleInstances = staleTickInstances.size() + staleWallClockInstances.size();
    if (staleInstances == totalInstances && staleInstances > 0) {
      ScriptScheduleInstance firstStale =
          !staleTickInstances.isEmpty()
              ? staleTickInstances.getFirst()
              : staleWallClockInstances.getFirst();
      throw new IllegalArgumentException(observationStaleReason(firstStale, observation));
    }
    tickInstances =
        tickInstances.stream()
            .filter(instance -> !isObservationStale(instance, observation))
            .toList();
    wallClockInstances =
        wallClockInstances.stream()
            .filter(instance -> !isObservationStale(instance, observation))
            .toList();
    int fired = 0;
    for (ScriptScheduleInstance instance : tickInstances) {
      if (!hasCompleteScriptPinTuple(instance)) {
        fenceMaterialization(instance, now);
        updates.add(instance);
        continue;
      }
      TickAdvanceResult advance;
      try {
        advance =
            advanceRuntimeProgress(
                instance, observation, observedAt, now, perScheduleCandidateLimit);
      } catch (IllegalArgumentException ex) {
        String reason = ex.getMessage();
        if (!REASON_DUE_TICK_OVERFLOW.equals(reason) && !REASON_INVALID_CADENCE.equals(reason)) {
          throw ex;
        }
        fenceOverflow(
            instance,
            TimerFiringCandidate.tick(
                instance,
                instance.getNextDueTickId() == null
                    ? observation.tickId()
                    : instance.getNextDueTickId(),
                observation.regionId(),
                observation.regionEpoch()),
            reason,
            now);
        updates.add(instance);
        continue;
      }
      candidates.addAll(
          advance.fireDueTicks().stream()
              .map(dueTick -> TimerFiringCandidate.tick(instance, dueTick))
              .toList());
      suppressedCandidates.addAll(advance.suppressedDueTicks());
      if (advance.changed()) {
        updates.add(instance);
      }
    }
    for (ScriptScheduleInstance instance : wallClockInstances) {
      if (!hasCompleteScriptPinTuple(instance)) {
        fenceMaterialization(instance, now);
        updates.add(instance);
        continue;
      }
      WallClockAdvanceResult advance;
      try {
        advance = advanceWallClockProgress(instance, observation, observedAt, now);
      } catch (IllegalArgumentException ex) {
        String reason = ex.getMessage();
        if (!REASON_DUE_TIME_OVERFLOW.equals(reason) && !REASON_INVALID_CADENCE.equals(reason)) {
          throw ex;
        }
        fenceOverflow(
            instance,
            TimerFiringCandidate.wallClock(
                instance,
                instance.getNextDueAt() == null ? observedAt : instance.getNextDueAt(),
                observation.regionId(),
                observation.regionEpoch()),
            reason,
            now);
        updates.add(instance);
        continue;
      }
      if (advance.fireDueAt() != null) {
        candidates.add(
            TimerFiringCandidate.wallClock(
                instance, advance.fireDueAt(), observation.regionId(), observation.regionEpoch()));
      }
      if (advance.suppressedCandidate() != null) {
        suppressedCandidates.add(advance.suppressedCandidate());
      }
      if (advance.changed()) {
        updates.add(instance);
      }
    }
    List<TimerFiringCandidate> selectedCandidates = roundRobinCandidates(candidates, maxFirings);
    if (!selectedCandidates.isEmpty()) {
      try {
        observedRuntimeState =
            gameSessionControlPlaneClient.getGameInstanceRuntimeState(
                observation.tenantId(), observation.gameInstanceId());
      } catch (RuntimeException ex) {
        LOGGER.warn(
            "Game Session runtime-state lookup failed for tenantId={} gameInstanceId={}; retaining timer progress and due state",
            observation.tenantId(),
            observation.gameInstanceId(),
            ex);
      }
    }
    Set<String> selectedIdentities =
        selectedCandidates.stream()
            .map(TimerFiringCandidate::durableIdentity)
            .collect(java.util.stream.Collectors.toSet());
    List<TimerFiringCandidate> truncatedCandidates =
        candidates.stream()
            .filter(candidate -> !selectedIdentities.contains(candidate.durableIdentity()))
            .toList();
    recordSkippedCandidates(suppressedCandidates, REASON_RUNTIME_SCOPE_CHANGED, now);
    boolean authorityUnavailable = false;
    for (TimerFiringCandidate candidate : selectedCandidates) {
      TimerEmissionResult emissionResult =
          emitTimerWorkItem(
              candidate, now, observedRuntimeState, pluginStateCache, admissionStateCache);
      if (emissionResult == TimerEmissionResult.EMITTED) {
        fired++;
      }
      authorityUnavailable |= emissionResult == TimerEmissionResult.AUTHORITY_UNAVAILABLE;
      if (candidate.wallClock()
          && candidate.instance().getNextDueAt() == null
          && updates.stream().noneMatch(instance -> instance == candidate.instance())) {
        updates.add(candidate.instance());
      }
    }
    if (authorityUnavailable || !runtimeAuthorityAvailable(observedRuntimeState)) {
      truncatedCandidates.forEach(
          candidate -> restoreDueCandidate(candidate.instance(), candidate));
    } else {
      recordSkippedCandidates(truncatedCandidates, REASON_CATCH_UP_TRUNCATED, now);
    }
    int truncated =
        authorityUnavailable || !runtimeAuthorityAvailable(observedRuntimeState)
            ? 0
            : Math.max(0, candidates.size() - selectedCandidates.size());
    if (!updates.isEmpty()) {
      scheduleInstanceRepository.saveAll(updates);
    }
    return new RuntimeTickProgressResult(updates.size(), fired, truncated);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ScheduleInstanceSummary> listInstances(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    int boundedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
    List<ScriptScheduleInstance> instances =
        scriptPatchVersion == null || scriptPatchVersion.isBlank()
            ? scheduleInstanceRepository
                .findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                    tenantId, gameInstanceId)
            : scheduleInstanceRepository
                .findByTenantIdAndGameInstanceIdAndScriptPatchVersionOrderByUpdatedAtDescScheduleDefinitionIdAsc(
                    tenantId, gameInstanceId, scriptPatchVersion);
    return instances.stream()
        .sorted(
            Comparator.comparing(ScriptScheduleInstance::getUpdatedAt)
                .reversed()
                .thenComparing(ScriptScheduleInstance::getScheduleDefinitionId))
        .limit(boundedLimit)
        .map(this::toSummary)
        .map(summary -> withPublication(tenantId, summary))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TimerAuditEventSummary> listTimerAuditEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
      String scriptId,
      String eventType,
      String finalReason,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    requireText(tenantId, "tenant_id");
    if (scriptPinEpoch < 0) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    int boundedLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 500);
    String normalizedTenant = tenantId;
    String normalizedInstance = blankToEmpty(gameInstanceId);
    String normalizedPatch = blankToEmpty(scriptPatchVersion);
    String normalizedScript = blankToEmpty(scriptId);
    String normalizedEvent = blankToEmpty(eventType);
    String normalizedReason = blankToEmpty(finalReason);
    Instant changedAfter = changedAfterMs <= 0 ? null : Instant.ofEpochMilli(changedAfterMs);
    Instant changedBefore = changedBeforeMs <= 0 ? null : Instant.ofEpochMilli(changedBeforeMs);
    org.springframework.data.domain.PageRequest page =
        org.springframework.data.domain.PageRequest.of(0, boundedLimit);
    boolean hasPinFilter =
        scriptPinEpoch > 0 || !blankToEmpty(scriptPinControlPlaneRequestId).isBlank();
    List<ScriptEventAudit> audits =
        !hasPinFilter
            ? eventAuditRepository.findTimerAuditEvents(
                normalizedTenant,
                normalizedInstance,
                normalizedPatch,
                normalizedScript,
                normalizedEvent,
                normalizedReason,
                changedAfter,
                changedBefore,
                page)
            : eventAuditRepository.findTimerAuditEvents(
                normalizedTenant,
                normalizedInstance,
                normalizedPatch,
                scriptPinEpoch,
                scriptPinControlPlaneRequestId,
                normalizedScript,
                normalizedEvent,
                normalizedReason,
                changedAfter,
                changedBefore,
                page);
    return audits.stream()
        .map(ScriptScheduleInstanceServiceImpl::toTimerAuditSummary)
        .map(summary -> withPublication(tenantId, summary))
        .toList();
  }

  private Map<String, PluginRuntimeState> activePluginStates(
      String tenantId,
      String gameInstanceId,
      AutomationRuntimeScopeSupport.RuntimeScope runtimeScope) {
    Map<String, PluginRuntimeState> active = new HashMap<>();
    for (PluginRuntimeState state :
        pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)) {
      if (!PluginState.PLUGIN_STATE_ENABLED.name().equals(state.getPluginState())) {
        continue;
      }
      if (!AutomationRuntimeScopeSupport.matches(state, runtimeScope)) {
        continue;
      }
      String pluginId = blankToEmpty(state.getPluginId());
      String activePluginVersionId = blankToEmpty(state.getActivePluginVersionId());
      if (!pluginId.isBlank() && !activePluginVersionId.isBlank()) {
        active.put(pluginId, state);
      }
    }
    return active;
  }

  private static Map<String, String> activePluginVersions(
      Map<String, PluginRuntimeState> activePluginStates) {
    Map<String, String> activeVersions = new HashMap<>();
    for (Map.Entry<String, PluginRuntimeState> entry : activePluginStates.entrySet()) {
      String activePluginVersionId = blankToEmpty(entry.getValue().getActivePluginVersionId());
      if (!activePluginVersionId.isBlank()) {
        activeVersions.put(entry.getKey(), activePluginVersionId);
      }
    }
    return activeVersions;
  }

  private static boolean shouldMaterialize(
      ScriptScheduleDefinition definition, Map<String, String> activePluginVersions) {
    String pluginId = blankToEmpty(definition.getPluginId());
    String pluginVersionId = blankToEmpty(definition.getPluginVersionId());
    if (pluginId.isBlank() && pluginVersionId.isBlank()) {
      return true;
    }
    if (pluginId.isBlank() || pluginVersionId.isBlank()) {
      return false;
    }
    return pluginVersionId.equals(activePluginVersions.get(pluginId));
  }

  private Map<String, List<ScriptEventBinding>> bindingsByScriptEvent(
      long tenantId, String scriptPatchVersion) {
    Map<String, List<ScriptEventBinding>> bindings = new HashMap<>();
    for (ScriptEventBinding binding :
        bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                tenantId, scriptPatchVersion)) {
      if (!binding.isEnabled()) {
        continue;
      }
      bindings
          .computeIfAbsent(
              bindingKey(binding.getScriptId(), binding.getEventType()), key -> new ArrayList<>())
          .add(binding);
    }
    return bindings;
  }

  private static List<ScriptEventBinding> matchingBindings(
      Map<String, List<ScriptEventBinding>> bindingsByScriptEvent,
      ScriptScheduleDefinition definition) {
    return bindingsByScriptEvent.getOrDefault(
        bindingKey(definition.getScriptId(), definition.getEventType()), List.of());
  }

  private void populateInstance(
      ScriptScheduleInstance instance,
      String tenantId,
      String gameInstanceId,
      ScriptScheduleDefinition definition,
      ScriptEventBinding binding,
      GameInstanceRuntimeState runtimeState,
      Map<String, PluginRuntimeState> activePluginStates,
      Instant pinObservedAt,
      Instant nonPinTransitionSeed,
      Instant now) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.fromRuntimeState(runtimeState);
    boolean existingRow = instance.getId() != null;
    boolean sameRuntimeGeneration =
        existingRow && sameRuntimeGeneration(instance, definition, runtimeState);
    boolean sameScheduleConfiguration =
        existingRow && sameScheduleConfiguration(instance, definition, binding);
    instance.setTenantId(tenantId);
    instance.setGameInstanceId(gameInstanceId);
    instance.setScriptPatchVersion(definition.getScriptPatchVersion());
    long scriptPinEpoch = runtimeState.getScriptPinEpoch();
    instance.setScriptPinEpoch(scriptPinEpoch);
    instance.setScriptId(definition.getScriptId());
    instance.setPlayableStateScope(
        normalizePlayableStateScope(runtimeState.getPlayableStateScope()));
    instance.setWorldSlug(routingBundle.worldSlug());
    instance.setRealmSlug(routingBundle.realmSlug());
    instance.setPointerVersion(routingBundle.pointerVersion());
    instance.setPluginId(blankToEmpty(definition.getPluginId()));
    instance.setPluginVersionId(blankToEmpty(definition.getPluginVersionId()));
    if (!instance.getPluginId().isBlank()) {
      PluginRuntimeState pluginState = activePluginStates.get(instance.getPluginId());
      if (pluginState != null
          && instance
              .getPluginVersionId()
              .equals(blankToEmpty(pluginState.getActivePluginVersionId()))) {
        instance.setPluginActivationEpoch(pluginState.getPluginActivationEpoch());
        instance.setLifecycleRevision(pluginState.getLifecycleRevision());
      } else {
        instance.setPluginActivationEpoch(0L);
        instance.setLifecycleRevision(0L);
      }
    } else {
      instance.setPluginActivationEpoch(0L);
      instance.setLifecycleRevision(0L);
    }
    instance.setEventType(definition.getEventType());
    instance.setScheduleDefinitionId(definition.getScheduleDefinitionId());
    instance.setScheduleKind(definition.getScheduleKind());
    instance.setCadenceValue(definition.getCadenceValue());
    instance.setCadenceUnit(definition.getCadenceUnit());
    instance.setPriorityTag(definition.getPriorityTag());
    instance.setTargetScopeType(blankToEmpty(binding.getTargetScopeType()));
    instance.setTargetScopeId(blankToEmpty(binding.getTargetScopeId()));
    instance.setBindingPriority(binding.getPriority());
    instance.setRequiresExclusiveEvent(binding.isRequiresExclusiveEvent());
    instance.setObservedRuntimeVersionId(blankToEmpty(runtimeState.getRuntimeVersionId()));
    instance.setLastObservedControlPlaneRequestId(
        scriptPinEpoch > 0
            ? blankToEmpty(runtimeState.getScriptPatchPinnedControlPlaneRequestId())
            : "");
    instance.setScheduleMetadataJson(definition.getScheduleMetadataJson());
    instance.setScheduleSemanticsHash(definition.getScheduleSemanticsHash());
    instance.setPinObservedAt(pinObservedAt);
    if (instance.getId() == null) {
      instance.setMaterializedAt(now);
    }
    if (UNIT_MILLISECONDS.equals(definition.getCadenceUnit())) {
      instance.setMaterializationStatus(STATUS_READY);
      boolean compatibleExistingRow =
          nonPinTransitionSeed == null && sameRuntimeGeneration && sameScheduleConfiguration;
      if (!compatibleExistingRow) {
        Instant seed = nonPinTransitionSeed != null ? nonPinTransitionSeed : pinObservedAt;
        try {
          instance.setNextDueAt(seed.plusMillis(definition.getCadenceValue()));
        } catch (DateTimeException | ArithmeticException ex) {
          throw new IllegalArgumentException(REASON_DUE_TIME_OVERFLOW, ex);
        }
        instance.setRuntimeRegionId("");
        instance.setRuntimeRegionEpoch(null);
        instance.setLastObservedTickId(null);
        instance.setLastRuntimeProgressObservedAt(null);
      }
      instance.setNextDueTickId(null);
    } else {
      boolean compatibleExistingRow =
          nonPinTransitionSeed == null && sameRuntimeGeneration && sameScheduleConfiguration;
      if (!compatibleExistingRow) {
        instance.setMaterializationStatus(STATUS_PENDING_RUNTIME_PROGRESS);
        instance.setNextDueAt(null);
        instance.setNextDueTickId(null);
        instance.setRuntimeRegionId("");
        instance.setRuntimeRegionEpoch(null);
        instance.setLastObservedTickId(null);
        instance.setLastRuntimeProgressObservedAt(null);
      } else if (hasRetainedMaterializationEvidence(instance)) {
        instance.setMaterializationStatus(STATUS_READY);
      }
    }
    instance.setUpdatedAt(now);
  }

  private static boolean sameRuntimeGeneration(
      ScriptScheduleInstance instance,
      ScriptScheduleDefinition definition,
      GameInstanceRuntimeState runtimeState) {
    // The durable observed runtime tuple is the generation boundary. A semantics hash is
    // diagnostic only and cannot infer continuity across a new pin/reset with identical text.
    return Objects.equals(instance.getScriptPatchVersion(), definition.getScriptPatchVersion())
        && instance.getScriptPinEpoch() > 0
        && instance.getScriptPinEpoch() == runtimeState.getScriptPinEpoch()
        && Objects.equals(
            blankToEmpty(instance.getObservedRuntimeVersionId()),
            blankToEmpty(runtimeState.getRuntimeVersionId()))
        && Objects.equals(
            blankToEmpty(instance.getLastObservedControlPlaneRequestId()),
            blankToEmpty(runtimeState.getScriptPatchPinnedControlPlaneRequestId()))
        && sameRuntimeScope(instance, runtimeState)
        && !STATUS_FENCED.equals(instance.getMaterializationStatus())
        && (!STATUS_PENDING_RUNTIME_PROGRESS.equals(instance.getMaterializationStatus())
            || hasRetainedMaterializationEvidence(instance));
  }

  private static boolean hasRetainedMaterializationEvidence(ScriptScheduleInstance instance) {
    if (instance.getNextDueAt() != null || instance.getNextDueTickId() != null) {
      return true;
    }
    return "TIMER".equals(instance.getScheduleKind())
        && UNIT_MILLISECONDS.equals(instance.getCadenceUnit())
        && instance.getLastObservedTickId() != null
        && !blankToEmpty(instance.getRuntimeRegionId()).isBlank()
        && instance.getRuntimeRegionEpoch() != null
        && instance.getRuntimeRegionEpoch() > 0;
  }

  private static boolean sameRuntimeScope(
      ScriptScheduleInstance instance, GameInstanceRuntimeState runtimeState) {
    String instanceRegionId = blankToEmpty(instance.getRuntimeRegionId());
    Long instanceRegionEpoch = instance.getRuntimeRegionEpoch();
    if (instanceRegionId.isBlank() || instanceRegionEpoch == null || instanceRegionEpoch <= 0) {
      return true;
    }
    return Objects.equals(instanceRegionId, blankToEmpty(runtimeState.getRegionId()))
        && instanceRegionEpoch == runtimeState.getRegionEpoch();
  }

  private static boolean sameScheduleConfiguration(
      ScriptScheduleInstance instance,
      ScriptScheduleDefinition definition,
      ScriptEventBinding binding) {
    return Objects.equals(instance.getScheduleKind(), definition.getScheduleKind())
        && instance.getCadenceValue() == definition.getCadenceValue()
        && Objects.equals(instance.getCadenceUnit(), definition.getCadenceUnit())
        && Objects.equals(instance.getPriorityTag(), definition.getPriorityTag())
        && instance.getBindingPriority() == binding.getPriority()
        && instance.isRequiresExclusiveEvent() == binding.isRequiresExclusiveEvent();
  }

  private TickAdvanceResult advanceRuntimeProgress(
      ScriptScheduleInstance instance,
      RuntimeTickProgressObservation observation,
      Instant observedAt,
      Instant now,
      int perScheduleCandidateLimit) {
    boolean priorRuntimeKnown =
        !blankToEmpty(instance.getRuntimeRegionId()).isBlank()
            && instance.getRuntimeRegionEpoch() != null
            && instance.getRuntimeRegionEpoch() > 0;
    boolean runtimeScopeChanged =
        priorRuntimeKnown
            && (!observation.regionId().equals(blankToEmpty(instance.getRuntimeRegionId()))
                || instance.getRuntimeRegionEpoch() != observation.regionEpoch());
    Long currentDueTick = instance.getNextDueTickId();
    if (STATUS_FENCED.equals(instance.getMaterializationStatus())) {
      return new TickAdvanceResult(false, List.of(), List.of());
    }
    if (!STATUS_READY.equals(instance.getMaterializationStatus())
        && hasRetainedMaterializationEvidence(instance)) {
      // A partial reconciliation deliberately retained this exact due occurrence. Runtime
      // progress cannot prove that the materialization generation is still authoritative; only
      // a complete reconciliation may restore READY and allow the occurrence to emit.
      return new TickAdvanceResult(false, List.of(), List.of());
    }
    if (!STATUS_READY.equals(instance.getMaterializationStatus())
        && currentDueTick == null
        && priorRuntimeKnown) {
      instance.setRuntimeRegionId(observation.regionId());
      instance.setRuntimeRegionEpoch(observation.regionEpoch());
      instance.setLastObservedTickId(observation.tickId());
      instance.setLastRuntimeProgressObservedAt(observedAt);
      instance.setUpdatedAt(now);
      return new TickAdvanceResult(true, List.of(), List.of());
    }
    List<TimerFiringCandidate> suppressedDueTicks =
        STATUS_READY.equals(instance.getMaterializationStatus())
                && runtimeScopeChanged
                && currentDueTick != null
                && currentDueTick <= observation.tickId()
            ? dueTicks(
                    currentDueTick,
                    observation.tickId(),
                    instance.getCadenceValue(),
                    perScheduleCandidateLimit)
                .stream()
                .map(
                    dueTick ->
                        TimerFiringCandidate.suppressedTick(
                            instance,
                            dueTick,
                            blankToEmpty(instance.getRuntimeRegionId()),
                            instance.getRuntimeRegionEpoch()))
                .toList()
            : List.of();
    List<Long> fireDueTicks =
        STATUS_READY.equals(instance.getMaterializationStatus())
                && !runtimeScopeChanged
                && currentDueTick != null
                && currentDueTick <= observation.tickId()
            ? dueTicks(
                currentDueTick,
                observation.tickId(),
                instance.getCadenceValue(),
                perScheduleCandidateLimit)
            : List.of();
    long nextDueTick =
        runtimeScopeChanged || currentDueTick == null || currentDueTick <= observation.tickId()
            ? nextFutureDueTick(
                observation.tickId(),
                runtimeScopeChanged ? null : currentDueTick,
                instance.getCadenceValue())
            : currentDueTick;
    boolean changed =
        runtimeScopeChanged
            || !STATUS_READY.equals(instance.getMaterializationStatus())
            || currentDueTick == null
            || currentDueTick != nextDueTick
            || instance.getLastObservedTickId() == null
            || instance.getLastObservedTickId() != observation.tickId();
    if (!changed) {
      return new TickAdvanceResult(false, fireDueTicks, suppressedDueTicks);
    }
    instance.setMaterializationStatus(STATUS_READY);
    instance.setRuntimeRegionId(observation.regionId());
    instance.setRuntimeRegionEpoch(observation.regionEpoch());
    instance.setLastObservedTickId(observation.tickId());
    instance.setLastRuntimeProgressObservedAt(observedAt);
    instance.setNextDueTickId(nextDueTick);
    instance.setNextDueAt(null);
    instance.setUpdatedAt(now);
    return new TickAdvanceResult(true, fireDueTicks, suppressedDueTicks);
  }

  private WallClockAdvanceResult advanceWallClockProgress(
      ScriptScheduleInstance instance,
      RuntimeTickProgressObservation observation,
      Instant observedAt,
      Instant now) {
    boolean priorRuntimeKnown =
        !blankToEmpty(instance.getRuntimeRegionId()).isBlank()
            && instance.getRuntimeRegionEpoch() != null
            && instance.getRuntimeRegionEpoch() > 0;
    boolean runtimeScopeChanged =
        priorRuntimeKnown
            && (!observation.regionId().equals(blankToEmpty(instance.getRuntimeRegionId()))
                || instance.getRuntimeRegionEpoch() != observation.regionEpoch());
    Instant currentDueAt = instance.getNextDueAt();
    if (STATUS_FENCED.equals(instance.getMaterializationStatus())) {
      return new WallClockAdvanceResult(false, null, null);
    }
    if (!STATUS_READY.equals(instance.getMaterializationStatus())
        && hasRetainedMaterializationEvidence(instance)) {
      // See the tick path above: keep a retained wall-clock due point pending until complete
      // materialization evidence arrives.
      return new WallClockAdvanceResult(false, null, null);
    }
    TimerFiringCandidate suppressedCandidate =
        STATUS_READY.equals(instance.getMaterializationStatus())
                && runtimeScopeChanged
                && currentDueAt != null
                && !currentDueAt.isAfter(observedAt)
            ? TimerFiringCandidate.suppressedWallClock(
                instance,
                currentDueAt,
                blankToEmpty(instance.getRuntimeRegionId()),
                instance.getRuntimeRegionEpoch())
            : null;
    Instant fireDueAt =
        STATUS_READY.equals(instance.getMaterializationStatus())
                && !runtimeScopeChanged
                && currentDueAt != null
                && !currentDueAt.isAfter(observedAt)
                && !blankToEmpty(observation.regionId()).isBlank()
                && observation.regionEpoch() > 0
            ? currentDueAt
            : null;
    boolean changed =
        runtimeScopeChanged
            || currentDueAt == null
            || instance.getLastObservedTickId() == null
            || instance.getLastObservedTickId() != observation.tickId()
            || !STATUS_READY.equals(instance.getMaterializationStatus());
    if (!changed) {
      return new WallClockAdvanceResult(false, fireDueAt, suppressedCandidate);
    }
    if (runtimeScopeChanged || currentDueAt == null) {
      try {
        instance.setNextDueAt(observedAt.plusMillis(instance.getCadenceValue()));
      } catch (DateTimeException | ArithmeticException ex) {
        throw new IllegalArgumentException(REASON_DUE_TIME_OVERFLOW, ex);
      }
    }
    instance.setMaterializationStatus(
        instance.getNextDueAt() == null ? STATUS_PENDING_RUNTIME_PROGRESS : STATUS_READY);
    instance.setRuntimeRegionId(observation.regionId());
    instance.setRuntimeRegionEpoch(observation.regionEpoch());
    instance.setLastObservedTickId(observation.tickId());
    instance.setLastRuntimeProgressObservedAt(observedAt);
    instance.setUpdatedAt(now);
    return new WallClockAdvanceResult(true, fireDueAt, suppressedCandidate);
  }

  private void recordSkippedCandidates(
      List<TimerFiringCandidate> candidates, String reason, Instant now) {
    if (candidates.isEmpty()) {
      return;
    }
    List<TimerFiringCandidate> newlyRecorded = new ArrayList<>();
    for (TimerFiringCandidate candidate : candidates) {
      if (persistSkippedAudit(candidate, reason, now)) {
        newlyRecorded.add(candidate);
      }
    }
    if (!newlyRecorded.isEmpty()) {
      incrementTimerMetricAfterCommit(metricNameForReason(reason), newlyRecorded, reason);
    }
  }

  private String metricNameForReason(String reason) {
    return switch (reason) {
      case REASON_CATCH_UP_TRUNCATED -> METRIC_TIMER_CATCHUP_TRUNCATED;
      case REASON_RUNTIME_SCOPE_CHANGED, REASON_PLAYABLE_STATE_SCOPE_CHANGED ->
          METRIC_TIMER_RUNTIME_FENCE_DROPPED;
      default -> throw new IllegalArgumentException("Unknown timer skip reason: " + reason);
    };
  }

  private void incrementTimerMetric(
      String metricName, List<TimerFiringCandidate> candidates, String reason) {
    String metricReason = metricReasonFor(metricName, reason);
    for (TimerFiringCandidate candidate : candidates) {
      ScriptScheduleInstance instance = candidate.instance();
      io.micrometer.core.instrument.Counter.Builder builder =
          io.micrometer.core.instrument.Counter.builder(metricName)
              .tag("service", SOURCE_SERVICE)
              .tag("scope", "game_instance")
              .tag("script_kind", scriptKindFor(instance))
              .tag("event_class", eventClassFor(instance.getEventType()))
              .tag("reason", metricReason);
      builder.register(meterRegistry).increment();
    }
  }

  private static String metricReasonFor(String metricName, String reason) {
    return switch (metricName) {
      case METRIC_TIMER_CATCHUP_TRUNCATED -> {
        if (!REASON_CATCH_UP_TRUNCATED.equals(reason)) {
          throw new IllegalArgumentException("Unknown timer catch-up reason: " + reason);
        }
        yield "resume_window_cap";
      }
      case METRIC_TIMER_RUNTIME_FENCE_DROPPED ->
          switch (reason) {
            case REASON_RUNTIME_SCOPE_CHANGED, REASON_PLAYABLE_STATE_SCOPE_CHANGED -> reason;
            default ->
                throw new IllegalArgumentException("Unknown timer runtime-fence reason: " + reason);
          };
      default -> throw new IllegalArgumentException("Unknown timer metric: " + metricName);
    };
  }

  private static String scriptKindFor(ScriptScheduleInstance instance) {
    if (!blankToEmpty(instance.getPluginId()).isBlank()) {
      return "PLUGIN";
    }
    if (!blankToEmpty(instance.getScriptId()).isBlank()) {
      return "SCRIPT";
    }
    return "unknown";
  }

  private static String eventClassFor(String eventType) {
    return switch (blankToEmpty(eventType)) {
      case "onCommand" -> "command";
      case "onSpawn" -> "spawn";
      case "onEnterRegion" -> "enter_region";
      case "onLeaveRegion" -> "leave_region";
      case "onInterval" -> "interval";
      case "onTimerExpire" -> "timer_expire";
      case "onLoad" -> "load";
      default -> "unknown";
    };
  }

  private void incrementTimerMetricAfterCommit(
      String metricName, List<TimerFiringCandidate> candidates, String reason) {
    runAfterCommit(() -> incrementTimerMetric(metricName, candidates, reason));
  }

  private static void runAfterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  private boolean persistSkippedAudit(
      TimerFiringCandidate candidate, String finalReason, Instant now) {
    ScriptScheduleInstance instance = candidate.instance();
    String entityId = targetEntityId(instance);
    String scriptEventId = timerScriptEventId(candidate);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            instance.getWorldSlug(), instance.getRealmSlug(), instance.getPointerVersion());
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(instance.getTenantId());
    audit.setGameInstanceId(instance.getGameInstanceId());
    audit.setRegionId(candidate.regionId());
    audit.setRegionEpoch(candidate.regionEpoch());
    audit.setEntityId(entityId);
    audit.setPlayableStateScope(blankToEmpty(instance.getPlayableStateScope()));
    audit.setWorldSlug(routingBundle.worldSlug());
    audit.setRealmSlug(routingBundle.realmSlug());
    audit.setPointerVersion(routingBundle.pointerVersion());
    audit.setScriptId(instance.getScriptId());
    audit.setPluginId(blankToEmpty(instance.getPluginId()));
    audit.setPluginVersionId(blankToEmpty(instance.getPluginVersionId()));
    audit.setEventType(instance.getEventType());
    audit.setEventSchemaVersion(DEFAULT_SCHEMA_VERSION);
    audit.setScriptPatchVersion(instance.getScriptPatchVersion());
    audit.setScriptPinEpoch(candidate.scriptPinEpoch());
    audit.setScriptPinControlPlaneRequestId(candidate.scriptPinControlPlaneRequestId());
    audit.setScriptEventId(scriptEventId);
    audit.setDryRun(SCHEDULER_IS_DRY_RUN);
    audit.setSourceService(SOURCE_SERVICE);
    audit.setTriggerMode(SCHEDULER_TRIGGER_MODE);
    audit.setSourceKind("SCHEDULE_TIMER");
    audit.setSourceState("SCHEDULE_DROPPED");
    audit.setSourceOrdinal(candidate.dueOrderValue());
    audit.setSourceDueTickId(candidate.wallClock() ? null : candidate.dueTickId());
    audit.setSourceDueAtMs(candidate.wallClock() ? candidate.dueAt().toEpochMilli() : null);
    audit.setFinalStage(FINAL_STAGE_ADMISSION);
    audit.setFinalOutcome(FINAL_OUTCOME_CANCELED);
    audit.setFinalReason(finalReason);
    audit.setCreatedAt(now);
    audit.setUpdatedAt(now);
    return eventAuditRepository.insertIfAbsentByHandlerIdentity(audit).inserted();
  }

  private List<TimerFiringCandidate> roundRobinCandidates(
      List<TimerFiringCandidate> candidates, int maxFirings) {
    if (candidates.isEmpty() || maxFirings <= 0) {
      return List.of();
    }
    Map<String, List<TimerFiringCandidate>> bySchedule = new java.util.LinkedHashMap<>();
    candidates.stream()
        .sorted(timerCandidateComparator())
        .forEach(
            candidate ->
                bySchedule
                    .computeIfAbsent(
                        scheduleKey(candidate.instance()), ignored -> new ArrayList<>())
                    .add(candidate));
    List<TimerFiringCandidate> selected = new ArrayList<>();
    int pass = 0;
    while (selected.size() < maxFirings) {
      boolean added = false;
      for (List<TimerFiringCandidate> scheduleCandidates : bySchedule.values()) {
        if (pass < scheduleCandidates.size()) {
          selected.add(scheduleCandidates.get(pass));
          added = true;
          if (selected.size() >= maxFirings) {
            return List.copyOf(selected);
          }
        }
      }
      if (!added) {
        break;
      }
      pass++;
    }
    return List.copyOf(selected);
  }

  private Comparator<TimerFiringCandidate> timerCandidateComparator() {
    return Comparator.comparingInt(
            (TimerFiringCandidate candidate) -> candidate.wallClock() ? 1 : 0)
        .thenComparingLong((TimerFiringCandidate candidate) -> candidate.dueOrderValue())
        .thenComparingInt(candidate -> priorityRank(candidate.instance().getPriorityTag()))
        .thenComparing(candidate -> scheduleKey(candidate.instance()));
  }

  private static int priorityRank(String priorityTag) {
    return switch (blankToEmpty(priorityTag)) {
      case "high" -> 0;
      case "normal" -> 1;
      default -> 2;
    };
  }

  private static List<Long> dueTicks(
      long firstDueTick, long observedTickId, long cadence, int candidateLimit) {
    requireValidCadence(cadence);
    if (firstDueTick < 0 || observedTickId < 0 || candidateLimit <= 0) {
      throw new IllegalArgumentException(REASON_INVALID_CADENCE);
    }
    List<Long> dueTicks = new ArrayList<>();
    long dueTick = firstDueTick;
    while (dueTick <= observedTickId && dueTicks.size() < candidateLimit) {
      dueTicks.add(dueTick);
      if (dueTick > Long.MAX_VALUE - cadence) {
        break;
      }
      dueTick += cadence;
    }
    return List.copyOf(dueTicks);
  }

  private TimerEmissionResult emitTimerWorkItem(
      TimerFiringCandidate candidate,
      Instant now,
      GetGameInstanceRuntimeStateResponse observedRuntimeState,
      Map<String, Optional<PluginRuntimeState>> pluginStateCache,
      Map<AdmissionStateCacheKey, Optional<AutomationAdmissionStateService.AdmissionStateSummary>>
          admissionStateCache) {
    ScriptScheduleInstance instance = candidate.instance();
    String entityId = targetEntityId(instance);
    String scriptEventId = timerScriptEventId(candidate);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            instance.getWorldSlug(), instance.getRealmSlug(), instance.getPointerVersion());
    AutomationAdmissionStateService.AdmissionStateSummary admissionState =
        admissionStateCache
            .computeIfAbsent(
                new AdmissionStateCacheKey(
                    instance.getTenantId(), instance.getGameInstanceId(), candidate.regionId()),
                region ->
                    Optional.ofNullable(
                        automationAdmissionStateService.getState(
                            instance.getTenantId(),
                            instance.getGameInstanceId(),
                            region.regionId())))
            .orElse(null);
    if (admissionState == null || !usableAdmissionState(admissionState, instance, candidate)) {
      restoreDueCandidate(instance, candidate);
      return TimerEmissionResult.AUTHORITY_UNAVAILABLE;
    }
    if (ADMISSION_MODE_PAUSED_FOR_ROLLBACK.equals(admissionState.mode())) {
      restoreDueCandidate(instance, candidate);
      return TimerEmissionResult.AUTHORITY_UNAVAILABLE;
    }
    MaterializationEligibility eligibility =
        STATUS_READY.equals(instance.getMaterializationStatus())
            ? currentMaterializationEligibility(
                instance, candidate, observedRuntimeState, pluginStateCache)
            : MaterializationEligibility.proven(REASON_MATERIALIZATION_NOT_READY);
    if (eligibility.kind() == MaterializationEligibility.Kind.AUTHORITY_UNAVAILABLE) {
      restoreDueCandidate(instance, candidate);
      return TimerEmissionResult.AUTHORITY_UNAVAILABLE;
    }
    if (eligibility.kind() == MaterializationEligibility.Kind.PROVEN_INELIGIBLE) {
      fenceIneligibleCandidate(instance, candidate, eligibility.reason(), now);
      return TimerEmissionResult.NOT_EMITTED;
    }
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId(instance.getTenantId());
    item.setGameInstanceId(instance.getGameInstanceId());
    item.setRegionId(candidate.regionId());
    item.setRegionEpoch(candidate.regionEpoch());
    item.setEntityId(entityId);
    item.setPlayableStateScope(blankToEmpty(instance.getPlayableStateScope()));
    item.setWorldSlug(routingBundle.worldSlug());
    item.setRealmSlug(routingBundle.realmSlug());
    item.setPointerVersion(routingBundle.pointerVersion());
    item.setScriptId(instance.getScriptId());
    item.setPluginId(blankToEmpty(instance.getPluginId()));
    item.setPluginVersionId(blankToEmpty(instance.getPluginVersionId()));
    item.setEventType(instance.getEventType());
    item.setEventSchemaVersion(DEFAULT_SCHEMA_VERSION);
    item.setQuotaClass(ScriptQuotaClasses.STANDARD_RUNTIME);
    item.setScriptPatchVersion(instance.getScriptPatchVersion());
    item.setScriptPinEpoch(candidate.scriptPinEpoch());
    item.setScriptPinControlPlaneRequestId(candidate.scriptPinControlPlaneRequestId());
    item.setScriptEventId(scriptEventId);
    item.setDryRun(SCHEDULER_IS_DRY_RUN);
    item.setSourceService(SOURCE_SERVICE);
    item.setTriggerMode(SCHEDULER_TRIGGER_MODE);
    item.setSourceKind("SCHEDULE_TIMER");
    item.setSourceState("SCHEDULE_DUE_CLAIMED");
    item.setSourceOrdinal(candidate.dueOrderValue());
    item.setSourceDueTickId(candidate.wallClock() ? null : candidate.dueTickId());
    item.setSourceDueAtMs(candidate.wallClock() ? candidate.dueAt().toEpochMilli() : null);
    item.setPriorityTag(instance.getPriorityTag());
    item.setReadSnapshotToken(timerReadSnapshotToken(candidate));
    item.setPayloadJson(timerPayload(candidate));
    item.setAdmissionEpoch(admissionState.admissionEpoch());
    item.setCreatedAt(now);
    item.setUpdatedAt(now);
    ScriptWorkItemRepository.IdempotentInsertResult insertResult =
        workItemRepository.insertIfAbsentByTriggerIdentity(item);
    if (!insertResult.inserted()) {
      if (candidate.wallClock()) {
        settleWallClockCandidate(instance, now);
      }
      return TimerEmissionResult.NOT_EMITTED;
    }
    ScriptWorkItem saved = insertResult.workItem();
    persistTimerAudit(candidate, saved, now);
    AutomationQueuePublicationSupport.enqueueAfterCommit(automationQueueService, saved, LOGGER);
    if (candidate.wallClock()) {
      settleWallClockCandidate(instance, now);
    }
    return TimerEmissionResult.EMITTED;
  }

  private enum TimerEmissionResult {
    EMITTED,
    NOT_EMITTED,
    AUTHORITY_UNAVAILABLE
  }

  private static boolean runtimeAuthorityAvailable(
      GetGameInstanceRuntimeStateResponse runtimeResponse) {
    return runtimeResponse != null
        && !hasNonBlankError(runtimeResponse)
        && runtimeResponse.hasRuntimeState();
  }

  private static boolean hasNonBlankError(GetGameInstanceRuntimeStateResponse runtimeResponse) {
    return runtimeResponse.hasError() && !runtimeResponse.getError().getCode().isBlank();
  }

  private MaterializationEligibility currentMaterializationEligibility(
      ScriptScheduleInstance instance,
      TimerFiringCandidate candidate,
      GetGameInstanceRuntimeStateResponse runtimeResponse,
      Map<String, Optional<PluginRuntimeState>> pluginStateCache) {
    if (runtimeResponse == null
        || hasNonBlankError(runtimeResponse)
        || !runtimeResponse.hasRuntimeState()) {
      return MaterializationEligibility.authorityUnavailable();
    }
    GameInstanceRuntimeState runtimeState = runtimeResponse.getRuntimeState();
    if (!Objects.equals(runtimeState.getTenantId(), instance.getTenantId())
        || !Objects.equals(runtimeState.getGameInstanceId(), instance.getGameInstanceId())
        || runtimeState.getPinnedScriptPatchVersion().isBlank()) {
      return MaterializationEligibility.authorityUnavailable();
    }
    if (runtimeState.getRegionId().isBlank() || runtimeState.getRegionEpoch() <= 0) {
      return MaterializationEligibility.authorityUnavailable();
    }
    if (runtimeState.getScriptPinEpoch() <= 0 || instance.getScriptPinEpoch() <= 0) {
      return MaterializationEligibility.authorityUnavailable();
    }
    String runtimeRequestId =
        blankToEmpty(runtimeState.getScriptPatchPinnedControlPlaneRequestId());
    String instanceRequestId = blankToEmpty(instance.getLastObservedControlPlaneRequestId());
    if (runtimeRequestId.isBlank()) {
      return MaterializationEligibility.authorityUnavailable();
    }
    if (instanceRequestId.isBlank()) {
      return MaterializationEligibility.proven(REASON_SCRIPT_PIN_REQUEST_ID_REQUIRED);
    }
    if (!hasExplicitPlayableStateScope(runtimeState.getPlayableStateScope())) {
      return MaterializationEligibility.authorityUnavailable();
    }
    if (!hasExplicitPlayableStateScope(toPlayableStateScope(instance.getPlayableStateScope()))) {
      return MaterializationEligibility.proven(REASON_PLAYABLE_STATE_SCOPE_CHANGED);
    }
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.fromRuntimeState(runtimeState);
    if (!routingBundle.isPresent()) {
      return MaterializationEligibility.authorityUnavailable();
    }
    if (RoutingBundleSupport.hasPartialRouting(
        instance.getWorldSlug(), instance.getRealmSlug(), instance.getPointerVersion())) {
      return MaterializationEligibility.proven(REASON_ROUTING_BUNDLE_CHANGED);
    }
    RoutingBundleSupport.RoutingBundle persistedRoutingBundle;
    try {
      persistedRoutingBundle =
          RoutingBundleSupport.normalize(
              instance.getWorldSlug(), instance.getRealmSlug(), instance.getPointerVersion());
    } catch (IllegalArgumentException ex) {
      return MaterializationEligibility.proven(REASON_ROUTING_BUNDLE_CHANGED);
    }
    if (!RoutingBundleSupport.sameRoutingBundle(routingBundle, persistedRoutingBundle)) {
      return MaterializationEligibility.proven(REASON_ROUTING_BUNDLE_CHANGED);
    }
    if (!Objects.equals(
        runtimeState.getPinnedScriptPatchVersion(), instance.getScriptPatchVersion())) {
      return MaterializationEligibility.proven(REASON_SCRIPT_PATCH_MISMATCH);
    }
    if (runtimeState.getScriptPinEpoch() != instance.getScriptPinEpoch()) {
      return MaterializationEligibility.proven(REASON_SCRIPT_PIN_EPOCH_MISMATCH);
    }
    if (!runtimeRequestId.equals(instanceRequestId)) {
      return MaterializationEligibility.proven(REASON_SCRIPT_PIN_REQUEST_ID_MISMATCH);
    }
    if (!Objects.equals(
        normalizePlayableStateScope(runtimeState.getPlayableStateScope()),
        blankToEmpty(instance.getPlayableStateScope()))) {
      return MaterializationEligibility.proven(REASON_PLAYABLE_STATE_SCOPE_CHANGED);
    }
    if (!Objects.equals(runtimeState.getRegionId(), candidate.regionId())
        || runtimeState.getRegionEpoch() != candidate.regionEpoch()) {
      return MaterializationEligibility.proven(REASON_RUNTIME_SCOPE_CHANGED);
    }
    String pluginId = blankToEmpty(instance.getPluginId());
    String pluginVersionId = blankToEmpty(instance.getPluginVersionId());
    if (pluginId.isBlank() && pluginVersionId.isBlank()) {
      return MaterializationEligibility.eligible();
    }
    if (pluginId.isBlank() || pluginVersionId.isBlank()) {
      return MaterializationEligibility.proven(REASON_PLUGIN_BINDING_MISMATCH);
    }
    try {
      var pluginState =
          pluginStateCache.computeIfAbsent(
              pluginId,
              ignored ->
                  pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
                      instance.getTenantId(), instance.getGameInstanceId(), pluginId));
      if (pluginState.isEmpty()) {
        return MaterializationEligibility.proven(REASON_PLUGIN_BINDING_MISMATCH);
      }
      PluginRuntimeState state = pluginState.orElseThrow();
      return PluginState.PLUGIN_STATE_ENABLED.name().equals(state.getPluginState())
              && Objects.equals(pluginId, blankToEmpty(state.getPluginId()))
              && Objects.equals(blankToEmpty(state.getActivePluginVersionId()), pluginVersionId)
              && candidate.pluginActivationEpoch() > 0
              && candidate.lifecycleRevision() > 0
              && state.getPluginActivationEpoch() == candidate.pluginActivationEpoch()
              && state.getLifecycleRevision() == candidate.lifecycleRevision()
              && AutomationRuntimeScopeSupport.matches(
                  state,
                  new AutomationRuntimeScopeSupport.RuntimeScope(
                      candidate.regionId(), candidate.regionEpoch()))
          ? MaterializationEligibility.eligible()
          : MaterializationEligibility.proven(REASON_PLUGIN_BINDING_MISMATCH);
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Plugin runtime-state lookup failed for tenantId={} gameInstanceId={} pluginId={}; retaining timer due state",
          instance.getTenantId(),
          instance.getGameInstanceId(),
          pluginId,
          ex);
      return MaterializationEligibility.authorityUnavailable();
    }
  }

  private record MaterializationEligibility(Kind kind, String reason) {
    private enum Kind {
      ELIGIBLE,
      AUTHORITY_UNAVAILABLE,
      PROVEN_INELIGIBLE
    }

    private static MaterializationEligibility eligible() {
      return new MaterializationEligibility(Kind.ELIGIBLE, "");
    }

    private static MaterializationEligibility authorityUnavailable() {
      return new MaterializationEligibility(Kind.AUTHORITY_UNAVAILABLE, "");
    }

    private static MaterializationEligibility proven(String reason) {
      return new MaterializationEligibility(Kind.PROVEN_INELIGIBLE, reason);
    }
  }

  private record AdmissionStateCacheKey(String tenantId, String gameInstanceId, String regionId) {}

  private static boolean hasExplicitPlayableStateScope(PlayableStateScope scope) {
    return scope != null
        && scope != PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        && scope != PlayableStateScope.UNRECOGNIZED;
  }

  private void fenceIneligibleCandidate(
      ScriptScheduleInstance instance, TimerFiringCandidate candidate, String reason, Instant now) {
    LOGGER.warn(
        "Timer candidate fenced for tenantId={} gameInstanceId={} scriptId={} pluginId={} reason={}",
        instance.getTenantId(),
        instance.getGameInstanceId(),
        instance.getScriptId(),
        blankToEmpty(instance.getPluginId()),
        reason);
    boolean newlyRecorded = persistSkippedAudit(candidate, reason, now);
    instance.setMaterializationStatus(STATUS_FENCED);
    instance.setNextDueAt(null);
    instance.setNextDueTickId(null);
    instance.setRuntimeRegionId("");
    instance.setRuntimeRegionEpoch(null);
    instance.setLastObservedTickId(null);
    instance.setLastRuntimeProgressObservedAt(null);
    instance.setUpdatedAt(now);
    if (newlyRecorded && isRuntimeFenceMetricReason(reason)) {
      incrementTimerMetricAfterCommit(metricNameForReason(reason), List.of(candidate), reason);
    }
  }

  private static boolean isRuntimeFenceMetricReason(String reason) {
    return REASON_RUNTIME_SCOPE_CHANGED.equals(reason)
        || REASON_PLAYABLE_STATE_SCOPE_CHANGED.equals(reason);
  }

  private void fenceOverflow(
      ScriptScheduleInstance instance, TimerFiringCandidate candidate, String reason, Instant now) {
    fenceIneligibleCandidate(instance, candidate, reason, now);
  }

  private static void fenceMaterialization(ScriptScheduleInstance instance, Instant now) {
    instance.setMaterializationStatus(STATUS_FENCED);
    instance.setNextDueAt(null);
    instance.setNextDueTickId(null);
    instance.setRuntimeRegionId("");
    instance.setRuntimeRegionEpoch(null);
    instance.setLastObservedTickId(null);
    instance.setLastRuntimeProgressObservedAt(null);
    instance.setUpdatedAt(now);
  }

  private static void settleWallClockCandidate(ScriptScheduleInstance instance, Instant now) {
    instance.setNextDueAt(null);
    instance.setUpdatedAt(now);
  }

  private static void restoreDueCandidate(
      ScriptScheduleInstance instance, TimerFiringCandidate candidate) {
    if (!candidate.wallClock()
        && candidate.dueTickId() != null
        && (instance.getNextDueTickId() == null
            || instance.getNextDueTickId() > candidate.dueTickId())) {
      instance.setNextDueTickId(candidate.dueTickId());
    }
  }

  private static boolean usableAdmissionState(
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      ScriptScheduleInstance instance,
      TimerFiringCandidate candidate) {
    return admissionState != null
        && Objects.equals(admissionState.tenantId(), instance.getTenantId())
        && Objects.equals(admissionState.gameInstanceId(), instance.getGameInstanceId())
        && Objects.equals(admissionState.regionId(), candidate.regionId())
        && admissionState.admissionEpoch() > 0
        && (ADMISSION_MODE_NORMAL.equals(admissionState.mode())
            || ADMISSION_MODE_PAUSED_FOR_ROLLBACK.equals(admissionState.mode()));
  }

  private static boolean usableObservationAdmissionState(
      AutomationAdmissionStateService.AdmissionStateSummary admissionState,
      RuntimeTickProgressObservation observation) {
    return admissionState != null
        && Objects.equals(admissionState.tenantId(), observation.tenantId())
        && Objects.equals(admissionState.gameInstanceId(), observation.gameInstanceId())
        && Objects.equals(admissionState.regionId(), observation.regionId())
        && admissionState.admissionEpoch() > 0
        && (ADMISSION_MODE_NORMAL.equals(admissionState.mode())
            || ADMISSION_MODE_PAUSED_FOR_ROLLBACK.equals(admissionState.mode()));
  }

  private void persistTimerAudit(
      TimerFiringCandidate candidate, ScriptWorkItem workItem, Instant now) {
    ScriptScheduleInstance instance = candidate.instance();
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(instance.getTenantId());
    audit.setGameInstanceId(instance.getGameInstanceId());
    audit.setRegionId(candidate.regionId());
    audit.setRegionEpoch(candidate.regionEpoch());
    audit.setEntityId(workItem.getEntityId());
    audit.setPlayableStateScope(blankToEmpty(workItem.getPlayableStateScope()));
    audit.setWorldSlug(routingBundle.worldSlug());
    audit.setRealmSlug(routingBundle.realmSlug());
    audit.setPointerVersion(routingBundle.pointerVersion());
    audit.setScriptId(instance.getScriptId());
    audit.setPluginId(blankToEmpty(workItem.getPluginId()));
    audit.setPluginVersionId(blankToEmpty(workItem.getPluginVersionId()));
    audit.setEventType(instance.getEventType());
    audit.setEventSchemaVersion(DEFAULT_SCHEMA_VERSION);
    audit.setScriptPatchVersion(instance.getScriptPatchVersion());
    audit.setScriptPinEpoch(candidate.scriptPinEpoch());
    audit.setScriptPinControlPlaneRequestId(candidate.scriptPinControlPlaneRequestId());
    audit.setScriptEventId(workItem.getScriptEventId());
    audit.setDryRun(SCHEDULER_IS_DRY_RUN);
    audit.setSourceService(SOURCE_SERVICE);
    audit.setTriggerMode(SCHEDULER_TRIGGER_MODE);
    audit.setSourceKind("SCHEDULE_TIMER");
    audit.setSourceState("WORK_ITEM_PERSISTED");
    audit.setSourceOrdinal(workItem.getSourceOrdinal());
    audit.setSourceDueTickId(workItem.getSourceDueTickId());
    audit.setSourceDueAtMs(workItem.getSourceDueAtMs());
    audit.setWorkItemId(workItem.getId());
    audit.setFinalStage("ADMISSION");
    audit.setFinalOutcome("work_item_persisted");
    audit.setFinalReason(candidate.finalReason());
    audit.setCreatedAt(now);
    audit.setUpdatedAt(now);
    eventAuditRepository.save(audit);
  }

  private static long nextFutureDueTick(long observedTickId, Long currentDueTick, long cadence) {
    requireValidCadence(cadence);
    if (observedTickId < 0) {
      throw new IllegalArgumentException(REASON_INVALID_CADENCE);
    }
    if (currentDueTick == null) {
      try {
        return Math.addExact(observedTickId, cadence);
      } catch (ArithmeticException ex) {
        throw new IllegalArgumentException(REASON_DUE_TICK_OVERFLOW, ex);
      }
    }
    if (currentDueTick < 0) {
      throw new IllegalArgumentException(REASON_INVALID_CADENCE);
    }
    if (currentDueTick > observedTickId) {
      return currentDueTick;
    }
    try {
      long elapsed = Math.subtractExact(observedTickId, currentDueTick);
      long steps = Math.addExact(elapsed / cadence, 1L);
      long advance = Math.multiplyExact(steps, cadence);
      return Math.addExact(currentDueTick, advance);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException(REASON_DUE_TICK_OVERFLOW, ex);
    }
  }

  private static void requireValidCadence(long cadence) {
    if (cadence <= 0) {
      throw new IllegalArgumentException(REASON_INVALID_CADENCE);
    }
  }

  private static boolean isObservationStale(
      ScriptScheduleInstance instance, RuntimeTickProgressObservation observation) {
    return observationStaleReason(instance, observation) != null;
  }

  private static String observationStaleReason(
      ScriptScheduleInstance instance, RuntimeTickProgressObservation observation) {
    Long storedEpoch = instance.getRuntimeRegionEpoch();
    boolean sameRegion = observation.regionId().equals(blankToEmpty(instance.getRuntimeRegionId()));
    if (sameRegion
        && storedEpoch != null
        && storedEpoch > 0
        && observation.regionEpoch() < storedEpoch) {
      return "stale_runtime_progress_region_epoch";
    }
    Long storedTick = instance.getLastObservedTickId();
    if (storedTick != null
        && storedEpoch != null
        && observation.regionEpoch() == storedEpoch
        && sameRegion
        && observation.tickId() < storedTick) {
      return "stale_runtime_progress_tick";
    }
    return null;
  }

  private static String targetEntityId(ScriptScheduleInstance instance) {
    return "ENTITY".equals(instance.getTargetScopeType())
        ? blankToEmpty(instance.getTargetScopeId())
        : "";
  }

  private static String timerScriptEventId(TimerFiringCandidate candidate) {
    return "timer-" + shortHash(candidate.eventIdentity());
  }

  private TimerAuditEventSummary withPublication(String tenantId, TimerAuditEventSummary summary) {
    PluginRuntimeStateService.PluginPublicationLink pluginPublication =
        pluginPublicationLink(tenantId, summary.pluginId(), summary.pluginVersionId());
    return new TimerAuditEventSummary(
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.regionId(),
        summary.regionEpoch(),
        summary.entityId(),
        summary.playableStateScope(),
        summary.worldSlug(),
        summary.realmSlug(),
        summary.pointerVersion(),
        summary.scriptId(),
        summary.pluginId(),
        summary.pluginVersionId(),
        summary.eventType(),
        summary.scriptPatchVersion(),
        summary.scriptPinEpoch(),
        summary.scriptPinControlPlaneRequestId(),
        summary.scriptEventId(),
        summary.triggerMode(),
        summary.sourceState(),
        summary.sourceOrdinal(),
        summary.sourceDueTickId(),
        summary.sourceDueAtMs(),
        summary.workItemId(),
        summary.finalStage(),
        summary.finalOutcome(),
        summary.finalReason(),
        summary.createdAtMs(),
        summary.updatedAtMs(),
        publicationLink(tenantId, summary.scriptPatchVersion()),
        pluginPublication);
  }

  private ScheduleInstanceSummary withPublication(
      String tenantId, ScheduleInstanceSummary summary) {
    PluginRuntimeStateService.PluginPublicationLink pluginPublication =
        pluginPublicationLink(tenantId, summary.pluginId(), summary.pluginVersionId());
    return new ScheduleInstanceSummary(
        summary.tenantId(),
        summary.gameInstanceId(),
        summary.scriptPatchVersion(),
        summary.scriptPinEpoch(),
        summary.scriptId(),
        summary.playableStateScope(),
        summary.worldSlug(),
        summary.realmSlug(),
        summary.pointerVersion(),
        summary.pluginId(),
        summary.pluginVersionId(),
        summary.eventType(),
        summary.scheduleDefinitionId(),
        summary.scheduleKind(),
        summary.cadenceValue(),
        summary.cadenceUnit(),
        summary.priorityTag(),
        summary.targetScopeType(),
        summary.targetScopeId(),
        summary.bindingPriority(),
        summary.requiresExclusiveEvent(),
        summary.materializationStatus(),
        summary.nextDueAtMs(),
        summary.nextDueTickId(),
        summary.observedRuntimeVersionId(),
        summary.lastObservedControlPlaneRequestId(),
        summary.pinObservedAtMs(),
        summary.materializedAtMs(),
        summary.updatedAtMs(),
        summary.runtimeRegionId(),
        summary.runtimeRegionEpoch(),
        summary.lastObservedTickId(),
        summary.lastRuntimeProgressObservedAtMs(),
        publicationLink(tenantId, summary.scriptPatchVersion()),
        pluginPublication);
  }

  private ScriptWorkItemService.ScriptPatchPublicationLink publicationLink(
      String tenantId, String scriptPatchVersion) {
    GetPublishedScriptPatchVersionResponse response;
    try {
      response =
          gameDesignControlPlaneClient.getPublishedScriptPatchVersion(tenantId, scriptPatchVersion);
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Game Design script-patch publication lookup failed for tenantId={} scriptPatchVersion={}",
          tenantId,
          scriptPatchVersion,
          ex);
      return unavailableScriptPatchPublication(scriptPatchVersion);
    }
    if (response == null) {
      LOGGER.warn(
          "Game Design script-patch publication lookup returned no response for tenantId={} scriptPatchVersion={}",
          tenantId,
          scriptPatchVersion);
      return unavailableScriptPatchPublication(scriptPatchVersion);
    }
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new ScriptWorkItemService.ScriptPatchPublicationLink(
          blankToEmpty(scriptPatchVersion),
          0L,
          0L,
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
          0L,
          blankToEmpty(response.getError().getCode()),
          blankToEmpty(response.getError().getMessage()));
    }
    return new ScriptWorkItemService.ScriptPatchPublicationLink(
        blankToEmpty(response.getScriptPatch().getScriptPatchVersion()),
        response.getScriptPatch().getVersionId(),
        response.getScriptPatch().getBaseVersionId(),
        response.getScriptPatch().getPublicationState(),
        response.getScriptPatch().getLastChangedAtMs(),
        "",
        "");
  }

  private PluginRuntimeStateService.PluginPublicationLink pluginPublicationLink(
      String tenantId, String pluginId, String pluginVersionId) {
    if (blankToEmpty(pluginId).isBlank() || blankToEmpty(pluginVersionId).isBlank()) {
      return null;
    }
    GetPublishedPluginVersionResponse response;
    try {
      response =
          gameDesignControlPlaneClient.getPublishedPluginVersion(
              tenantId, pluginId, pluginVersionId);
    } catch (RuntimeException ex) {
      LOGGER.warn(
          "Game Design plugin publication lookup failed for tenantId={} pluginId={} pluginVersionId={}",
          tenantId,
          pluginId,
          pluginVersionId,
          ex);
      return unavailablePluginPublication(pluginVersionId);
    }
    if (response == null) {
      LOGGER.warn(
          "Game Design plugin publication lookup returned no response for tenantId={} pluginId={} pluginVersionId={}",
          tenantId,
          pluginId,
          pluginVersionId);
      return unavailablePluginPublication(pluginVersionId);
    }
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new PluginRuntimeStateService.PluginPublicationLink(
          pluginVersionId,
          0L,
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
          "",
          0L,
          blankToEmpty(response.getError().getCode()),
          blankToEmpty(response.getError().getMessage()));
    }
    return new PluginRuntimeStateService.PluginPublicationLink(
        blankToEmpty(response.getPluginVersion().getPluginVersionId()),
        response.getPluginVersion().getPublicationId(),
        response.getPluginVersion().getPublicationState(),
        blankToEmpty(response.getPluginVersion().getStatusReason()),
        response.getPluginVersion().getLastChangedAtMs(),
        "",
        "");
  }

  private static ScriptWorkItemService.ScriptPatchPublicationLink unavailableScriptPatchPublication(
      String scriptPatchVersion) {
    return new ScriptWorkItemService.ScriptPatchPublicationLink(
        blankToEmpty(scriptPatchVersion),
        0L,
        0L,
        VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
        0L,
        "GAME_DESIGN_UNAVAILABLE",
        "Game Design service unavailable");
  }

  private static PluginRuntimeStateService.PluginPublicationLink unavailablePluginPublication(
      String pluginVersionId) {
    return new PluginRuntimeStateService.PluginPublicationLink(
        pluginVersionId,
        0L,
        VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
        "",
        0L,
        "GAME_DESIGN_UNAVAILABLE",
        "Game Design service unavailable");
  }

  private static TimerAuditEventSummary toTimerAuditSummary(ScriptEventAudit audit) {
    return new TimerAuditEventSummary(
        audit.getTenantId(),
        audit.getGameInstanceId(),
        audit.getRegionId(),
        audit.getRegionEpoch() == null ? 0L : audit.getRegionEpoch(),
        audit.getEntityId(),
        blankToEmpty(audit.getPlayableStateScope()),
        blankToEmpty(audit.getWorldSlug()),
        blankToEmpty(audit.getRealmSlug()),
        blankToEmpty(audit.getPointerVersion()),
        audit.getScriptId(),
        blankToEmpty(audit.getPluginId()),
        blankToEmpty(audit.getPluginVersionId()),
        audit.getEventType(),
        audit.getScriptPatchVersion(),
        audit.getScriptPinEpoch() == null ? 0L : audit.getScriptPinEpoch(),
        blankToEmpty(audit.getScriptPinControlPlaneRequestId()),
        audit.getScriptEventId(),
        audit.getTriggerMode(),
        blankToEmpty(audit.getSourceState()),
        audit.getSourceOrdinal() == null ? 0L : audit.getSourceOrdinal(),
        audit.getSourceDueTickId() == null ? 0L : audit.getSourceDueTickId(),
        audit.getSourceDueAtMs() == null ? 0L : audit.getSourceDueAtMs(),
        audit.getWorkItemId() == null ? 0L : audit.getWorkItemId(),
        audit.getFinalStage(),
        audit.getFinalOutcome(),
        audit.getFinalReason(),
        audit.getCreatedAt() == null ? 0L : audit.getCreatedAt().toEpochMilli(),
        audit.getUpdatedAt() == null ? 0L : audit.getUpdatedAt().toEpochMilli(),
        null,
        null);
  }

  private static String timerReadSnapshotToken(TimerFiringCandidate candidate) {
    ScriptScheduleInstance instance = candidate.instance();
    return "automation:"
        + instance.getEventType()
        + ":"
        + instance.getGameInstanceId()
        + ":"
        + candidate.regionEpoch()
        + ":"
        + candidate.scriptPinControlPlaneRequestId()
        + ":"
        + candidate.duePointToken()
        + ":"
        + shortHash(instance.getScheduleDefinitionId());
  }

  private String timerPayload(TimerFiringCandidate candidate) {
    ScriptScheduleInstance instance = candidate.instance();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("scheduleId", blankToEmpty(instance.getScheduleDefinitionId()));
    payload.put("scriptPatchVersion", blankToEmpty(instance.getScriptPatchVersion()));
    payload.put("scriptPinEpoch", candidate.scriptPinEpoch());
    payload.put(
        "scriptPinControlPlaneRequestId", blankToEmpty(candidate.scriptPinControlPlaneRequestId()));
    if (candidate.wallClock()) {
      payload.put("dueAt", candidate.dueAt().toEpochMilli());
    } else {
      payload.put("dueTickId", candidate.dueTickId());
    }
    payload.put("targetScopeType", blankToEmpty(instance.getTargetScopeType()));
    payload.put("targetScopeId", blankToEmpty(instance.getTargetScopeId()));
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception ex) {
      throw new IllegalStateException("timer_payload_json_invalid", ex);
    }
  }

  private static String scheduleKey(ScriptScheduleInstance instance) {
    return blankToEmpty(instance.getPlayableStateScope())
        + "|"
        + blankToEmpty(instance.getPluginId())
        + "|"
        + blankToEmpty(instance.getPluginVersionId())
        + "|"
        + blankToEmpty(instance.getTargetScopeType())
        + "|"
        + blankToEmpty(instance.getTargetScopeId())
        + "|"
        + blankToEmpty(instance.getScheduleDefinitionId());
  }

  private static String shortHash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.substring(0, 60);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private ScheduleInstanceSummary toSummary(ScriptScheduleInstance instance) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            instance.getWorldSlug(), instance.getRealmSlug(), instance.getPointerVersion());
    return new ScheduleInstanceSummary(
        instance.getTenantId(),
        instance.getGameInstanceId(),
        instance.getScriptPatchVersion(),
        instance.getScriptPinEpoch(),
        instance.getScriptId(),
        blankToEmpty(instance.getPlayableStateScope()),
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion(),
        instance.getPluginId(),
        instance.getPluginVersionId(),
        instance.getEventType(),
        instance.getScheduleDefinitionId(),
        instance.getScheduleKind(),
        instance.getCadenceValue(),
        instance.getCadenceUnit(),
        instance.getPriorityTag(),
        instance.getTargetScopeType(),
        instance.getTargetScopeId(),
        instance.getBindingPriority(),
        instance.isRequiresExclusiveEvent(),
        instance.getMaterializationStatus(),
        instance.getNextDueAt() == null ? 0L : instance.getNextDueAt().toEpochMilli(),
        instance.getNextDueTickId() == null ? 0L : instance.getNextDueTickId(),
        instance.getObservedRuntimeVersionId(),
        instance.getLastObservedControlPlaneRequestId(),
        instance.getPinObservedAt().equals(Instant.EPOCH)
            ? 0L
            : instance.getPinObservedAt().toEpochMilli(),
        instance.getMaterializedAt().toEpochMilli(),
        instance.getUpdatedAt().toEpochMilli(),
        blankToEmpty(instance.getRuntimeRegionId()),
        instance.getRuntimeRegionEpoch() == null ? 0L : instance.getRuntimeRegionEpoch(),
        instance.getLastObservedTickId() == null ? 0L : instance.getLastObservedTickId(),
        instance.getLastRuntimeProgressObservedAt() == null
            ? 0L
            : instance.getLastRuntimeProgressObservedAt().toEpochMilli(),
        null,
        null);
  }

  private static String scopeKey(
      String playableStateScope,
      String pluginId,
      String pluginVersionId,
      String scheduleDefinitionId,
      String targetScopeType,
      String targetScopeId) {
    return blankToEmpty(playableStateScope)
        + "\u0000"
        + blankToEmpty(pluginId)
        + "\u0000"
        + blankToEmpty(pluginVersionId)
        + "\u0000"
        + blankToEmpty(targetScopeType)
        + "\u0000"
        + blankToEmpty(targetScopeId)
        + "\u0000"
        + scheduleDefinitionId;
  }

  private static String bindingKey(String scriptId, String eventType) {
    return blankToEmpty(scriptId) + "\u0000" + blankToEmpty(eventType);
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

  private static PlayableStateScope toPlayableStateScope(String playableStateScope) {
    return switch (blankToEmpty(playableStateScope)) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private record TickAdvanceResult(
      boolean changed, List<Long> fireDueTicks, List<TimerFiringCandidate> suppressedDueTicks) {}

  private record WallClockAdvanceResult(
      boolean changed, Instant fireDueAt, TimerFiringCandidate suppressedCandidate) {}

  private record TimerFiringCandidate(
      ScriptScheduleInstance instance,
      String regionId,
      Long regionEpoch,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
      long pluginActivationEpoch,
      long lifecycleRevision,
      Long dueTickId,
      Instant dueAt,
      boolean wallClock) {
    private static TimerFiringCandidate tick(ScriptScheduleInstance instance, long dueTickId) {
      return new TimerFiringCandidate(
          instance,
          blankToEmpty(instance.getRuntimeRegionId()),
          instance.getRuntimeRegionEpoch(),
          requirePositiveScriptPinEpoch(instance),
          requireScriptPinControlPlaneRequestId(instance),
          instance.getPluginActivationEpoch(),
          instance.getLifecycleRevision(),
          dueTickId,
          null,
          false);
    }

    private static TimerFiringCandidate tick(
        ScriptScheduleInstance instance, long dueTickId, String regionId, long regionEpoch) {
      return new TimerFiringCandidate(
          instance,
          regionId,
          regionEpoch,
          requirePositiveScriptPinEpoch(instance),
          requireScriptPinControlPlaneRequestId(instance),
          instance.getPluginActivationEpoch(),
          instance.getLifecycleRevision(),
          dueTickId,
          null,
          false);
    }

    private static TimerFiringCandidate wallClock(
        ScriptScheduleInstance instance, Instant dueAt, String regionId, long regionEpoch) {
      return new TimerFiringCandidate(
          instance,
          regionId,
          regionEpoch,
          requirePositiveScriptPinEpoch(instance),
          requireScriptPinControlPlaneRequestId(instance),
          instance.getPluginActivationEpoch(),
          instance.getLifecycleRevision(),
          null,
          dueAt,
          true);
    }

    private static TimerFiringCandidate suppressedTick(
        ScriptScheduleInstance instance, long dueTickId, String regionId, Long regionEpoch) {
      return new TimerFiringCandidate(
          instance,
          regionId,
          regionEpoch,
          requirePositiveScriptPinEpoch(instance),
          requireScriptPinControlPlaneRequestId(instance),
          instance.getPluginActivationEpoch(),
          instance.getLifecycleRevision(),
          dueTickId,
          null,
          false);
    }

    private static TimerFiringCandidate suppressedWallClock(
        ScriptScheduleInstance instance, Instant dueAt, String regionId, Long regionEpoch) {
      return new TimerFiringCandidate(
          instance,
          regionId,
          regionEpoch,
          requirePositiveScriptPinEpoch(instance),
          requireScriptPinControlPlaneRequestId(instance),
          instance.getPluginActivationEpoch(),
          instance.getLifecycleRevision(),
          null,
          dueAt,
          true);
    }

    private String duePointToken() {
      return wallClock ? Long.toString(dueAt.toEpochMilli()) : Long.toString(dueTickId);
    }

    private long dueOrderValue() {
      return wallClock ? dueAt.toEpochMilli() : dueTickId;
    }

    private String durableIdentity() {
      return identity(true);
    }

    private String eventIdentity() {
      return identity(false);
    }

    private String identity(boolean includeOwnerRequestEvidence) {
      List<String> values =
          new ArrayList<>(
              List.of(
                  instance.getTenantId(),
                  instance.getGameInstanceId(),
                  instance.getPlayableStateScope(),
                  regionId,
                  String.valueOf(regionEpoch),
                  blankToEmpty(instance.getTargetScopeType()),
                  blankToEmpty(instance.getTargetScopeId()),
                  targetEntityId(instance),
                  instance.getScriptId(),
                  blankToEmpty(instance.getPluginId()),
                  blankToEmpty(instance.getPluginVersionId()),
                  instance.getEventType(),
                  DEFAULT_SCHEMA_VERSION,
                  instance.getScriptPatchVersion(),
                  String.valueOf(scriptPinEpoch)));
      if (!blankToEmpty(instance.getPluginId()).isBlank()) {
        values.add("pluginActivationEpoch:" + pluginActivationEpoch);
      }
      if (includeOwnerRequestEvidence) {
        values.add(scriptPinControlPlaneRequestId);
      }
      values.addAll(
          List.of(
              instance.getScheduleDefinitionId(),
              wallClock ? "dueAt:" + dueAt.toEpochMilli() : "dueTickId:" + dueTickId,
              Boolean.toString(SCHEDULER_IS_DRY_RUN),
              SCHEDULER_TRIGGER_MODE));
      return lengthPrefixedIdentity(values.toArray(String[]::new));
    }

    private String finalReason() {
      return wallClock ? "timer_due_at_" + dueAt.toEpochMilli() : "timer_due_tick_" + dueTickId;
    }
  }

  private static long requirePositiveScriptPinEpoch(ScriptScheduleInstance instance) {
    if (instance.getScriptPinEpoch() <= 0) {
      throw new IllegalArgumentException("script_pin_epoch_required");
    }
    return instance.getScriptPinEpoch();
  }

  private static String requireScriptPinControlPlaneRequestId(ScriptScheduleInstance instance) {
    String requestId = blankToEmpty(instance.getLastObservedControlPlaneRequestId());
    if (requestId.isBlank()) {
      throw new IllegalArgumentException(REASON_SCRIPT_PIN_REQUEST_ID_REQUIRED);
    }
    return requestId;
  }

  private static boolean hasCompleteScriptPinTuple(ScriptScheduleInstance instance) {
    return instance != null
        && instance.getScriptPinEpoch() > 0
        && !blankToEmpty(instance.getScriptPatchVersion()).isBlank()
        && !blankToEmpty(instance.getLastObservedControlPlaneRequestId()).isBlank();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String lengthPrefixedIdentity(String... values) {
    StringBuilder identity = new StringBuilder();
    for (String value : values) {
      String normalized = blankToEmpty(value);
      // UTF-8 byte framing is part of persisted dedup identity across language boundaries.
      identity
          .append(normalized.getBytes(StandardCharsets.UTF_8).length)
          .append(':')
          .append(normalized);
    }
    return identity.toString();
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  private static List<ScriptScheduleInstance> safeInstances(
      List<ScriptScheduleInstance> instances) {
    return instances == null ? List.of() : instances;
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }
}
