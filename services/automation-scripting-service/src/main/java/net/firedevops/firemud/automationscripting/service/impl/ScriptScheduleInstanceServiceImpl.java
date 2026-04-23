package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleInstanceRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
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

  private final ScriptScheduleDefinitionRepository scheduleDefinitionRepository;
  private final ScriptScheduleInstanceRepository scheduleInstanceRepository;
  private final ScriptPatchPinProjectionRepository pinProjectionRepository;
  private final PluginRuntimeStateRepository pluginRuntimeStateRepository;
  private final ScriptEventBindingRepository bindingRepository;

  public ScriptScheduleInstanceServiceImpl(
      ScriptScheduleDefinitionRepository scheduleDefinitionRepository,
      ScriptScheduleInstanceRepository scheduleInstanceRepository,
      ScriptPatchPinProjectionRepository pinProjectionRepository,
      PluginRuntimeStateRepository pluginRuntimeStateRepository,
      ScriptEventBindingRepository bindingRepository) {
    this.scheduleDefinitionRepository = scheduleDefinitionRepository;
    this.scheduleInstanceRepository = scheduleInstanceRepository;
    this.pinProjectionRepository = pinProjectionRepository;
    this.pluginRuntimeStateRepository = pluginRuntimeStateRepository;
    this.bindingRepository = bindingRepository;
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
    List<ScriptScheduleInstance> instances =
        scheduleInstanceRepository.findByTenantIdAndGameInstanceIdAndCadenceUnit(
            observation.tenantId(), observation.gameInstanceId(), UNIT_TICKS);
    List<ScriptScheduleInstance> updates = new ArrayList<>();
    for (ScriptScheduleInstance instance : instances) {
      if (advanceRuntimeProgress(instance, observation, observedAt, now)) {
        updates.add(instance);
      }
    }
    if (!updates.isEmpty()) {
      scheduleInstanceRepository.saveAll(updates);
    }
    return new RuntimeTickProgressResult(updates.size());
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

  private boolean advanceRuntimeProgress(
      ScriptScheduleInstance instance,
      RuntimeTickProgressObservation observation,
      Instant observedAt,
      Instant now) {
    boolean runtimeScopeChanged =
        !observation.regionId().equals(blankToEmpty(instance.getRuntimeRegionId()))
            || instance.getRuntimeRegionEpoch() == null
            || instance.getRuntimeRegionEpoch() != observation.regionEpoch();
    Long currentDueTick = instance.getNextDueTickId();
    long nextDueTick =
        runtimeScopeChanged || currentDueTick == null || currentDueTick <= observation.tickId()
            ? observation.tickId() + Math.max(1L, instance.getCadenceValue())
            : currentDueTick;
    boolean changed =
        runtimeScopeChanged
            || !STATUS_READY.equals(instance.getMaterializationStatus())
            || currentDueTick == null
            || currentDueTick != nextDueTick
            || instance.getLastObservedTickId() == null
            || instance.getLastObservedTickId() != observation.tickId();
    if (!changed) {
      return false;
    }
    instance.setMaterializationStatus(STATUS_READY);
    instance.setRuntimeRegionId(observation.regionId());
    instance.setRuntimeRegionEpoch(observation.regionEpoch());
    instance.setLastObservedTickId(observation.tickId());
    instance.setLastRuntimeProgressObservedAt(observedAt);
    instance.setNextDueTickId(nextDueTick);
    instance.setNextDueAt(null);
    instance.setUpdatedAt(now);
    return true;
  }

  private ScheduleInstanceSummary toSummary(ScriptScheduleInstance instance) {
    return new ScheduleInstanceSummary(
        instance.getTenantId(),
        instance.getGameInstanceId(),
        instance.getScriptPatchVersion(),
        instance.getScriptId(),
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
            : instance.getLastRuntimeProgressObservedAt().toEpochMilli());
  }

  private static String scopeKey(
      String pluginId,
      String pluginVersionId,
      String scheduleDefinitionId,
      String targetScopeType,
      String targetScopeId) {
    return blankToEmpty(pluginId)
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

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }
}
