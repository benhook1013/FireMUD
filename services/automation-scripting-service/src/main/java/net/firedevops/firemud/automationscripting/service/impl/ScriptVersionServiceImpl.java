package net.firedevops.firemud.automationscripting.service.impl;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import org.springframework.stereotype.Service;

@Service
public class ScriptVersionServiceImpl implements ScriptVersionService {
  private final ScriptPatchVersionCommandService commandService;
  private final Optional<TemporalScriptPatchReadinessOrchestrator> temporalOrchestrator;

  public ScriptVersionServiceImpl(
      ScriptPatchVersionCommandService commandService,
      Optional<TemporalScriptPatchReadinessOrchestrator> temporalOrchestrator) {
    this.commandService = commandService;
    this.temporalOrchestrator = temporalOrchestrator;
  }

  @Override
  public void notifyUpdate(
      String tenantId, String scriptPatchVersion, List<String> affectedScripts) {
    commandService.notifyUpdate(tenantId, scriptPatchVersion, affectedScripts);
    if (temporalOrchestrator.isPresent() && !affectedScripts.isEmpty()) {
      temporalOrchestrator.get().startTracking(tenantId, scriptPatchVersion);
    }
  }
}
