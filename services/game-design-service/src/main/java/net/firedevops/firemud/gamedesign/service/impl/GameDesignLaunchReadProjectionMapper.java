package net.firedevops.firemud.gamedesign.service.impl;

import java.util.List;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;

/** Pure mapping from Game Design's current owner projections to the existing launch read protos. */
public final class GameDesignLaunchReadProjectionMapper {
  private GameDesignLaunchReadProjectionMapper() {}

  public static PublishedReleaseBundle toProto(PublishedReleaseBundleDto bundle) {
    PublishedReleaseBundle.Builder builder =
        PublishedReleaseBundle.newBuilder()
            .setId(bundle.id())
            .setVersionId(bundle.versionId())
            .setVersionNumber(bundle.versionNumber())
            .setAttestationSchemaVersion(bundle.attestationSchemaVersion())
            .setPublishWorkflowId(bundle.publishWorkflowId())
            .setManifestHash(bundle.manifestHash())
            .addAllRequiredManifestAssetKeys(bundle.requiredManifestAssetKeys())
            .addAllCommandDefinitions(bundle.commandDefinitions())
            .addAllParticipantDigests(
                bundle.participantDigests().stream()
                    .map(
                        digest -> {
                          ParticipantDigest.Builder participant =
                              ParticipantDigest.newBuilder()
                                  .setParticipantKey(digest.participantKey())
                                  .setScopeValue(digest.scopeValue())
                                  .setAppliedCommitId(
                                      digest.appliedCommitId() == null
                                          ? ""
                                          : digest.appliedCommitId())
                                  .setContentDigest(
                                      digest.contentDigest() == null ? "" : digest.contentDigest())
                                  .setDigestSchemaVersion(
                                      digest.digestSchemaVersion() == null
                                          ? 0
                                          : digest.digestSchemaVersion());
                          if (digest.abilitySchemaDigest() != null) {
                            participant.setAbilitySchemaDigest(digest.abilitySchemaDigest());
                          }
                          return participant.build();
                        })
                    .toList())
            .setGenerationConfigRevision(bundle.generationConfigRevision())
            .setIsScriptOnly(bundle.scriptOnly())
            .setScriptPatchVersion(
                bundle.scriptPatchVersion() == null ? "" : bundle.scriptPatchVersion())
            .setPublishedAt(bundle.publishedAt().toString())
            .setPublishedReleaseBundleRef(
                bundle.publishedReleaseBundleRef() == null
                    ? ""
                    : bundle.publishedReleaseBundleRef())
            .setCanonicalTenantId(
                bundle.canonicalTenantId() == null ? "" : bundle.canonicalTenantId().toString())
            .setCanonicalVersionId(
                bundle.canonicalVersionId() == null ? "" : bundle.canonicalVersionId().toString())
            .addAllArtifactDigests(
                bundle.artifactDigests() == null
                    ? List.of()
                    : bundle.artifactDigests().stream()
                        .map(GameDesignLaunchReadProjectionMapper::toProto)
                        .toList());
    if (bundle.manifestSchemaVersion() != null) {
      builder.setManifestSchemaVersion(bundle.manifestSchemaVersion());
    }
    return builder.build();
  }

  public static VersionAssetArtifactState toProto(VersionAssetArtifactStateDto state) {
    return VersionAssetArtifactState.newBuilder()
        .setTenantId(state.tenantId())
        .setVersionId(state.versionId())
        .setExportedVersionNumber(state.exportedVersionNumber())
        .setArtifactState(ArtifactState.valueOf("ARTIFACT_STATE_" + state.artifactState()))
        .setStateEpoch(state.stateEpoch())
        .setManifestHash(state.manifestHash() == null ? "" : state.manifestHash())
        .setLastWorkflowId(state.lastWorkflowId() == null ? "" : state.lastWorkflowId())
        .setLastErrorCode(state.lastErrorCode() == null ? "" : state.lastErrorCode())
        .setLastErrorMessage(state.lastErrorMessage() == null ? "" : state.lastErrorMessage())
        .setUpdatedAt(state.updatedAt().toString())
        .addAllExportedManifestAssetKeys(state.exportedManifestAssetKeys())
        .build();
  }

  public static VersionStateSnapshot toProto(VersionStateDto state) {
    return VersionStateSnapshot.newBuilder()
        .setTenantId(state.tenantId())
        .setVersionId(state.versionId())
        .setVersionState(toProto(state.versionState()))
        .setVersionStateEpoch(state.versionStateEpoch())
        .setUpdatedAt(state.updatedAt().toString())
        .build();
  }

  public static VersionLifecycleState toProto(
      net.firedevops.firemud.gamedesign.model.VersionLifecycleState state) {
    return switch (state) {
      case DRAFT -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT;
      case PUBLISHED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED;
      case ACTIVE -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE;
      case FAILED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_FAILED;
      case RETIRED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_RETIRED;
      case UPLOAD_REJECTED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_UPLOAD_REJECTED;
      case SIGNATURE_VERIFIED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_SIGNATURE_VERIFIED;
      case VALIDATION_FAILED_DESIGN ->
          VersionLifecycleState.VERSION_LIFECYCLE_STATE_VALIDATION_FAILED_DESIGN;
      case SUPERSEDED -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_SUPERSEDED;
      case REVOKED_DESIGN -> VersionLifecycleState.VERSION_LIFECYCLE_STATE_REVOKED_DESIGN;
    };
  }

  private static net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest toProto(
      PublishedArtifactDigest digest) {
    return net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
        .setUsageKey(digest.usageKey())
        .setArtifactKind(digest.artifactKind())
        .setImmutableObjectKey(digest.immutableObjectKey())
        .setContentDigest(digest.contentDigest())
        .setContentType(digest.contentType())
        .setArtifactSchemaVersion(digest.artifactSchemaVersion())
        .build();
  }
}
