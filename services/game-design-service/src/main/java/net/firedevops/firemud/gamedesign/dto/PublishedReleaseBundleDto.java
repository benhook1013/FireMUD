package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PublishedReleaseBundleDto(
    Long id,
    String tenantId,
    Long versionId,
    int versionNumber,
    String attestationSchemaVersion,
    String publishWorkflowId,
    String manifestHash,
    List<String> requiredManifestAssetKeys,
    List<PublishParticipantDigestDto> participantDigests,
    String generationConfigRevision,
    boolean scriptOnly,
    String scriptPatchVersion,
    LocalDateTime publishedAt) {
  public PublishedReleaseBundleDto {
    requiredManifestAssetKeys =
        List.copyOf(requiredManifestAssetKeys == null ? List.of() : requiredManifestAssetKeys);
    participantDigests = List.copyOf(participantDigests == null ? List.of() : participantDigests);
  }
}
