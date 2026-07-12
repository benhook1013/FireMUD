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
    List<String> commandDefinitions,
    String generationConfigRevision,
    boolean scriptOnly,
    String scriptPatchVersion,
    LocalDateTime publishedAt) {
  public PublishedReleaseBundleDto {
    requiredManifestAssetKeys =
        List.copyOf(requiredManifestAssetKeys == null ? List.of() : requiredManifestAssetKeys);
    participantDigests = List.copyOf(participantDigests == null ? List.of() : participantDigests);
    commandDefinitions = List.copyOf(commandDefinitions == null ? List.of() : commandDefinitions);
  }

  public PublishedReleaseBundleDto(
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
    this(
        id,
        tenantId,
        versionId,
        versionNumber,
        attestationSchemaVersion,
        publishWorkflowId,
        manifestHash,
        requiredManifestAssetKeys,
        participantDigests,
        List.of(),
        generationConfigRevision,
        scriptOnly,
        scriptPatchVersion,
        publishedAt);
  }
}
