package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;

public interface ScriptScheduleDefinitionService {
  void refreshPatchSchedules(
      String tenantId,
      String scriptPatchVersion,
      List<ScriptDefinition> definitions,
      List<String> affectedScripts);
}
