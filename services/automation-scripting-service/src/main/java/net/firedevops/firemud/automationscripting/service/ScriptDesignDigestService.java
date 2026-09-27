package net.firedevops.firemud.automationscripting.service;

public interface ScriptDesignDigestService {
  ScriptDraftDesignDigest getDraftDesignDigestForVersion(String tenantId, String versionId);

  ScriptDraftDesignDigest getDraftDesignDigestForScriptPatch(
      String tenantId, long baseVersionId, String scriptPatchVersion);

  record ScriptDraftDesignDigest(
      String tenantId,
      String scopeValue,
      long baseVersionId,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
