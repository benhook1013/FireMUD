package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
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
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained internally.")
public class ScriptScheduleInstanceServiceImpl implements ScriptScheduleInstanceService {
  private static final String UNIT_MILLISECONDS = "MILLISECONDS";
  private static final String UNIT_TICKS = "TICKS";
  private static final String STATUS_READY = "READY";
  private static final String STATUS_PENDING_RUNTIME_PROGRESS = "PENDING_RUNTIME_PROGRESS";
  private static final String DEFAULT_SCHEMA_VERSION = "v1";
  private static final String SOURCE_SERVICE = "automation-scripting-service";
  private static final String FINAL_STAGE_ADMISSION = "ADMISSION";
  private static final String FINAL_OUTCOME_CANCELED = "canceled";
  private static final String REASON_CATCH_UP_TRUNCATED = "catch_up_truncated";
  private static final String REASON_RUNTIME_SCOPE_CHANGED = "runtime_scope_changed";

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
  private final ScriptSchedulerProperties schedulerProperties;
  private final MeterRegistry meterRegistry;

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
      ScriptSchedulerProperties schedulerProperties,
      MeterRegistry meterRegistry) {
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
    this.schedulerProperties = schedulerProperties;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Transactional
  public void reconcileObservedRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState) {
    requireText(tenantId, "tenant_id");
    requireText(gameInstanceId, "game_instance_id");
    if (runtimeState == null || runtimeState.getPinnedScriptPatchVersion().isBlank()) {
      scheduleInstanceRepository.deleteByTenantIdAndGameInstanceId(tenantId, gameInstanceId);
      return;
    }

    String scriptPatchVersion = runtimeState.getPinnedScriptPatchVersion();
    List<ScriptScheduleDefinition> definitions =
        scheduleDefinitionRepository
            .findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
                Long.parseLong(tenantId), scriptPatchVersion);
    Map<String, List<ScriptEventBinding>> bindingsByScriptEvent =
        bindingsByScriptEvent(Long.parseLong(tenantId), scriptPatchVersion);
    Map<String, String> activePluginVersions = activePluginVersions(tenantId, gameInstanceId);
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
        populateInstance(
            instance,
            tenantId,
            gameInstanceId,
            definition,
            binding,
            runtimeState,
            pinObservedAt,
            now);
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

  @Override
  @Transactional
  public void reconcilePinnedPatchInstances(String tenantId, String scriptPatchVersion) {
    requireText(tenantId, "tenant_id");
    requireText(scriptPatchVersion, "script_patch_version");
    for (ScriptPatchPinProjection projection :
        pinProjectionRepository.findByTenantIdAndObservedPinnedScriptPatchVersion(
            tenantId, scriptPatchVersion)) {
      GameInstanceRuntimeState runtimeState =
          GameInstanceRuntimeState.newBuilder()
              .setTenantId(projection.getTenantId())
              .setGameInstanceId(projection.getGameInstanceId())
              .setPinnedScriptPatchVersion(projection.getObservedPinnedScriptPatchVersion())
              .setRegionId(blankToEmpty(projection.getRuntimeRegionId()))
              .setRegionEpoch(projection.getRuntimeRegionEpoch())
              .setPlayableStateScope(toPlayableStateScope(projection.getPlayableStateScope()))
              .setWorldSlug(blankToEmpty(projection.getWorldSlug()))
              .setRealmSlug(blankToEmpty(projection.getRealmSlug()))
              .setPointerVersion(parsePointerVersion(projection.getPointerVersion()))
              .setScriptPatchPinnedControlPlaneRequestId(
                  projection.getLastObservedControlPlaneRequestId())
              .setScriptPatchPinnedAtMs(projection.getObservedAt().toEpochMilli())
              .build();
      reconcileObservedRuntimeState(tenantId, projection.getGameInstanceId(), runtimeState);
    }
  }

  @Override
  @Transactional
  public RuntimeTickProgressResult observeRuntimeTickProgress(
      RuntimeTickProgressObservation observation) {
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
    List<ScriptScheduleInstance> updates = new ArrayList<>();
    int maxFirings = schedulerProperties.getMaxCatchUpFiringsPerObservation();
    List<TimerFiringCandidate> candidates = new ArrayList<>();
    List<TimerFiringCandidate> suppressedCandidates = new ArrayList<>();
    List<ScriptScheduleInstance> tickInstances =
        safeInstances(
            scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
                observation.tenantId(), observation.gameInstanceId(), UNIT_TICKS));
    List<ScriptScheduleInstance> wallClockInstances =
        safeInstances(
            scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
                observation.tenantId(), observation.gameInstanceId(), UNIT_MILLISECONDS));
    int fired = 0;
    for (ScriptScheduleInstance instance : tickInstances) {
      TickAdvanceResult advance =
          advanceRuntimeProgress(instance, observation, observedAt, now, maxFirings + 1);
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
      WallClockAdvanceResult advance =
          advanceWallClockProgress(instance, observation, observedAt, now);
      if (advance.fireDueAt() != null) {
        candidates.add(TimerFiringCandidate.wallClock(instance, advance.fireDueAt()));
      }
      if (advance.suppressedCandidate() != null) {
        suppressedCandidates.add(advance.suppressedCandidate());
      }
      if (advance.changed()) {
        updates.add(instance);
      }
    }
    List<TimerFiringCandidate> selectedCandidates = roundRobinCandidates(candidates, maxFirings);
    Set<String> selectedIdentities =
        selectedCandidates.stream()
            .map(TimerFiringCandidate::identity)
            .collect(java.util.stream.Collectors.toSet());
    List<TimerFiringCandidate> truncatedCandidates =
        candidates.stream()
            .filter(candidate -> !selectedIdentities.contains(candidate.identity()))
            .toList();
    recordSkippedCandidates(suppressedCandidates, REASON_RUNTIME_SCOPE_CHANGED, now);
    recordSkippedCandidates(truncatedCandidates, REASON_CATCH_UP_TRUNCATED, now);
    List<TimerFiringCandidate> firedCandidates = new ArrayList<>();
    for (TimerFiringCandidate candidate : selectedCandidates) {
      if (emitTimerWorkItem(candidate, now)) {
        fired++;
        firedCandidates.add(candidate);
      }
    }
    int truncated = Math.max(0, candidates.size() - selectedCandidates.size());
    if (fired > 0) {
      incrementTimerMetric("automation_script_timer_fired_total", firedCandidates, null);
    }
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
      String scriptId,
      String eventType,
      String finalReason,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    requireText(tenantId, "tenant_id");
    int boundedLimit = Math.min(Math.max(limit <= 0 ? 50 : limit, 1), 500);
    return eventAuditRepository
        .findTimerAuditEvents(
            tenantId,
            blankToEmpty(gameInstanceId),
            blankToEmpty(scriptPatchVersion),
            blankToEmpty(scriptId),
            blankToEmpty(eventType),
            blankToEmpty(finalReason),
            changedAfterMs <= 0 ? null : Instant.ofEpochMilli(changedAfterMs),
            changedBeforeMs <= 0 ? null : Instant.ofEpochMilli(changedBeforeMs),
            org.springframework.data.domain.PageRequest.of(0, boundedLimit))
        .stream()
        .map(ScriptScheduleInstanceServiceImpl::toTimerAuditSummary)
        .map(summary -> withPublication(tenantId, summary))
        .toList();
  }

  private Map<String, String> activePluginVersions(String tenantId, String gameInstanceId) {
    Map<String, String> active = new HashMap<>();
    for (PluginRuntimeState state :
        pluginRuntimeStateRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)) {
      if (!PluginState.PLUGIN_STATE_ENABLED.name().equals(state.getPluginState())) {
        continue;
      }
      String pluginId = blankToEmpty(state.getPluginId());
      String activePluginVersionId = blankToEmpty(state.getActivePluginVersionId());
      if (!pluginId.isBlank() && !activePluginVersionId.isBlank()) {
        active.put(pluginId, activePluginVersionId);
      }
    }
    return active;
  }

  private static boolean shouldMaterialize(
      ScriptScheduleDefinition definition, Map<String, String> activePluginVersions) {
    String pluginId = blankToEmpty(definition.getPluginId());
    if (pluginId.isBlank()) {
      return true;
    }
    return blankToEmpty(definition.getPluginVersionId()).equals(activePluginVersions.get(pluginId));
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
      Instant pinObservedAt,
      Instant now) {
    instance.setTenantId(tenantId);
    instance.setGameInstanceId(gameInstanceId);
    instance.setScriptPatchVersion(definition.getScriptPatchVersion());
    instance.setScriptId(definition.getScriptId());
    instance.setPlayableStateScope(
        normalizePlayableStateScope(runtimeState.getPlayableStateScope()));
    instance.setWorldSlug(blankToEmpty(runtimeState.getWorldSlug()));
    instance.setRealmSlug(blankToEmpty(runtimeState.getRealmSlug()));
    instance.setPointerVersion(normalizePointerVersion(runtimeState.getPointerVersion()));
    instance.setPluginId(blankToEmpty(definition.getPluginId()));
    instance.setPluginVersionId(blankToEmpty(definition.getPluginVersionId()));
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
        blankToEmpty(runtimeState.getScriptPatchPinnedControlPlaneRequestId()));
    instance.setScheduleMetadataJson(definition.getScheduleMetadataJson());
    instance.setScheduleSemanticsHash(definition.getScheduleSemanticsHash());
    instance.setPinObservedAt(pinObservedAt);
    if (instance.getId() == null) {
      instance.setMaterializedAt(now);
    }
    if (UNIT_MILLISECONDS.equals(definition.getCadenceUnit())) {
      instance.setMaterializationStatus(STATUS_READY);
      instance.setNextDueAt(pinObservedAt.plusMillis(definition.getCadenceValue()));
      instance.setNextDueTickId(null);
      instance.setRuntimeRegionId("");
      instance.setRuntimeRegionEpoch(null);
      instance.setLastObservedTickId(null);
      instance.setLastRuntimeProgressObservedAt(null);
    } else {
      instance.setMaterializationStatus(STATUS_PENDING_RUNTIME_PROGRESS);
      instance.setNextDueAt(null);
      instance.setNextDueTickId(null);
    }
    instance.setUpdatedAt(now);
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
    List<TimerFiringCandidate> suppressedDueTicks =
        runtimeScopeChanged && currentDueTick != null && currentDueTick <= observation.tickId()
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
        !runtimeScopeChanged && currentDueTick != null && currentDueTick <= observation.tickId()
            ? dueTicks(
                currentDueTick,
                observation.tickId(),
                instance.getCadenceValue(),
                perScheduleCandidateLimit)
            : List.of();
    long nextDueTick =
        runtimeScopeChanged || currentDueTick == null || currentDueTick <= observation.tickId()
            ? nextFutureDueTick(observation.tickId(), currentDueTick, instance.getCadenceValue())
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
    TimerFiringCandidate suppressedCandidate =
        runtimeScopeChanged && currentDueAt != null && !currentDueAt.isAfter(observedAt)
            ? TimerFiringCandidate.suppressedWallClock(
                instance,
                currentDueAt,
                blankToEmpty(instance.getRuntimeRegionId()),
                instance.getRuntimeRegionEpoch())
            : null;
    Instant fireDueAt =
        !runtimeScopeChanged
                && currentDueAt != null
                && !currentDueAt.isAfter(observedAt)
                && !blankToEmpty(observation.regionId()).isBlank()
                && observation.regionEpoch() > 0
            ? currentDueAt
            : null;
    boolean changed =
        runtimeScopeChanged
            || instance.getLastObservedTickId() == null
            || instance.getLastObservedTickId() != observation.tickId()
            || !STATUS_READY.equals(instance.getMaterializationStatus());
    if (!changed) {
      return new WallClockAdvanceResult(false, fireDueAt, suppressedCandidate);
    }
    instance.setMaterializationStatus(STATUS_READY);
    instance.setRuntimeRegionId(observation.regionId());
    instance.setRuntimeRegionEpoch(observation.regionEpoch());
    instance.setLastObservedTickId(observation.tickId());
    instance.setLastRuntimeProgressObservedAt(observedAt);
    if (runtimeScopeChanged && suppressedCandidate != null) {
      instance.setNextDueAt(null);
    }
    instance.setUpdatedAt(now);
    return new WallClockAdvanceResult(true, fireDueAt, suppressedCandidate);
  }

  private void recordSkippedCandidates(
      List<TimerFiringCandidate> candidates, String reason, Instant now) {
    if (candidates.isEmpty()) {
      return;
    }
    incrementTimerMetric(metricNameForReason(reason), candidates, reason);
    for (TimerFiringCandidate candidate : candidates) {
      persistSkippedAudit(candidate, reason, now);
    }
  }

  private String metricNameForReason(String reason) {
    return switch (reason) {
      case REASON_CATCH_UP_TRUNCATED -> "automation_script_timer_catchup_truncated_total";
      case REASON_RUNTIME_SCOPE_CHANGED -> "automation_script_timer_runtime_fence_dropped_total";
      default -> throw new IllegalArgumentException("Unknown timer skip reason: " + reason);
    };
  }

  private void incrementTimerMetric(
      String metricName, List<TimerFiringCandidate> candidates, String reason) {
    for (TimerFiringCandidate candidate : candidates) {
      ScriptScheduleInstance instance = candidate.instance();
      io.micrometer.core.instrument.Counter.Builder builder =
          io.micrometer.core.instrument.Counter.builder(metricName)
              .tag("eventType", instance.getEventType());
      if (reason != null) {
        builder.tag("reason", reason);
      }
      builder.register(meterRegistry).increment();
    }
  }

  private void persistSkippedAudit(
      TimerFiringCandidate candidate, String finalReason, Instant now) {
    ScriptScheduleInstance instance = candidate.instance();
    String entityId = targetEntityId(instance);
    String scriptEventId = timerScriptEventId(candidate);
    if (eventAuditRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            instance.getTenantId(),
            instance.getGameInstanceId(),
            candidate.regionId(),
            candidate.regionEpoch(),
            entityId,
            blankToEmpty(instance.getPlayableStateScope()),
            blankToEmpty(instance.getWorldSlug()),
            blankToEmpty(instance.getRealmSlug()),
            blankToEmpty(instance.getPointerVersion()),
            instance.getScriptId(),
            instance.getEventType(),
            DEFAULT_SCHEMA_VERSION,
            instance.getScriptPatchVersion(),
            scriptEventId,
            false)) {
      return;
    }
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(instance.getTenantId());
    audit.setGameInstanceId(instance.getGameInstanceId());
    audit.setRegionId(candidate.regionId());
    audit.setRegionEpoch(candidate.regionEpoch());
    audit.setEntityId(entityId);
    audit.setPlayableStateScope(blankToEmpty(instance.getPlayableStateScope()));
    audit.setWorldSlug(blankToEmpty(instance.getWorldSlug()));
    audit.setRealmSlug(blankToEmpty(instance.getRealmSlug()));
    audit.setPointerVersion(blankToEmpty(instance.getPointerVersion()));
    audit.setScriptId(instance.getScriptId());
    audit.setPluginId(blankToEmpty(instance.getPluginId()));
    audit.setPluginVersionId(blankToEmpty(instance.getPluginVersionId()));
    audit.setEventType(instance.getEventType());
    audit.setEventSchemaVersion(DEFAULT_SCHEMA_VERSION);
    audit.setScriptPatchVersion(instance.getScriptPatchVersion());
    audit.setScriptEventId(scriptEventId);
    audit.setDryRun(false);
    audit.setSourceService(SOURCE_SERVICE);
    audit.setTriggerMode(TriggerMode.TRIGGER_MODE_CATCH_UP.name());
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
    eventAuditRepository.save(audit);
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
    long boundedCadence = Math.max(1L, cadence);
    List<Long> dueTicks = new ArrayList<>();
    long dueTick = firstDueTick;
    while (dueTick <= observedTickId && dueTicks.size() < candidateLimit) {
      dueTicks.add(dueTick);
      dueTick += boundedCadence;
    }
    return List.copyOf(dueTicks);
  }

  private boolean emitTimerWorkItem(TimerFiringCandidate candidate, Instant now) {
    ScriptScheduleInstance instance = candidate.instance();
    String entityId = targetEntityId(instance);
    String scriptEventId = timerScriptEventId(candidate);
    if (workItemRepository
        .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
            instance.getTenantId(),
            instance.getGameInstanceId(),
            candidate.regionId(),
            candidate.regionEpoch(),
            entityId,
            blankToEmpty(instance.getPlayableStateScope()),
            blankToEmpty(instance.getWorldSlug()),
            blankToEmpty(instance.getRealmSlug()),
            blankToEmpty(instance.getPointerVersion()),
            instance.getScriptId(),
            instance.getEventType(),
            DEFAULT_SCHEMA_VERSION,
            instance.getScriptPatchVersion(),
            scriptEventId,
            false)) {
      return false;
    }
    AutomationAdmissionStateService.AdmissionStateSummary admissionState =
        automationAdmissionStateService.getState(
            instance.getTenantId(), instance.getGameInstanceId(), candidate.regionId());
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId(instance.getTenantId());
    item.setGameInstanceId(instance.getGameInstanceId());
    item.setRegionId(candidate.regionId());
    item.setRegionEpoch(candidate.regionEpoch());
    item.setEntityId(entityId);
    item.setPlayableStateScope(blankToEmpty(instance.getPlayableStateScope()));
    item.setWorldSlug(blankToEmpty(instance.getWorldSlug()));
    item.setRealmSlug(blankToEmpty(instance.getRealmSlug()));
    item.setPointerVersion(blankToEmpty(instance.getPointerVersion()));
    item.setScriptId(instance.getScriptId());
    item.setPluginId(blankToEmpty(instance.getPluginId()));
    item.setPluginVersionId(blankToEmpty(instance.getPluginVersionId()));
    item.setEventType(instance.getEventType());
    item.setEventSchemaVersion(DEFAULT_SCHEMA_VERSION);
    item.setScriptPatchVersion(instance.getScriptPatchVersion());
    item.setScriptEventId(scriptEventId);
    item.setDryRun(false);
    item.setSourceService(SOURCE_SERVICE);
    item.setTriggerMode(TriggerMode.TRIGGER_MODE_CATCH_UP.name());
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
    try {
      ScriptWorkItem saved = workItemRepository.saveAndFlush(item);
      automationQueueService.enqueueWorkItem(saved);
      persistTimerAudit(candidate, saved, now);
      if (candidate.wallClock()) {
        instance.setNextDueAt(null);
        instance.setUpdatedAt(now);
      }
      return true;
    } catch (DataIntegrityViolationException ex) {
      return false;
    }
  }

  private void persistTimerAudit(
      TimerFiringCandidate candidate, ScriptWorkItem workItem, Instant now) {
    ScriptScheduleInstance instance = candidate.instance();
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId(instance.getTenantId());
    audit.setGameInstanceId(instance.getGameInstanceId());
    audit.setRegionId(candidate.regionId());
    audit.setRegionEpoch(candidate.regionEpoch());
    audit.setEntityId(workItem.getEntityId());
    audit.setPlayableStateScope(blankToEmpty(workItem.getPlayableStateScope()));
    audit.setWorldSlug(blankToEmpty(workItem.getWorldSlug()));
    audit.setRealmSlug(blankToEmpty(workItem.getRealmSlug()));
    audit.setPointerVersion(blankToEmpty(workItem.getPointerVersion()));
    audit.setScriptId(instance.getScriptId());
    audit.setPluginId(blankToEmpty(workItem.getPluginId()));
    audit.setPluginVersionId(blankToEmpty(workItem.getPluginVersionId()));
    audit.setEventType(instance.getEventType());
    audit.setEventSchemaVersion(DEFAULT_SCHEMA_VERSION);
    audit.setScriptPatchVersion(instance.getScriptPatchVersion());
    audit.setScriptEventId(workItem.getScriptEventId());
    audit.setDryRun(false);
    audit.setSourceService(SOURCE_SERVICE);
    audit.setTriggerMode(TriggerMode.TRIGGER_MODE_CATCH_UP.name());
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
    long boundedCadence = Math.max(1L, cadence);
    if (currentDueTick == null) {
      return observedTickId + boundedCadence;
    }
    long nextDueTick = currentDueTick;
    while (nextDueTick <= observedTickId) {
      nextDueTick += boundedCadence;
    }
    return nextDueTick;
  }

  private static String targetEntityId(ScriptScheduleInstance instance) {
    return "ENTITY".equals(instance.getTargetScopeType())
        ? blankToEmpty(instance.getTargetScopeId())
        : "";
  }

  private static String timerScriptEventId(TimerFiringCandidate candidate) {
    return "timer-" + shortHash(candidate.identity());
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
    GetPublishedScriptPatchVersionResponse response =
        gameDesignControlPlaneClient.getPublishedScriptPatchVersion(tenantId, scriptPatchVersion);
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
    var response =
        gameDesignControlPlaneClient.getPublishedPluginVersion(tenantId, pluginId, pluginVersionId);
    if (response == null) {
      return new PluginRuntimeStateService.PluginPublicationLink(
          pluginVersionId,
          0L,
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED,
          "",
          0L,
          "GAME_DESIGN_UNAVAILABLE",
          "Game Design service unavailable");
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
        + candidate.duePointToken()
        + ":"
        + shortHash(instance.getScheduleDefinitionId());
  }

  private static String timerPayload(TimerFiringCandidate candidate) {
    ScriptScheduleInstance instance = candidate.instance();
    String dueField =
        candidate.wallClock()
            ? "\"dueAt\":" + candidate.dueAt().toEpochMilli()
            : "\"dueTickId\":" + candidate.dueTickId();
    return "{\"scheduleId\":\""
        + escape(instance.getScheduleDefinitionId())
        + "\","
        + dueField
        + ",\"targetScopeType\":\""
        + escape(instance.getTargetScopeType())
        + "\",\"targetScopeId\":\""
        + escape(instance.getTargetScopeId())
        + "\"}";
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

  private static String escape(String value) {
    return blankToEmpty(value).replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private ScheduleInstanceSummary toSummary(ScriptScheduleInstance instance) {
    return new ScheduleInstanceSummary(
        instance.getTenantId(),
        instance.getGameInstanceId(),
        instance.getScriptPatchVersion(),
        instance.getScriptId(),
        blankToEmpty(instance.getPlayableStateScope()),
        blankToEmpty(instance.getWorldSlug()),
        blankToEmpty(instance.getRealmSlug()),
        blankToEmpty(instance.getPointerVersion()),
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

  private static String normalizePointerVersion(long pointerVersion) {
    return pointerVersion > 0 ? Long.toString(pointerVersion) : "";
  }

  private static long parsePointerVersion(String pointerVersion) {
    if (pointerVersion == null || pointerVersion.isBlank()) {
      return 0L;
    }
    return Long.parseLong(pointerVersion);
  }

  private record TickAdvanceResult(
      boolean changed, List<Long> fireDueTicks, List<TimerFiringCandidate> suppressedDueTicks) {}

  private record WallClockAdvanceResult(
      boolean changed, Instant fireDueAt, TimerFiringCandidate suppressedCandidate) {}

  private record TimerFiringCandidate(
      ScriptScheduleInstance instance,
      String regionId,
      Long regionEpoch,
      Long dueTickId,
      Instant dueAt,
      boolean wallClock) {
    private static TimerFiringCandidate tick(ScriptScheduleInstance instance, long dueTickId) {
      return new TimerFiringCandidate(
          instance,
          blankToEmpty(instance.getRuntimeRegionId()),
          instance.getRuntimeRegionEpoch(),
          dueTickId,
          null,
          false);
    }

    private static TimerFiringCandidate wallClock(ScriptScheduleInstance instance, Instant dueAt) {
      return new TimerFiringCandidate(
          instance,
          blankToEmpty(instance.getRuntimeRegionId()),
          instance.getRuntimeRegionEpoch(),
          null,
          dueAt,
          true);
    }

    private static TimerFiringCandidate suppressedTick(
        ScriptScheduleInstance instance, long dueTickId, String regionId, Long regionEpoch) {
      return new TimerFiringCandidate(instance, regionId, regionEpoch, dueTickId, null, false);
    }

    private static TimerFiringCandidate suppressedWallClock(
        ScriptScheduleInstance instance, Instant dueAt, String regionId, Long regionEpoch) {
      return new TimerFiringCandidate(instance, regionId, regionEpoch, null, dueAt, true);
    }

    private String duePointToken() {
      return wallClock ? Long.toString(dueAt.toEpochMilli()) : Long.toString(dueTickId);
    }

    private long dueOrderValue() {
      return wallClock ? dueAt.toEpochMilli() : dueTickId;
    }

    private String identity() {
      return instance.getTenantId()
          + "|"
          + instance.getGameInstanceId()
          + "|"
          + regionId
          + "|"
          + regionEpoch
          + "|"
          + targetEntityId(instance)
          + "|"
          + instance.getScriptId()
          + "|"
          + instance.getEventType()
          + "|"
          + instance.getScriptPatchVersion()
          + "|"
          + instance.getScheduleDefinitionId()
          + "|"
          + (wallClock ? "dueAt:" + dueAt.toEpochMilli() : "dueTickId:" + dueTickId);
    }

    private String finalReason() {
      return wallClock ? "timer_due_at_" + dueAt.toEpochMilli() : "timer_due_tick_" + dueTickId;
    }
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
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
