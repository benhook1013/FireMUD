package net.firedevops.firemud.gamedesign.publication;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;

/** Shared canonical conversion from the published release DTO into retained read evidence. */
final class PublishedReleaseEvidenceEncoding {
  private PublishedReleaseEvidenceEncoding() {}

  static PublishedReleaseBundle toPublishedReleaseBundle(PublishedReleaseBundleDto dto) {
    Objects.requireNonNull(dto, "dto");
    var builder =
        PublishedReleaseBundle.newBuilder()
            .setId(dto.id())
            .setVersionId(dto.versionId())
            .setVersionNumber(dto.versionNumber())
            .setAttestationSchemaVersion(dto.attestationSchemaVersion())
            .setPublishWorkflowId(dto.publishWorkflowId())
            .setManifestHash(dto.manifestHash())
            .addAllRequiredManifestAssetKeys(dto.requiredManifestAssetKeys())
            .setIsScriptOnly(dto.scriptOnly())
            .setScriptPatchVersion(Objects.requireNonNullElse(dto.scriptPatchVersion(), ""))
            .setPublishedAt(dto.publishedAt().toString())
            .setGenerationConfigRevision(dto.generationConfigRevision())
            .addAllCommandDefinitions(dto.commandDefinitions())
            .setPublishedReleaseBundleRef(dto.publishedReleaseBundleRef())
            .setCanonicalTenantId(dto.canonicalTenantId().toString())
            .setCanonicalVersionId(dto.canonicalVersionId().toString());
    for (PublishParticipantDigestDto participant : dto.participantDigests()) {
      if (!participant.succeeded()) throw new FailedParticipantException();
      var value =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(Objects.requireNonNull(participant.appliedCommitId()))
              .setContentDigest(Objects.requireNonNull(participant.contentDigest()))
              .setDigestSchemaVersion(Objects.requireNonNull(participant.digestSchemaVersion()));
      if (participant.abilitySchemaDigest() != null) {
        value.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      builder.addParticipantDigests(value);
    }
    if (dto.manifestSchemaVersion() != null) {
      builder.setManifestSchemaVersion(dto.manifestSchemaVersion());
    }
    if (dto.artifactDigests() != null) {
      for (PublishedArtifactDigest artifact : dto.artifactDigests()) {
        builder.addArtifactDigests(
            net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                .setUsageKey(artifact.usageKey())
                .setArtifactKind(artifact.artifactKind())
                .setImmutableObjectKey(artifact.immutableObjectKey())
                .setContentDigest(artifact.contentDigest())
                .setContentType(artifact.contentType())
                .setArtifactSchemaVersion(artifact.artifactSchemaVersion()));
      }
    }
    return builder.build();
  }

  static GameDesignPublicationTerminalEvidence.ReleaseContent releaseContent(
      PublishedReleaseBundleDto dto) {
    Objects.requireNonNull(dto, "dto");
    if (dto.manifestSchemaVersion() == null || dto.artifactDigests() == null) {
      throw new IncompleteArtifactProofException();
    }
    List<GameDesignPublicationTerminalEvidence.Participant> participants =
        dto.participantDigests().stream()
            .map(
                value ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        value.participantKey(),
                        value.scopeValue(),
                        value.baseVersionId(),
                        value.appliedCommitId(),
                        value.contentDigest(),
                        Objects.requireNonNull(value.digestSchemaVersion()),
                        value.abilitySchemaDigest(),
                        value.errorCode(),
                        value.errorMessage()))
            .toList();
    List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts =
        dto.artifactDigests().stream()
            .map(
                value ->
                    new AuthoredWorldReleaseAttestationEvidence.Artifact(
                        value.usageKey(),
                        value.artifactKind(),
                        value.immutableObjectKey(),
                        value.contentDigest(),
                        value.contentType(),
                        value.artifactSchemaVersion()))
            .toList();
    return new GameDesignPublicationTerminalEvidence.ReleaseContent(
        dto.canonicalTenantId(),
        dto.canonicalVersionId(),
        dto.publishedReleaseBundleRef(),
        dto.versionNumber(),
        dto.attestationSchemaVersion(),
        dto.publishWorkflowId(),
        dto.manifestHash(),
        dto.manifestSchemaVersion(),
        artifacts,
        dto.requiredManifestAssetKeys(),
        participants,
        dto.commandDefinitions(),
        dto.generationConfigRevision(),
        dto.worldPublishedStartLocationEvidence());
  }

  static final class FailedParticipantException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private FailedParticipantException() {
      super("Published release participant did not succeed");
    }
  }

  static final class IncompleteArtifactProofException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private IncompleteArtifactProofException() {
      super("Immutable release content evidence is incomplete");
    }
  }
}
