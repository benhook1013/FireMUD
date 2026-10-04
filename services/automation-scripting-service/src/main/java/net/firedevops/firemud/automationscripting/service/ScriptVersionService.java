package net.firedevops.firemud.automationscripting.service;

import java.util.List;

/** Handles live script patch updates. */
public interface ScriptVersionService {
  boolean notifyUpdate(
      String tenantId, long baseVersionId, String scriptPatchVersion, List<String> affectedScripts);
}
