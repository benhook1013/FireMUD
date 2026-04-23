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
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptScheduleInstanceRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained internally.")
public class ScriptScheduleInstanceServiceImpl implements ScriptScheduleInstanceService {
  private static final String UNIT_MILLISECONDS = "MILLISECONDS";
  private static final String STATUS_READY = "READY";
  private static final String STATUS_PENDING_RUNTIME_PROGRESS = "PENDING_RUNTIME_PROGRESS";

  private final ScriptScheduleDefinitionRepository scheduleDefinitionRepository;
  private final ScriptScheduleInstanceRepository scheduleInstanceRepository;
  private final ScriptPatchPinProjectionRepository pinProjectionRepository;

  public ScriptScheduleInstanceServiceImpl(
      ScriptScheduleDefinitionRepository scheduleDefinitionRepository,
      ScriptScheduleInstanceRepository scheduleInstanceRepository,
      ScriptPatchPinProjectionRepository pinProjectionRepository) {
    this.scheduleDefinitionRepository = scheduleDefinitionRepository;
    this.scheduleInstanceRepository = scheduleInstanceRepository;
    this.pinProjectionRepository = pinProjectionRepository;
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
              instance.getScheduleDefinitionId()),
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
      String key =
          scopeKey(
              definition.getPluginId(),
              definition.getPluginVersionId(),
              definition.getScheduleDefinitionId());
      desiredKeys.add(key);
      ScriptScheduleInstance instance =
          existingByKey.getOrDefault(key, new ScriptScheduleInstance());
      populateInstance(
          instance, tenantId, gameInstanceId, definition, runtimeState, pinObservedAt, now);
      upserts.add(instance);
    }

    List<ScriptScheduleInstance> deletes =
        existing.stream()
            .filter(
                instance ->
                    !desiredKeys.contains(
                        scopeKey(
                            instance.getPluginId(),
                            instance.getPluginVersionId(),
                            instance.getScheduleDefinitionId())))
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

  private void populateInstance(
      ScriptScheduleInstance instance,
      String tenantId,
      String gameInstanceId,
      ScriptScheduleDefinition definition,
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
    } else {
      instance.setMaterializationStatus(STATUS_PENDING_RUNTIME_PROGRESS);
      instance.setNextDueAt(null);
      instance.setNextDueTickId(null);
    }
    instance.setUpdatedAt(now);
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
        instance.getMaterializationStatus(),
        instance.getNextDueAt() == null ? 0L : instance.getNextDueAt().toEpochMilli(),
        instance.getNextDueTickId() == null ? 0L : instance.getNextDueTickId(),
        instance.getObservedRuntimeVersionId(),
        instance.getLastObservedControlPlaneRequestId(),
        instance.getPinObservedAt().equals(Instant.EPOCH)
            ? 0L
            : instance.getPinObservedAt().toEpochMilli(),
        instance.getMaterializedAt().toEpochMilli(),
        instance.getUpdatedAt().toEpochMilli());
  }

  private static String scopeKey(
      String pluginId, String pluginVersionId, String scheduleDefinitionId) {
    return blankToEmpty(pluginId)
        + "\u0000"
        + blankToEmpty(pluginVersionId)
        + "\u0000"
        + scheduleDefinitionId;
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
