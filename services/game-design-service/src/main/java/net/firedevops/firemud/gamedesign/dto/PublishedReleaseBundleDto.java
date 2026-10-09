package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;

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
    LocalDateTime publishedAt,
    UUID canonicalTenantId,
    UUID canonicalVersionId,
    String publishedReleaseBundleRef,
    Integer manifestSchemaVersion,
    List<PublishedArtifactDigest> artifactDigests,
    WorldPublishedStartLocationEvidence worldPublishedStartLocationEvidence) {
  public PublishedReleaseBundleDto {
    requiredManifestAssetKeys =
        List.copyOf(requiredManifestAssetKeys == null ? List.of() : requiredManifestAssetKeys);
    participantDigests = List.copyOf(participantDigests == null ? List.of() : participantDigests);
    commandDefinitions = List.copyOf(commandDefinitions == null ? List.of() : commandDefinitions);
    if ((manifestSchemaVersion == null) != (artifactDigests == null)) {
      throw new IllegalArgumentException(
          "Manifest schema and artifact proof must be present together");
    }
    artifactDigests = artifactDigests == null ? null : List.copyOf(artifactDigests);
    boolean selectorSchema =
        "v2".equals(attestationSchemaVersion) || "v3".equals(attestationSchemaVersion);
    if (selectorSchema != (worldPublishedStartLocationEvidence != null)) {
      throw new IllegalArgumentException(
          "Selector evidence is mandatory for selected release schemas and forbidden historically");
    }
  }

  /** Original retained bundle construction shape; cannot construct a selector-bearing release. */
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
      List<String> commandDefinitions,
      String generationConfigRevision,
      boolean scriptOnly,
      String scriptPatchVersion,
      LocalDateTime publishedAt,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      Integer manifestSchemaVersion,
      List<PublishedArtifactDigest> artifactDigests) {
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
        commandDefinitions,
        generationConfigRevision,
        scriptOnly,
        scriptPatchVersion,
        publishedAt,
        canonicalTenantId,
        canonicalVersionId,
        publishedReleaseBundleRef,
        manifestSchemaVersion,
        artifactDigests,
        null);
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
      LocalDateTime publishedAt,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      Integer manifestSchemaVersion,
      List<PublishedArtifactDigest> artifactDigests) {
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
        publishedAt,
        canonicalTenantId,
        canonicalVersionId,
        publishedReleaseBundleRef,
        manifestSchemaVersion,
        artifactDigests);
  }

  /** Retained rows may lack canonical identity; this shape grants no launch authority. */
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
      List<String> commandDefinitions,
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
        commandDefinitions,
        generationConfigRevision,
        scriptOnly,
        scriptPatchVersion,
        publishedAt,
        null,
        null,
        null,
        null,
        null,
        null);
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
        publishedAt,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
