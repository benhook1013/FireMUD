package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;

public interface ScriptDesignDigestService {
  ScriptDraftDesignDigest getDraftDesignDigestForVersion(String tenantId, String versionId);

  ScriptDraftDesignDigest getDraftDesignDigestForScriptPatch(
      String tenantId, long baseVersionId, String scriptPatchVersion);

  /**
   * Digests already captured owner-local rows; this does not authorize publication or seal storage.
   * The caller owns transaction/immutability and must supply definitions in the original
   * owner-query order. Rows remain mutable and must not change during this call.
   */
  ScriptDraftDesignDigest getDraftDesignDigestForScriptPatchSnapshot(
      String tenantId,
      long baseVersionId,
      String scriptPatchVersion,
      List<ScriptDefinition> definitions,
      List<ScriptEventBinding> bindings);

  record ScriptDraftDesignDigest(
      String tenantId,
      String scopeValue,
      long baseVersionId,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
