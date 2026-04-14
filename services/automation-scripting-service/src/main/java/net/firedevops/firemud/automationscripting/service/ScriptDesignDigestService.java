package net.firedevops.firemud.automationscripting.service;

public interface ScriptDesignDigestService {
  ScriptDraftDesignDigest getDraftDesignDigest(String tenantId, String scriptPatchVersion);

  record ScriptDraftDesignDigest(
      String tenantId,
      String scriptPatchVersion,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
