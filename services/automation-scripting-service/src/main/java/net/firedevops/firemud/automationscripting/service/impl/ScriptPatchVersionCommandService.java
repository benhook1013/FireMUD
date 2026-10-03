package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptEventIngressService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained only for command orchestration.")
public class ScriptPatchVersionCommandService {
  private static final Logger logger =
      LoggingUtil.getLogger(ScriptPatchVersionCommandService.class);

  private final ScriptDefinitionRepository repository;
  private final ScriptScheduleDefinitionService scheduleDefinitionService;
  private final ScriptScheduleInstanceService scheduleInstanceService;
  private final ScriptEventIngressService scriptEventIngressService;
  private final ScriptPatchReadinessProjectionService readinessProjectionService;
  private final Map<Long, Map<String, String>> registry = new ConcurrentHashMap<>();

  public ScriptPatchVersionCommandService(
      ScriptDefinitionRepository repository,
      ScriptScheduleDefinitionService scheduleDefinitionService,
      ScriptScheduleInstanceService scheduleInstanceService,
      ScriptEventIngressService scriptEventIngressService,
      ScriptPatchReadinessProjectionService readinessProjectionService) {
    this.repository = repository;
    this.scheduleDefinitionService = scheduleDefinitionService;
    this.scheduleInstanceService = scheduleInstanceService;
    this.scriptEventIngressService = scriptEventIngressService;
    this.readinessProjectionService = readinessProjectionService;
  }

  @Timed(value = "script.version.notify")
  public boolean notifyUpdate(
      String tenantId,
      long baseVersionId,
      String scriptPatchVersion,
      List<String> affectedScripts) {
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    if (baseVersionId <= 0L) {
      throw new IllegalArgumentException("base_version_id must be positive");
    }
    logger.info(
        "Applying script patch {} for tenant {} affecting {} scripts",
        scriptPatchVersion,
        tenantId,
        affectedScripts.size());
    if (affectedScripts.isEmpty()) {
      throw new IllegalArgumentException("zero_handler_manifest_unverifiable");
    }
    Set<String> requestedNames = new HashSet<>(affectedScripts);
    if (requestedNames.size() != affectedScripts.size()) {
      throw new IllegalArgumentException(
          "affectedScripts must resolve exactly one definition per unique requested name");
    }
    List<String> canonicalScriptNames = requestedNames.stream().sorted().toList();
    List<ScriptDefinition> defs =
        repository.findByTenantIdAndScriptVersionAndNameIn(
            tenantKey, scriptPatchVersion, canonicalScriptNames);
    Set<String> resolvedNames =
        defs.stream().map(ScriptDefinition::getName).collect(java.util.stream.Collectors.toSet());
    if (defs.size() != requestedNames.size()
        || defs.stream().map(ScriptDefinition::getName).distinct().count() != requestedNames.size()
        || !requestedNames.equals(resolvedNames)) {
      throw new IllegalArgumentException(
          "affectedScripts must resolve exactly one definition per unique requested name");
    }
    if (defs.stream()
        .anyMatch(
            definition ->
                definition.getBaseVersionId() == null
                    || definition.getBaseVersionId() != baseVersionId
                    || !scriptPatchVersion.equals(definition.getScriptVersion()))) {
      throw new IllegalArgumentException("script_patch_base_version_mismatch");
    }
    defs = defs.stream().sorted(java.util.Comparator.comparing(ScriptDefinition::getName)).toList();
    Optional<Long> retainedBaseVersionId =
        repository.findScriptPatchBaseVersionId(tenantId, scriptPatchVersion);
    if (retainedBaseVersionId.isEmpty()) {
      throw new IllegalArgumentException("script_patch_base_version_unavailable");
    }
    if (retainedBaseVersionId.get() != baseVersionId) {
      throw new IllegalArgumentException("script_patch_base_version_mismatch");
    }
    readinessProjectionService.beginPatchReadiness(
        tenantId, baseVersionId, scriptPatchVersion, canonicalScriptNames);
    List<ScriptDefinition> canonicalDefinitions = defs;
    boolean applied =
        readinessProjectionService.applyIfCurrent(
            tenantId,
            scriptPatchVersion,
            canonicalScriptNames,
            admitOnLoad -> {
              if (admitOnLoad) {
                canonicalDefinitions.forEach(
                    def -> admitOnLoad(tenantId, baseVersionId, scriptPatchVersion, def));
              }
              scheduleDefinitionService.refreshPatchSchedules(
                  tenantId, scriptPatchVersion, canonicalDefinitions, canonicalScriptNames);
              scheduleInstanceService.reconcilePinnedPatchInstances(tenantId, scriptPatchVersion);
            });
    if (!applied) {
      return false;
    }
    boolean registryRebuilt =
        readinessProjectionService.rebuildRegistryIfCurrent(
            tenantId,
            scriptPatchVersion,
            canonicalScriptNames,
            () -> rebuildRegistry(tenantKey, canonicalScriptNames, canonicalDefinitions));
    if (!registryRebuilt) {
      return false;
    }
    logger.info("Reloaded {} scripts for patch {}", defs.size(), scriptPatchVersion);
    return true;
  }

  private void rebuildRegistry(
      long tenantKey, List<String> scriptNames, List<ScriptDefinition> definitions) {
    Map<String, String> map = registry.computeIfAbsent(tenantKey, id -> new ConcurrentHashMap<>());
    scriptNames.forEach(map::remove);
    for (ScriptDefinition definition : definitions) {
      map.put(definition.getName(), definition.getDefinition());
    }
  }

  private void admitOnLoad(
      String tenantId, long baseVersionId, String scriptPatchVersion, ScriptDefinition definition) {
    TriggerScriptEventRequest request =
        TriggerScriptEventRequest.newBuilder()
            .setTenantId(tenantId)
            .setScriptId(definition.getName())
            .setScriptPatchBaseVersionId(baseVersionId)
            .setEventType("onLoad")
            .setEventSchemaVersion("v1")
            .setScriptPatchVersion(scriptPatchVersion)
            .setScriptEventId(
                onLoadScriptEventId(tenantId, scriptPatchVersion, definition.getName()))
            .setTriggerMode(TriggerMode.TRIGGER_MODE_NORMAL)
            .setPayloadJson("{}")
            .build();
    scriptEventIngressService.admit(request, "automation-scripting-service");
  }

  private static String onLoadScriptEventId(
      String tenantId, String scriptPatchVersion, String scriptId) {
    return "onload:" + tenantId + ":" + scriptPatchVersion + ":" + scriptId;
  }
}
