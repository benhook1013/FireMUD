package net.firedevops.firemud.service;

import java.util.List;

/** Handles live script patch updates. */
public interface ScriptVersionService {
  void notifyUpdate(String tenantId, String scriptPatchVersion, List<String> affectedScripts);
}
