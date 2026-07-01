package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;
import java.util.List;

public record GameInstanceRuntimeStateDto(
    long tenantId,
    long gameInstanceId,
    String runtimeVersionId,
    String pinnedScriptPatchVersion,
    String launchDescriptorId,
    String status,
    Long versionId,
    Long releaseBundleId,
    long versionStateEpoch,
    Instant scriptPatchPinnedAt,
    String scriptPatchPinnedBy,
    String scriptPatchPinnedReason,
    String scriptPatchPinnedControlPlaneRequestId,
    String playableStateScope,
    String worldSlug,
    String realmSlug,
    Long pointerVersion,
    ScriptPatchPublicationLinkDto publication,
    String regionId,
    long regionEpoch,
    List<AdmissionPointerDto> currentAdmissionPointers) {
  public GameInstanceRuntimeStateDto {
    currentAdmissionPointers = List.copyOf(currentAdmissionPointers);
  }

  public record ScriptPatchPublicationLinkDto(
      String scriptPatchVersion,
      Long versionId,
      Long baseVersionId,
      String publicationState,
      Instant lastChangedAt,
      String lookupErrorCode,
      String lookupErrorMessage) {}
}
