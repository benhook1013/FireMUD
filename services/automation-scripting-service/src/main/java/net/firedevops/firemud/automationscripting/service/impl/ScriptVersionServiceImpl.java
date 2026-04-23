package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Default in-memory implementation which simply logs reload requests. Real implementation would
 * reload affected scripts and update registries.
 */
@Service
@RequiredArgsConstructor
public class ScriptVersionServiceImpl implements ScriptVersionService {
  private static final Logger logger = LoggingUtil.getLogger(ScriptVersionServiceImpl.class);

  private final ScriptDefinitionRepository repository;
  private final ScriptScheduleDefinitionService scheduleDefinitionService;
  private final Map<Long, Map<String, String>> registry = new ConcurrentHashMap<>();

  @Override
  @Timed(value = "script.version.notify")
  public void notifyUpdate(
      String tenantId, String scriptPatchVersion, List<String> affectedScripts) {
    Long tenantKey = Long.parseLong(tenantId);
    logger.info(
        "Applying script patch {} for tenant {} affecting {} scripts",
        scriptPatchVersion,
        tenantId,
        affectedScripts.size());
    if (affectedScripts.isEmpty()) {
      logger.info("No scripts provided for patch {}", scriptPatchVersion);
      return;
    }
    List<ScriptDefinition> defs =
        repository.findByTenantIdAndScriptVersionAndNameIn(
            tenantKey, scriptPatchVersion, affectedScripts);
    scheduleDefinitionService.refreshPatchSchedules(
        tenantId, scriptPatchVersion, defs, affectedScripts);
    Map<String, String> map = registry.computeIfAbsent(tenantKey, id -> new ConcurrentHashMap<>());
    affectedScripts.forEach(map::remove);
    for (ScriptDefinition def : defs) {
      map.put(def.getName(), def.getDefinition());
    }
    logger.info("Reloaded {} scripts for patch {}", defs.size(), scriptPatchVersion);
  }
}
