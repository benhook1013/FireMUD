package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record PinnedScriptPatchVersionDto(
    String pinnedScriptPatchVersion,
    Instant pinnedAt,
    String pinnedBy,
    String controlPlaneRequestId,
    ScriptPatchPublicationLinkDto publication) {
  public record ScriptPatchPublicationLinkDto(
      String scriptPatchVersion,
      Long versionId,
      Long baseVersionId,
      String publicationState,
      Instant lastChangedAt,
      String lookupErrorCode,
      String lookupErrorMessage) {}
}
