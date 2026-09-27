package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;

/** Resolves a publication link using the exact admitted script patch provenance. */
final class ScriptPatchPublicationLinkResolver {
  private ScriptPatchPublicationLinkResolver() {}

  static ScriptPatchPublicationLink resolve(
      GameDesignClient gameDesignClient,
      long tenantId,
      String scriptPatchVersion,
      Long baseVersionId) {
    String normalizedScriptPatchVersion = scriptPatchVersion == null ? "" : scriptPatchVersion;
    if (baseVersionId == null || baseVersionId <= 0L) {
      return lookupError(
          normalizedScriptPatchVersion,
          "SCRIPT_PATCH_BASE_VERSION_REQUIRED",
          "Exact admitted script patch base version is unavailable");
    }

    GetPublishedScriptPatchVersionResponse response =
        gameDesignClient == null
            ? null
            : gameDesignClient.getPublishedScriptPatchVersion(
                tenantId, normalizedScriptPatchVersion, baseVersionId);
    if (response == null) {
      return lookupError(
          normalizedScriptPatchVersion,
          "SCRIPT_PATCH_PUBLICATION_LOOKUP_UNAVAILABLE",
          "Game Design returned no script patch lookup response");
    }
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return lookupError(
          normalizedScriptPatchVersion,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    if (!response.hasScriptPatch()) {
      return lookupError(
          normalizedScriptPatchVersion,
          "SCRIPT_PATCH_PUBLICATION_LOOKUP_EMPTY",
          "Game Design returned no published script patch");
    }
    if (!Long.toString(tenantId).equals(response.getScriptPatch().getTenantId())) {
      return lookupError(
          normalizedScriptPatchVersion,
          "SCRIPT_PATCH_PROVENANCE_MISMATCH",
          "Published script patch tenant does not match the requested tenant");
    }
    if (!normalizedScriptPatchVersion.equals(response.getScriptPatch().getScriptPatchVersion())) {
      return lookupError(
          normalizedScriptPatchVersion,
          "SCRIPT_PATCH_PROVENANCE_MISMATCH",
          "Published script patch version does not match the admitted script patch");
    }
    if (response.getScriptPatch().getBaseVersionId() != baseVersionId) {
      return lookupError(
          normalizedScriptPatchVersion,
          "SCRIPT_PATCH_PROVENANCE_MISMATCH",
          "Published script patch base version does not match the admitted base version");
    }
    return ScriptPatchPublicationLink.newBuilder()
        .setScriptPatchVersion(response.getScriptPatch().getScriptPatchVersion())
        .setVersionId(response.getScriptPatch().getVersionId())
        .setBaseVersionId(response.getScriptPatch().getBaseVersionId())
        .setPublicationState(response.getScriptPatch().getPublicationState())
        .setLastChangedAtMs(response.getScriptPatch().getLastChangedAtMs())
        .build();
  }

  private static ScriptPatchPublicationLink lookupError(
      String scriptPatchVersion, String code, String message) {
    return ScriptPatchPublicationLink.newBuilder()
        .setScriptPatchVersion(scriptPatchVersion)
        .setVersionId(0L)
        .setBaseVersionId(0L)
        .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED)
        .setLastChangedAtMs(0L)
        .setLookupErrorCode(code)
        .setLookupErrorMessage(message)
        .build();
  }
}
